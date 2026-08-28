package org.casemgmt.engine.embedded;

import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.EngineObservationHandler;
import org.casemgmt.observation.MilestoneObservation;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.casemgmt.projection.ActivityObservation;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.spring.boot.starter.event.ExecutionEvent;
import org.operaton.bpm.spring.boot.starter.event.TaskEvent;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Translates synchronous Operaton callbacks into the canonical engine-observation contract. */
public final class EmbeddedEngineEventBridge {

    public static final String SOURCE = "operaton:embedded";

    private final EngineObservationHandler observations;
    private final ProcessCaseCorrelation correlation;
    private final ProcessActivityClassifier classifier;
    private final RepositoryService repository;
    private final TaskService tasks;
    private final String engineId;
    private final Clock clock;

    public EmbeddedEngineEventBridge(
            EngineObservationHandler observations,
            ProcessCaseCorrelation correlation,
            ProcessActivityClassifier classifier,
            RepositoryService repository,
            TaskService tasks,
            String engineId) {
        this(observations, correlation, classifier, repository, tasks, engineId,
                Clock.systemUTC());
    }

    EmbeddedEngineEventBridge(
            EngineObservationHandler observations,
            ProcessCaseCorrelation correlation,
            ProcessActivityClassifier classifier,
            RepositoryService repository,
            TaskService tasks,
            String engineId,
            Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.correlation = Objects.requireNonNull(correlation, "correlation");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId must not be blank");
        }
        this.engineId = engineId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The built-in Operaton Spring plugin publishes this event synchronously from the command
     * context. The common handler therefore joins the same transaction; any persistence failure
     * propagates and rolls the engine command back.
     */
    @EventListener
    public void onTask(TaskEvent event) {
        UserTaskObservation.EventType eventType = taskEvent(event.getEventName(),
                event.getAssignee());
        if (eventType == null || event.getProcessInstanceId() == null || event.getId() == null) {
            return;
        }
        String caseId = correlation.caseId(
                event.getProcessInstanceId(), event.getProcessDefinitionId());
        if (caseId == null) {
            return;
        }
        Instant receivedAt = clock.instant();
        Date engineDate = event.getLastUpdated() != null
                ? event.getLastUpdated() : event.getCreateTime();
        Instant occurredAt = engineDate == null ? receivedAt : engineDate.toInstant();
        String definitionKey = processDefinitionKey(event.getProcessDefinitionId());
        ProcessActivityClassifier.TaskMetadata metadata = classifier.taskMetadata(
                event.getProcessDefinitionId(), event.getTaskDefinitionKey());

        Map<String, Object> attributes = authorityAttributes(
                event.getProcessDefinitionId(), definitionKey);
        put(attributes, "taskDefinitionKey", event.getTaskDefinitionKey());
        // One execution commonly visits several sequential user tasks. The task id is the stable
        // occurrence key; using executionId collapses them into one projected plan item.
        attributes.put("activityInstanceId", event.getId());
        put(attributes, "name", event.getName());
        put(attributes, "assignee", event.getAssignee());
        attributes.put("candidateGroups", metadata.candidateGroups());
        put(attributes, "formKey", metadata.formKey());
        attributes.put("priority", event.getPriority());
        if (event.getDueDate() != null) {
            attributes.put("dueAt", at(event.getDueDate()).toString());
        }
        if (eventType == UserTaskObservation.EventType.COMPLETED) {
            attributes.put("variables", new LinkedHashMap<>(tasks.getVariables(event.getId())));
        }

        observations.apply(new UserTaskObservation(observationId(), 1, SOURCE, engineId,
                event.getTenantId(), caseId, event.getProcessInstanceId(), event.getId(), null,
                eventType, occurredAt, receivedAt, attributes));
    }

    /** Emits lifecycle for explicitly classified stages and milestones only. */
    @EventListener
    public void onExecution(ExecutionEvent event) {
        if (isRootProcessStart(event)) {
            emitProcessStarted(event);
            return;
        }
        if (event.getProcessInstanceId() == null || event.getActivityInstanceId() == null) {
            return;
        }
        String caseId = correlation.caseId(
                event.getProcessInstanceId(), event.getProcessDefinitionId());
        if (caseId == null) {
            return;
        }
        var classification = classifier.classify(
                event.getProcessDefinitionId(), event.getCurrentActivityId());
        if (classification.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        String definitionKey = processDefinitionKey(event.getProcessDefinitionId());
        Map<String, Object> attributes = authorityAttributes(
                event.getProcessDefinitionId(), definitionKey);
        put(attributes, "activityId", event.getCurrentActivityId());
        put(attributes, "name", event.getCurrentActivityName());

        ProcessActivityClassifier.Classification value = classification.orElseThrow();
        if (value.kind() == ActivityObservation.Kind.MILESTONE) {
            MilestoneObservation.EventType type = milestoneEvent(event.getEventName());
            if (type == null) {
                return;
            }
            put(attributes, "milestoneId", value.milestoneId());
            observations.apply(new MilestoneObservation(observationId(), 1, SOURCE, engineId,
                    event.getTenantId(), caseId, event.getProcessInstanceId(),
                    event.getActivityInstanceId(), null, type, now, now, attributes));
            return;
        }
        ActivityLifecycleObservation.EventType type = activityEvent(event.getEventName());
        if (type == null) {
            return;
        }
        observations.apply(new ActivityLifecycleObservation(observationId(), 1, SOURCE, engineId,
                event.getTenantId(), caseId, event.getProcessInstanceId(),
                event.getActivityInstanceId(), null, type, now, now, attributes));
    }

    private void emitProcessStarted(ExecutionEvent event) {
        String caseId = correlation.caseId(
                event.getProcessInstanceId(), event.getProcessDefinitionId());
        if (caseId == null) {
            return;
        }
        Instant now = clock.instant();
        Map<String, Object> attributes = authorityAttributes(
                event.getProcessDefinitionId(),
                processDefinitionKey(event.getProcessDefinitionId()));
        observations.apply(new ProcessObservation(observationId(), 1, SOURCE, engineId,
                event.getTenantId(), caseId, event.getProcessInstanceId(),
                event.getProcessInstanceId(), null, ProcessObservation.EventType.STARTED,
                now, now, attributes));
    }

    private static boolean isRootProcessStart(ExecutionEvent event) {
        return "start".equals(event.getEventName())
                && event.getActivityInstanceId() == null
                && event.getProcessInstanceId() != null
                && event.getProcessInstanceId().equals(event.getId());
    }

    /** Process history provides the engine's stable sequence and exact terminal timestamp. */
    @EventListener
    public void onHistory(HistoryEvent event) {
        if (event instanceof HistoricActivityInstanceEventEntity activity
                && activity.getEndTime() != null && activity.isCanceled()) {
            onCancelledActivity(activity);
            return;
        }
        if (!(event instanceof HistoricProcessInstanceEventEntity process)
                || process.getProcessInstanceId() == null) {
            return;
        }
        String caseId = correlation.caseId(
                process.getProcessInstanceId(), process.getProcessDefinitionId());
        if (caseId == null) {
            return;
        }
        Instant receivedAt = clock.instant();
        Map<String, Object> attributes = authorityAttributes(
                process.getProcessDefinitionId(), process.getProcessDefinitionKey());
        ProcessObservation.EventType type;
        Date engineDate;
        if (process.getEndTime() != null) {
            type = process.getDeleteReason() == null
                    ? ProcessObservation.EventType.COMPLETED
                    : ProcessObservation.EventType.TERMINATED;
            engineDate = process.getEndTime();
        } else if (HistoryEvent.ACTIVITY_EVENT_TYPE_START.equals(process.getEventType())
                && process.getStartTime() != null) {
            type = ProcessObservation.EventType.STARTED;
            engineDate = process.getStartTime();
        } else {
            // Historic process entities are updated and republished throughout execution. Their
            // original start time remains populated, so treating every non-terminal snapshot as
            // STARTED creates a fresh revision/fingerprint for the same semantic fact. Only the
            // history producer's explicit start event is canonical evidence.
            return;
        }
        observations.apply(new ProcessObservation(observationId(), 1, SOURCE, engineId,
                process.getTenantId(), caseId, process.getProcessInstanceId(),
                process.getProcessInstanceId(), stableRevision(process.getSequenceCounter()), type,
                engineDate.toInstant(), receivedAt, attributes));
    }

    private void onCancelledActivity(HistoricActivityInstanceEventEntity activity) {
        if (activity.getProcessInstanceId() == null || activity.getActivityInstanceId() == null) {
            return;
        }
        String caseId = correlation.caseId(
                activity.getProcessInstanceId(), activity.getProcessDefinitionId());
        if (caseId == null) {
            return;
        }
        var classification = classifier.classify(
                activity.getProcessDefinitionId(), activity.getActivityId());
        if (classification.isEmpty()) {
            return;
        }
        Instant receivedAt = clock.instant();
        Map<String, Object> attributes = authorityAttributes(
                activity.getProcessDefinitionId(), activity.getProcessDefinitionKey());
        put(attributes, "activityId", activity.getActivityId());
        put(attributes, "name", activity.getActivityName());
        var value = classification.orElseThrow();
        if (value.kind() == ActivityObservation.Kind.MILESTONE) {
            put(attributes, "milestoneId", value.milestoneId());
            observations.apply(new MilestoneObservation(observationId(), 1, SOURCE, engineId,
                    activity.getTenantId(), caseId, activity.getProcessInstanceId(),
                    activity.getActivityInstanceId(), stableRevision(activity.getSequenceCounter()),
                    MilestoneObservation.EventType.CANCELLED,
                    activity.getEndTime().toInstant(), receivedAt, attributes));
            return;
        }
        observations.apply(new ActivityLifecycleObservation(observationId(), 1, SOURCE, engineId,
                activity.getTenantId(), caseId, activity.getProcessInstanceId(),
                activity.getActivityInstanceId(), stableRevision(activity.getSequenceCounter()),
                ActivityLifecycleObservation.EventType.CANCELLED,
                activity.getEndTime().toInstant(), receivedAt, attributes));
    }

    private String processDefinitionKey(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new IllegalArgumentException("processDefinitionId must not be blank");
        }
        var definition = repository.getProcessDefinition(processDefinitionId);
        if (definition == null || definition.getKey() == null || definition.getKey().isBlank()) {
            throw new IllegalStateException(
                    "No process definition key for exact definition " + processDefinitionId);
        }
        return definition.getKey();
    }

    private static Map<String, Object> authorityAttributes(
            String processDefinitionId, String processDefinitionKey) {
        if (processDefinitionId == null || processDefinitionId.isBlank()
                || processDefinitionKey == null || processDefinitionKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Exact process definition id and key are required");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("processDefinitionId", processDefinitionId);
        attributes.put("processDefinitionKey", processDefinitionKey);
        return attributes;
    }

    private static UserTaskObservation.EventType taskEvent(String eventName, String assignee) {
        if (eventName == null) {
            return null;
        }
        return switch (eventName) {
            case "create" -> UserTaskObservation.EventType.CREATED;
            case "assignment" -> assignee == null
                    ? UserTaskObservation.EventType.UNCLAIMED
                    : UserTaskObservation.EventType.CLAIMED;
            // Operaton emits an undirected update immediately before assignment. Translating it
            // would race the semantic assignment event at the same timestamp and make CLAIMED or
            // UNCLAIMED appear stale, so only the directional callback is canonical evidence.
            case "update" -> null;
            case "complete" -> UserTaskObservation.EventType.COMPLETED;
            case "delete" -> UserTaskObservation.EventType.DELETED;
            default -> null;
        };
    }

    private static ActivityLifecycleObservation.EventType activityEvent(String eventName) {
        if ("start".equals(eventName)) {
            return ActivityLifecycleObservation.EventType.STARTED;
        }
        if ("end".equals(eventName)) {
            return ActivityLifecycleObservation.EventType.COMPLETED;
        }
        return null;
    }

    private static MilestoneObservation.EventType milestoneEvent(String eventName) {
        if ("end".equals(eventName)) {
            return MilestoneObservation.EventType.REACHED;
        }
        return null;
    }

    private static Long stableRevision(long sequenceCounter) {
        return sequenceCounter > 0 ? sequenceCounter : null;
    }

    private static void put(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private static String observationId() {
        return UUID.randomUUID().toString();
    }

    private static java.time.OffsetDateTime at(Date date) {
        return date.toInstant().atOffset(ZoneOffset.UTC);
    }
}
