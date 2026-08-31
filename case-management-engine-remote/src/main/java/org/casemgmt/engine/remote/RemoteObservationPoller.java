package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.MilestoneObservation;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
        for (ActiveBpmnCaseRepository.ReconciliationProcess process :
                activeCases.findAllProcessesForActiveCases()) {
            count += reconcileProcess(process, receivedAt);
        }
        inboxWorker.drainUntilIdle(); return count;
    }

    private int reconcileProcess(ActiveBpmnCaseRepository.ReconciliationProcess process,
                                 OffsetDateTime receivedAt) {
        CaseIdentity identity = new CaseIdentity(process.caseId(), process.tenantId(),
                process.engineId());
        int inserted = reconcilePages(ObservationStream.TASKS, process, receivedAt,
                this::taskEnvelope, converted -> converted.envelope().observation()
                        instanceof UserTaskObservation task
                        && task.eventType() != UserTaskObservation.EventType.CREATED
                        ? ObservationStream.TASK_TERMINALS : ObservationStream.TASKS);
        inserted += reconcilePages(ObservationStream.ACTIVITIES, process, receivedAt,
                this::activityEnvelope, converted -> converted.envelope().observation()
                        instanceof ActivityLifecycleObservation activity
                        && activity.eventType() != ActivityLifecycleObservation.EventType.STARTED
                        ? ObservationStream.ACTIVITY_TERMINALS
                        : converted.envelope().observation() instanceof MilestoneObservation milestone
                        && milestone.eventType() != MilestoneObservation.EventType.REOPENED
                        ? ObservationStream.ACTIVITY_TERMINALS : ObservationStream.ACTIVITIES);
        Map<String, Object> current = getMap("/history/process-instance/" + process.processInstanceId());
        if (current != null && current.get("endTime") != null) {
            Converted converted = processEnvelope(historyRow(current, receivedAt), receivedAt, identity);
            if (converted != null) inserted += ingestion.persist(identity.tenantId(),
                    ObservationStream.PROCESSES, List.of(converted.envelope()));
        }
        return inserted;
    }

    private int reconcilePages(ObservationStream sourceStream,
                               ActiveBpmnCaseRepository.ReconciliationProcess process,
                               OffsetDateTime receivedAt, EnvelopeFactory factory,
                               StreamSelector streamSelector) {
        int inserted = 0;
        for (int first = 0; ; first += PAGE_SIZE) {
            String path = sourceStream == ObservationStream.TASKS ? "/history/task" :
                    "/history/activity-instance";
            String sortBy = sourceStream == ObservationStream.TASKS ? "taskId" :
                    "activityInstanceId";
            List<Map<String, Object>> page = getList(path + "?processInstanceId="
                    + encoded(process.processInstanceId()) + "&firstResult=" + first + "&maxResults="
                    + PAGE_SIZE + "&sortBy=" + sortBy + "&sortOrder=asc");
            Map<ObservationStream, List<ObservationEnvelope>> envelopes = new LinkedHashMap<>();
            CaseIdentity identity = new CaseIdentity(process.caseId(), process.tenantId(),
                    process.engineId());
            for (Map<String, Object> row : page) {
                Map<String, Object> history = withProcessDefinition(row,
                        process.processDefinitionId());
                Converted converted = factory.convert(historyRow(history, receivedAt), receivedAt,
                        identity);
                if (converted != null) envelopes.computeIfAbsent(streamSelector.stream(converted),
                        ignored -> new ArrayList<>()).add(converted.envelope());
            }
            for (var entry : envelopes.entrySet()) {
                inserted += ingestion.persist(process.tenantId(), entry.getKey(), entry.getValue());
            }
            if (page.size() < PAGE_SIZE) return inserted;
        }
    }

    public int pollOnce() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int count = pollTasks(now) + pollTaskTerminals(now) + pollActivities(now)
                + pollActivityTerminals(now) + pollProcesses(now);
        inboxWorker.drainOnce(); return count;
    }

    private int pollTasks(OffsetDateTime receivedAt) {
        return pollPages(ObservationStream.TASKS, "/history/task?startedAfter=", "startedBefore",
                "startTime", "taskId", receivedAt, this::taskEnvelope);
    }
    private int pollTaskTerminals(OffsetDateTime receivedAt) {
        return pollPages(ObservationStream.TASK_TERMINALS, "/history/task?finishedAfter=", "finishedBefore",
                "endTime", "taskId", receivedAt, this::taskEnvelope);
    }
    private int pollActivities(OffsetDateTime receivedAt) {
        return pollPages(ObservationStream.ACTIVITIES, "/history/activity-instance?startedAfter=", "startedBefore",
                "startTime", "activityInstanceId", receivedAt, this::activityEnvelope);
    }
    private int pollActivityTerminals(OffsetDateTime receivedAt) {
        return pollPages(ObservationStream.ACTIVITY_TERMINALS, "/history/activity-instance?finishedAfter=", "finishedBefore",
                "endTime", "activityInstanceId", receivedAt, this::activityEnvelope);
    }
    private int pollProcesses(OffsetDateTime receivedAt) {
        return pollPages(ObservationStream.PROCESSES, "/history/process-instance?finished=true&finishedAfter=", "finishedBefore",
                "endTime", "instanceId", receivedAt, this::processEnvelope);
    }

    /** A page's rows are pair-ordered and durably inserted before any matching tenant cursor moves. */
    private int pollPages(ObservationStream stream, String path, String untilParameter,
                          String timestampField, String stableIdentitySort,
                          OffsetDateTime receivedAt, EnvelopeFactory factory) {
        // A cursor is persisted per tenant/feed. Querying a conservative overlap ensures a
        // restarted process may safely replay page boundaries; fingerprints deduplicate the replay.
        OffsetDateTime from = ingestion.oldestCursor(stream)
                .map(ObservationCursor::timestamp)
                .map(at -> OffsetDateTime.ofInstant(at, ZoneOffset.UTC).minus(OVERLAP))
                .orElse(receivedAt.minus(INITIAL_LOOKBACK)); int inserted = 0;
        Map<String, ObservationCursor> windowCursors = new HashMap<>();
        for (int first = 0; ; first += PAGE_SIZE) {
            // Operaton accepts one sort field, so a pair cursor cannot be expressed remotely.
            // Bound the poll to a fixed time window and page by the endpoint's documented stable
            // identity key.  The set cannot move while this pass is in progress; subsequent
            // overlap polling redelivers its boundary and the durable fingerprint makes that
            // replay idempotent.
            List<Map<String,Object>> page = getList(path + encoded(from) + "&" + untilParameter
                    + "=" + encoded(receivedAt) + "&firstResult=" + first + "&maxResults=" + PAGE_SIZE
                    + "&sortBy=" + stableIdentitySort + "&sortOrder=asc");
            List<HistoryRow> ordered = page.stream().map(row -> new HistoryRow(new ObservationCursor(timestamp(row, timestampField).toInstant(), requiredId(row)), row)).sorted(Comparator.comparing(HistoryRow::cursor)).toList();
            inserted += persistPage(stream, ordered, receivedAt, factory, windowCursors);
            if (page.size() < PAGE_SIZE) {
                windowCursors.forEach((tenantId, cursor) ->
                        ingestion.advanceCompletedWindow(tenantId, stream, cursor));
                return inserted;
            }
        }
    }

    private int persistPage(ObservationStream stream, List<HistoryRow> rows, OffsetDateTime receivedAt,
                            EnvelopeFactory factory, Map<String, ObservationCursor> windowCursors) {
        Map<String,List<Converted>> byTenant = new LinkedHashMap<>();
        for (HistoryRow row : rows) {
            String processId = firstString(row.row().get("processInstanceId"), row.row().get("id"));
            Converted converted = factory.convert(row, receivedAt,
                    identity(processId, string(row.row().get("businessKey"))));
            if (converted != null) byTenant.computeIfAbsent(converted.tenantId(),
                    ignored -> new ArrayList<>()).add(converted);
        }
        int inserted = 0;
        for (var entry : byTenant.entrySet()) {
            List<Converted> values = entry.getValue();
            inserted += ingestion.persistPage(entry.getKey(), stream, values.stream().map(Converted::envelope).toList());
            values.stream().map(Converted::cursor).max(Comparator.naturalOrder()).ifPresent(cursor ->
                    windowCursors.merge(entry.getKey(), cursor,
                            (current, candidate) -> current.compareTo(candidate) >= 0 ? current : candidate));
        }
        return inserted;
    }

    private Converted taskEnvelope(HistoryRow history, OffsetDateTime receivedAt, CaseIdentity identity) {
        Map<String,Object> row = history.row(); String processId = string(row.get("processInstanceId")); String taskId = string(row.get("id"));
        if (identity == null || taskId == null) return null;
        OffsetDateTime engineAt = timeOr(row.get("endTime"), timeOr(row.get("startTime"), receivedAt));
        UserTaskObservation.EventType event = taskEvent(row);
        var metadata = classifier.taskMetadata(string(row.get("processDefinitionId")), string(row.get("taskDefinitionKey")));
        Map<String,Object> attributes = new LinkedHashMap<>(); attributes.put("taskDefinitionKey", string(row.get("taskDefinitionKey"))); attributes.put("activityInstanceId", taskId); attributes.put("name", string(row.get("name"))); attributes.put("assignee", string(row.get("assignee"))); attributes.put("candidateGroups", metadata.candidateGroups()); attributes.put("formKey", metadata.formKey()); attributes.put("slaTargetId", metadata.slaTargetId()); attributes.put("priority", intValue(row.get("priority"))); OffsetDateTime due = time(row.get("due")); attributes.put("dueAt", due == null ? null : due.toString());
        return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new UserTaskObservation("remote-task-" + taskId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, taskId, null, event, engineAt.toInstant(), receivedAt.toInstant(), attributes)));
    }

    private Converted activityEnvelope(HistoryRow history, OffsetDateTime receivedAt, CaseIdentity identity) {
        Map<String,Object> row = history.row(); String processId = string(row.get("processInstanceId")); String activityId = string(row.get("activityId")); String instanceId = firstString(row.get("activityInstanceId"), row.get("id"));
        var classification = classifier.classify(string(row.get("processDefinitionId")), activityId);
        if (identity == null || activityId == null || instanceId == null || classification.isEmpty()) return null;
        OffsetDateTime engineAt = timeOr(row.get("endTime"), timeOr(row.get("startTime"), receivedAt));
        Map<String, Object> attributes = new LinkedHashMap<>(); attributes.put("activityId", activityId); attributes.put("name", string(row.get("activityName")));
        var value = classification.orElseThrow();
        attributes.put("slaTargetId", value.slaTargetId());
        if (value.kind() == org.casemgmt.projection.ActivityObservation.Kind.MILESTONE) {
            if (!Boolean.TRUE.equals(row.get("canceled")) && row.get("endTime") == null) return null;
            MilestoneObservation.EventType event = Boolean.TRUE.equals(row.get("canceled"))
                    ? MilestoneObservation.EventType.CANCELLED : MilestoneObservation.EventType.REACHED;
            attributes.put("milestoneId", value.milestoneId());
            return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new MilestoneObservation(
                    "remote-milestone-" + instanceId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, instanceId, null, event, engineAt.toInstant(), receivedAt.toInstant(), attributes)));
        }
        ActivityLifecycleObservation.EventType event = Boolean.TRUE.equals(row.get("canceled")) ? ActivityLifecycleObservation.EventType.CANCELLED : row.get("endTime") == null ? ActivityLifecycleObservation.EventType.STARTED : ActivityLifecycleObservation.EventType.COMPLETED;
        return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new ActivityLifecycleObservation("remote-activity-" + instanceId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, instanceId, null, event, engineAt.toInstant(), receivedAt.toInstant(), attributes)));
    }

    private Converted processEnvelope(HistoryRow history, OffsetDateTime receivedAt, CaseIdentity identity) {
        Map<String,Object> row = history.row(); String processId = string(row.get("id")); if (identity == null || processId == null) return null;
        OffsetDateTime engineAt = timeOr(row.get("endTime"), receivedAt); ProcessObservation.EventType event = row.get("deleteReason") == null ? ProcessObservation.EventType.COMPLETED : ProcessObservation.EventType.TERMINATED;
        return new Converted(identity.tenantId(), history.cursor(), new ObservationEnvelope(new ProcessObservation("remote-process-" + processId + "-" + event, 1, "remote-history", identity.engineId(), identity.tenantId(), identity.caseId(), processId, processId, null, event, engineAt.toInstant(), receivedAt.toInstant(), Map.of("processDefinitionKey", string(row.get("processDefinitionKey"))))));
    }

    private CaseIdentity identity(String processId, String businessKey) { String caseId = businessKey == null ? processBusinessKey(processId) : businessKey; if (caseId == null) return null; var c = cases.findById(caseId).orElse(null); return c == null ? null : new CaseIdentity(c.id(), c.tenantId(), c.engineId()); }
    private String processBusinessKey(String processId) { try { Map<String,Object> row = getMap("/history/process-instance/" + processId); return row == null ? null : string(row.get("businessKey")); } catch (RestClientException e) { throw new EngineException("Remote history process lookup failed: " + e.getMessage(), e); } }
    @SuppressWarnings("unchecked") private Map<String,Object> getMap(String path) { try { return client.get().uri(path).retrieve().body(Map.class); } catch (RestClientException e) { throw new EngineException("Remote history lookup failed for " + path + ": " + e.getMessage(), e); } }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> getList(String path) { try { List<Map<String,Object>> rows = client.get().uri(URI.create(path)).retrieve().body(List.class); return rows == null ? List.of() : rows; } catch (RestClientException e) { throw new EngineException("Remote history poll failed for " + path + ": " + e.getMessage(), e); } }
    private static OffsetDateTime timestamp(Map<String,Object> row, String field) { OffsetDateTime value = time(row.get(field)); if (value == null) throw new EngineException("Remote history row has no " + field); return value; }
    private static String requiredId(Map<String,Object> row) { String id = firstString(row.get("id"), row.get("activityInstanceId")); if (id == null) throw new EngineException("Remote history row has no stable id"); return id; }
    private static HistoryRow historyRow(Map<String, Object> row, OffsetDateTime receivedAt) {
        OffsetDateTime occurredAt = timeOr(row.get("endTime"), timeOr(row.get("startTime"), receivedAt));
        return new HistoryRow(new ObservationCursor(occurredAt.toInstant(), requiredId(row)), row);
    }
    private static Map<String, Object> withProcessDefinition(Map<String, Object> row,
                                                              String processDefinitionId) {
        if (string(row.get("processDefinitionId")) != null || processDefinitionId == null) {
            return row;
        }
        Map<String, Object> value = new LinkedHashMap<>(row);
        value.put("processDefinitionId", processDefinitionId);
        return value;
    }
    /** Query values are encoded exactly once before being supplied as an already-built URI. */
    private static String encoded(OffsetDateTime value) {
        return URLEncoder.encode(QUERY_TIME.format(value), StandardCharsets.UTF_8);
    }
    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static OffsetDateTime time(Object value) { return value == null ? null : RemoteEngineGateway.parseCreatedAt(value); }
    private static OffsetDateTime timeOr(Object first, OffsetDateTime fallback) { OffsetDateTime value = time(first); return value == null ? fallback : value; }
    private static String string(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private static String firstString(Object... values) { for (Object value : values) { String string = string(value); if (string != null) return string; } return null; }
    private static int intValue(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static UserTaskObservation.EventType taskEvent(Map<String, Object> row) {
        if (row.get("endTime") == null) return UserTaskObservation.EventType.CREATED;
        String reason = string(row.get("deleteReason"));
        if ("completed".equals(reason)) return UserTaskObservation.EventType.COMPLETED;
        if (reason == null) throw new EngineException("Finished remote task history row has no deleteReason");
        return UserTaskObservation.EventType.DELETED;
    }
    private record HistoryRow(ObservationCursor cursor, Map<String,Object> row) { }
    private record Converted(String tenantId, ObservationCursor cursor, ObservationEnvelope envelope) { }
    private record CaseIdentity(String caseId, String tenantId, String engineId) { }
    @FunctionalInterface private interface EnvelopeFactory {
        Converted convert(HistoryRow row, OffsetDateTime receivedAt, CaseIdentity identity);
    }
    @FunctionalInterface private interface StreamSelector { ObservationStream stream(Converted converted); }
}
