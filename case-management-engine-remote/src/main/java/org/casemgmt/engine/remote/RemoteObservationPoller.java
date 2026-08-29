package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.RemotePollingCheckpointRepository;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.projection.ActivityObservation;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Stock-engine observation using overlapping history windows. Duplicate observations are safe
 * because the projection port upserts by engine identifiers.
 */
public final class RemoteObservationPoller {

    static final String CHECKPOINT = "operaton-history";
    static final Duration OVERLAP = Duration.ofMinutes(2);
    static final Duration INITIAL_LOOKBACK = Duration.ofMinutes(10);
    private static final DateTimeFormatter QUERY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final RestClient client;
    private final CaseProjectionPort projections;
    private final RemotePollingCheckpointRepository checkpoints;
    private final ActiveBpmnCaseRepository activeCases;
    private final RemoteProcessActivityClassifier classifier;

    public RemoteObservationPoller(RestClient client, CaseProjectionPort projections,
                                   RemotePollingCheckpointRepository checkpoints,
                                   ActiveBpmnCaseRepository activeCases,
                                   RemoteProcessActivityClassifier classifier) {
        this.client = client;
        this.projections = projections;
        this.checkpoints = checkpoints;
        this.activeCases = activeCases;
        this.classifier = classifier;
    }

    /** Periodic authoritative pass over every still-active BPMN root process. */
    public int reconcileAllActive() {
        OffsetDateTime observedAt = OffsetDateTime.now(ZoneOffset.UTC);
        int count = 0;
        try {
            for (ActiveBpmnCaseRepository.ActiveCase active : activeCases.findAll()) {
                Map<String, Object> process = getMap(
                        "/history/process-instance/" + active.rootProcessInstanceId());
                if (process != null && process.get("endTime") != null) {
                    OffsetDateTime engineAt = parseTime(process.get("endTime"));
                    projections.observe(new ProcessCompletionObservation(active.caseId(),
                            active.rootProcessInstanceId(),
                            string(process.get("processDefinitionKey")),
                            process.get("deleteReason") == null ? "completed" : "cancelled",
                            engineAt == null ? observedAt : engineAt, observedAt));
                }
                for (Map<String, Object> task : getList("/history/task?processInstanceId="
                        + active.rootProcessInstanceId() + "&maxResults=500")) {
                    observeTask(active.caseId(), task, observedAt);
                    count++;
                }
            }
            checkpoints.succeeded(CHECKPOINT, observedAt);
            return count;
        } catch (RuntimeException e) {
            checkpoints.failed(CHECKPOINT, e.getMessage());
            throw e;
        }
    }

    public int pollOnce() {
        OffsetDateTime pollStartedAt = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime watermark = checkpoints.find(CHECKPOINT)
                .map(RemotePollingCheckpointRepository.Checkpoint::watermark)
                .orElse(pollStartedAt.minus(INITIAL_LOOKBACK));
        OffsetDateTime from = watermark.minus(OVERLAP);
        try {
            int observations = observeTasks(from, pollStartedAt)
                    + observeActivities(from, pollStartedAt)
                    + observeCompletedProcesses(from, pollStartedAt);
            checkpoints.succeeded(CHECKPOINT, pollStartedAt);
            return observations;
        } catch (RuntimeException e) {
            checkpoints.failed(CHECKPOINT, e.getMessage());
            throw e;
        }
    }

    private int observeActivities(OffsetDateTime from, OffsetDateTime observedAt) {
        List<Map<String, Object>> rows = historyRows("/history/activity-instance?startedAfter="
                + encoded(from), "startTime");
        int count = 0;
        for (Map<String, Object> row : rows) {
            String processDefinitionId = string(row.get("processDefinitionId"));
            String activityId = string(row.get("activityId"));
            var classification = classifier.classify(processDefinitionId, activityId);
            if (classification.isEmpty()) continue;
            String processInstanceId = string(row.get("processInstanceId"));
            if (processInstanceId == null) continue;
            String caseId = processBusinessKey(processInstanceId);
            if (caseId == null) continue;
            OffsetDateTime engineAt = parseTime(row.get("endTime"));
            if (engineAt == null) engineAt = parseTime(row.get("startTime"));
            String event = Boolean.TRUE.equals(row.get("canceled")) ? "delete"
                    : row.get("endTime") == null ? "start" : "end";
            String activityInstanceId = firstString(row.get("activityInstanceId"), row.get("id"));
            if (activityInstanceId == null) continue;
            projections.observe(new ActivityObservation(caseId,
                    activityInstanceId, activityId,
                    string(row.get("activityName")), classification.get().kind(),
                    classification.get().milestoneId(), event,
                    engineAt == null ? observedAt : engineAt, observedAt));
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int observeTasks(OffsetDateTime from, OffsetDateTime observedAt) {
        List<Map<String, Object>> rows = historyRows("/history/task?startedAfter=" + encoded(from),
                "startTime");
        int count = 0;
        for (Map<String, Object> row : rows) {
            String processInstanceId = string(row.get("processInstanceId"));
            if (processInstanceId == null) {
                continue;
            }
            String caseId = processBusinessKey(processInstanceId);
            if (caseId == null) {
                continue;
            }
            observeTask(caseId, row, observedAt);
            count++;
        }
        return count;
    }

    private void observeTask(String caseId, Map<String, Object> row, OffsetDateTime observedAt) {
        OffsetDateTime engineAt = parseTime(row.get("endTime"));
        if (engineAt == null) engineAt = parseTime(row.get("startTime"));
        boolean ended = row.get("endTime") != null;
        String event = ended
                ? (row.get("deleteReason") == null ? "complete" : "delete")
                : "create";
        var metadata = classifier.taskMetadata(string(row.get("processDefinitionId")),
                string(row.get("taskDefinitionKey")));
        projections.observe(new TaskObservation(caseId, string(row.get("id")),
                string(row.get("id")), string(row.get("taskDefinitionKey")),
                string(row.get("name")), event, string(row.get("assignee")),
                metadata.candidateGroups(), metadata.formKey(),
                intValue(row.get("priority")), parseTime(row.get("due")),
                engineAt == null ? observedAt : engineAt, observedAt));
    }

    private int observeCompletedProcesses(OffsetDateTime from, OffsetDateTime observedAt) {
        List<Map<String, Object>> rows = historyRows("/history/process-instance?finished=true"
                + "&finishedAfter=" + encoded(from), "endTime");
        int count = 0;
        for (Map<String, Object> row : rows) {
            String caseId = string(row.get("businessKey"));
            String processId = string(row.get("id"));
            if (caseId == null || processId == null) continue;
            OffsetDateTime engineAt = parseTime(row.get("endTime"));
            String endState = row.get("deleteReason") == null ? "completed" : "cancelled";
            projections.observe(new ProcessCompletionObservation(caseId, processId,
                    string(row.get("processDefinitionKey")), endState,
                    engineAt == null ? observedAt : engineAt, observedAt));
            count++;
        }
        return count;
    }

    private String processBusinessKey(String processInstanceId) {
        try {
            Map<String, Object> row = getMap("/history/process-instance/" + processInstanceId);
            return row == null ? null : string(row.get("businessKey"));
        } catch (RestClientException e) {
            throw new EngineException("Remote history process lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Never advance the poll checkpoint after only the engine default's first 500 rows.  Operaton
     * accepts {@code firstResult}/{@code maxResults}; every page is collected before its stream
     * is projected, then locally ordered by the immutable timestamp/id pair for equal-time rows.
     */
    private List<Map<String, Object>> historyRows(String path, String timestampField) {
        return RemoteHistoryPagination.readAll(500, (firstResult, maxResults) -> {
            List<Map<String, Object>> page = getList(path + "&firstResult=" + firstResult
                    + "&maxResults=" + maxResults + "&sortBy=" + timestampField
                    + "&sortOrder=asc");
            return page.stream().map(row -> new RemoteHistoryPagination.Row<>(
                    timestamp(row, timestampField).toInstant(), requiredHistoryId(row), row)).toList();
        }).stream().map(RemoteHistoryPagination.Row::value).toList();
    }

    private static OffsetDateTime timestamp(Map<String, Object> row, String field) {
        OffsetDateTime value = parseTime(row.get(field));
        if (value == null) throw new EngineException("Remote history row has no " + field);
        return value;
    }

    private static String requiredHistoryId(Map<String, Object> row) {
        String id = firstString(row.get("id"), row.get("activityInstanceId"));
        if (id == null) throw new EngineException("Remote history row has no stable id");
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String path) {
        try {
            return client.get().uri(path).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new EngineException("Remote history lookup failed for " + path + ": "
                    + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        try {
            List<Map<String, Object>> rows = client.get().uri(path).retrieve().body(List.class);
            return rows == null ? List.of() : rows;
        } catch (RestClientException e) {
            throw new EngineException("Remote history poll failed for " + path + ": "
                    + e.getMessage(), e);
        }
    }

    private static String encoded(OffsetDateTime value) {
        return URLEncoder.encode(QUERY_TIME.format(value), StandardCharsets.UTF_8);
    }

    private static OffsetDateTime parseTime(Object value) {
        return value == null ? null : RemoteEngineGateway.parseCreatedAt(value);
    }

    private static String string(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String firstString(Object... values) {
        for (Object value : values) {
            String text = string(value);
            if (text != null) return text;
        }
        return null;
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
