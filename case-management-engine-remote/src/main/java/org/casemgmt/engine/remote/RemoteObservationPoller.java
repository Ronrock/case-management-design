package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.ObservationCursor;
import org.casemgmt.observation.ObservationEnvelope;
import org.casemgmt.observation.ObservationStream;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.RemoteObservationInboxWorker;
import org.casemgmt.observation.RemoteObservationIngestionService;
import org.casemgmt.observation.UserTaskObservation;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.repo.CaseRepository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remote history is first made durable, then applied through the common lifecycle handler. */
public final class RemoteObservationPoller {
    static final Duration OVERLAP = Duration.ofMinutes(2);
    static final Duration INITIAL_LOOKBACK = Duration.ofMinutes(10);
    private static final int PAGE_SIZE = 500;
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private final RestClient client;
    private final ActiveBpmnCaseRepository activeCases;
    private final CaseRepository cases;
    private final RemoteProcessActivityClassifier classifier;
    private final RemoteObservationIngestionService ingestion;
    private final RemoteObservationInboxWorker inboxWorker;

    public RemoteObservationPoller(RestClient client, ActiveBpmnCaseRepository activeCases,
                                   CaseRepository cases, RemoteProcessActivityClassifier classifier,
                                   RemoteObservationIngestionService ingestion,
                                   RemoteObservationInboxWorker inboxWorker) {
        this.client = client; this.activeCases = activeCases; this.cases = cases;
        this.classifier = classifier; this.ingestion = ingestion; this.inboxWorker = inboxWorker;
    }

    /** Reconciliation produces normal inbox evidence; it never mutates projections directly. */
    public int reconcileAllActive() {
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC); int count = 0;
        for (ActiveBpmnCaseRepository.ActiveCase active : activeCases.findAll()) {
            Map<String, Object> process = getMap("/history/process-instance/" + active.rootProcessInstanceId());
            if (process != null && process.get("endTime") != null) {
                OffsetDateTime engineAt = timeOr(process.get("endTime"), receivedAt);
                ProcessObservation.EventType event = process.get("deleteReason") == null ? ProcessObservation.EventType.COMPLETED : ProcessObservation.EventType.TERMINATED;
                ObservationEnvelope envelope = new ObservationEnvelope(new ProcessObservation(
                        "remote-process-" + active.rootProcessInstanceId() + "-" + event, 1, "remote-history",
                        active.engineId(), active.tenantId(), active.caseId(), active.rootProcessInstanceId(),
                        active.rootProcessInstanceId(), null, event, engineAt.toInstant(), receivedAt.toInstant(),
                        Map.of("processDefinitionKey", string(process.get("processDefinitionKey")))));
                count += ingestion.persist(active.tenantId(), ObservationStream.PROCESSES, List.of(envelope));
            }
        }
        inboxWorker.drainOnce(); return count;
    }

    public int pollOnce() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int count = pollTasks(now) + pollActivities(now) + pollProcesses(now);
        inboxWorker.drainOnce(); return count;
    }

    private int pollTasks(OffsetDateTime receivedAt) { return pollPages(ObservationStream.TASKS, "/history/task?startedAfter=", "startTime", receivedAt, this::taskEnvelope); }
    private int pollActivities(OffsetDateTime receivedAt) { return pollPages(ObservationStream.ACTIVITIES, "/history/activity-instance?startedAfter=", "startTime", receivedAt, this::activityEnvelope); }
    private int pollProcesses(OffsetDateTime receivedAt) { return pollPages(ObservationStream.PROCESSES, "/history/process-instance?finished=true&finishedAfter=", "endTime", receivedAt, this::processEnvelope); }

    /** A page's rows are pair-ordered and durably inserted before any matching tenant cursor moves. */
    private int pollPages(ObservationStream stream, String path, String timestampField,
                          OffsetDateTime receivedAt, EnvelopeFactory factory) {
        // A cursor is persisted per tenant/feed. Querying a conservative overlap ensures a
        // restarted process may safely replay page boundaries; fingerprints deduplicate the replay.
        OffsetDateTime from = ingestion.oldestCursor(stream)
                .map(ObservationCursor::timestamp)
                .map(at -> OffsetDateTime.ofInstant(at, ZoneOffset.UTC).minus(OVERLAP))
                .orElse(receivedAt.minus(INITIAL_LOOKBACK)); int inserted = 0;
        for (int first = 0; ; first += PAGE_SIZE) {
            List<Map<String,Object>> page = getList(path + encoded(from) + "&firstResult=" + first + "&maxResults=" + PAGE_SIZE + "&sortBy=" + timestampField + "&sortOrder=asc");
            List<HistoryRow> ordered = page.stream().map(row -> new HistoryRow(new ObservationCursor(timestamp(row, timestampField).toInstant(), requiredId(row)), row)).sorted(Comparator.comparing(HistoryRow::cursor)).toList();
            inserted += persistPage(stream, ordered, receivedAt, factory);
            if (page.size() < PAGE_SIZE) return inserted;
        }
    }

    private int persistPage(ObservationStream stream, List<HistoryRow> rows, OffsetDateTime receivedAt, EnvelopeFactory factory) {
        Map<String,List<Converted>> byTenant = new LinkedHashMap<>();
        for (HistoryRow row : rows) { Converted converted = factory.convert(row, receivedAt); if (converted != null) byTenant.computeIfAbsent(converted.tenantId(), ignored -> new ArrayList<>()).add(converted); }
        int inserted = 0;
        for (var entry : byTenant.entrySet()) {
            List<Converted> values = entry.getValue();
            inserted += ingestion.persistPage(entry.getKey(), stream, values.stream().map(Converted::envelope).toList(), values.getLast().cursor());
        }
        return inserted;
    }

    private Converted taskEnvelope(HistoryRow history, OffsetDateTime receivedAt) {
        Map<String,Object> row = history.row(); String processId = string(row.get("processInstanceId")); CaseIdentity identity = identity(processId, string(row.get("businessKey"))); String taskId = string(row.get("id"));
        if (identity == null || taskId == null) return null;
        OffsetDateTime engineAt = timeOr(row.get("endTime"), timeOr(row.get("startTime"), receivedAt));
        UserTaskObservation.EventType event = row.get("endTime") == null ? UserTaskObservation.EventType.CREATED : row.get("deleteReason") == null ? UserTaskObservation.EventType.COMPLETED : UserTaskObservation.EventType.DELETED;
        var metadata = classifier.taskMetadata(string(row.get("processDefinitionId")), string(row.get("taskDefinitionKey")));
        Map<String,Object> attributes = new LinkedHashMap<>(); attributes.put("taskDefinitionKey", string(row.get("taskDefinitionKey"))); attributes.put("activityInstanceId", taskId); attributes.put("name", string(row.get("name"))); attributes.put("assignee", string(row.get("assignee"))); attributes.put("candidateGroups", metadata.candidateGroups()); attributes.put("formKey", metadata.formKey()); attributes.put("priority", intValue(row.get("priority"))); OffsetDateTime due = time(row.get("due")); attributes.put("dueAt", due == null ? null : due.toString());
        return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new UserTaskObservation("remote-task-" + taskId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, taskId, null, event, engineAt.toInstant(), receivedAt.toInstant(), attributes)));
    }

    private Converted activityEnvelope(HistoryRow history, OffsetDateTime receivedAt) {
        Map<String,Object> row = history.row(); String processId = string(row.get("processInstanceId")); CaseIdentity identity = identity(processId, string(row.get("businessKey"))); String activityId = string(row.get("activityId")); String instanceId = firstString(row.get("activityInstanceId"), row.get("id"));
        if (identity == null || activityId == null || instanceId == null || classifier.classify(string(row.get("processDefinitionId")), activityId).isEmpty()) return null;
        OffsetDateTime engineAt = timeOr(row.get("endTime"), timeOr(row.get("startTime"), receivedAt)); ActivityLifecycleObservation.EventType event = Boolean.TRUE.equals(row.get("canceled")) ? ActivityLifecycleObservation.EventType.CANCELLED : row.get("endTime") == null ? ActivityLifecycleObservation.EventType.STARTED : ActivityLifecycleObservation.EventType.COMPLETED;
        return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new ActivityLifecycleObservation("remote-activity-" + instanceId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, instanceId, null, event, engineAt.toInstant(), receivedAt.toInstant(), Map.of("activityId", activityId, "name", string(row.get("activityName"))))));
    }

    private Converted processEnvelope(HistoryRow history, OffsetDateTime receivedAt) {
        Map<String,Object> row = history.row(); String processId = string(row.get("id")); CaseIdentity identity = identity(processId, string(row.get("businessKey"))); if (identity == null || processId == null) return null;
        OffsetDateTime engineAt = timeOr(row.get("endTime"), receivedAt); ProcessObservation.EventType event = row.get("deleteReason") == null ? ProcessObservation.EventType.COMPLETED : ProcessObservation.EventType.TERMINATED;
        return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new ProcessObservation("remote-process-" + processId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, processId, null, event, engineAt.toInstant(), receivedAt.toInstant(), Map.of("processDefinitionKey", string(row.get("processDefinitionKey"))))));
    }

    private CaseIdentity identity(String processId, String businessKey) { String caseId = businessKey == null ? processBusinessKey(processId) : businessKey; if (caseId == null) return null; var c = cases.findById(caseId).orElse(null); return c == null ? null : new CaseIdentity(c.id(), c.tenantId(), c.engineId()); }
    private String processBusinessKey(String processId) { try { Map<String,Object> row = getMap("/history/process-instance/" + processId); return row == null ? null : string(row.get("businessKey")); } catch (RestClientException e) { throw new EngineException("Remote history process lookup failed: " + e.getMessage(), e); } }
    @SuppressWarnings("unchecked") private Map<String,Object> getMap(String path) { try { return client.get().uri(path).retrieve().body(Map.class); } catch (RestClientException e) { throw new EngineException("Remote history lookup failed for " + path + ": " + e.getMessage(), e); } }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> getList(String path) { try { List<Map<String,Object>> rows = client.get().uri(path).retrieve().body(List.class); return rows == null ? List.of() : rows; } catch (RestClientException e) { throw new EngineException("Remote history poll failed for " + path + ": " + e.getMessage(), e); } }
    private static OffsetDateTime timestamp(Map<String,Object> row, String field) { OffsetDateTime value = time(row.get(field)); if (value == null) throw new EngineException("Remote history row has no " + field); return value; }
    private static String requiredId(Map<String,Object> row) { String id = firstString(row.get("id"), row.get("activityInstanceId")); if (id == null) throw new EngineException("Remote history row has no stable id"); return id; }
    private static String encoded(OffsetDateTime value) { return URLEncoder.encode(QUERY_TIME.format(value), StandardCharsets.UTF_8); }
    private static OffsetDateTime time(Object value) { return value == null ? null : RemoteEngineGateway.parseCreatedAt(value); }
    private static OffsetDateTime timeOr(Object first, OffsetDateTime fallback) { OffsetDateTime value = time(first); return value == null ? fallback : value; }
    private static String string(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private static String firstString(Object... values) { for (Object value : values) { String string = string(value); if (string != null) return string; } return null; }
    private static int intValue(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private record HistoryRow(ObservationCursor cursor, Map<String,Object> row) { }
    private record Converted(String tenantId, ObservationCursor cursor, ObservationEnvelope envelope) { }
    private record CaseIdentity(String caseId, String tenantId, String engineId) { }
    @FunctionalInterface private interface EnvelopeFactory { Converted convert(HistoryRow row, OffsetDateTime receivedAt); }
}
