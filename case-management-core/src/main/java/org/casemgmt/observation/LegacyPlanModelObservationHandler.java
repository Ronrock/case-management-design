package org.casemgmt.observation;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.projection.ActivityObservation;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.TaskObservation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Preserves the pre-WS3 PLAN_MODEL projection surface without canonical BPMN effects. */
public final class LegacyPlanModelObservationHandler {

    private final CaseProjectionPort projections;
    private final EventPublisher events;

    public LegacyPlanModelObservationHandler(CaseProjectionPort projections) {
        this(projections, null);
    }

    public LegacyPlanModelObservationHandler(
            CaseProjectionPort projections, EventPublisher events) {
        this.projections = Objects.requireNonNull(projections, "projections");
        this.events = events;
    }

    public void apply(EngineObservation observation) {
        if (observation instanceof UserTaskObservation task) {
            projections.observe(taskProjection(task));
            return;
        }
        if (observation instanceof ActivityLifecycleObservation activity) {
            projections.observe(activityProjection(activity));
            return;
        }
        if (observation instanceof MilestoneObservation milestone) {
            projections.observe(milestoneProjection(milestone));
            return;
        }
        ProcessObservation process = (ProcessObservation) observation;
        if (process.eventType() == ProcessObservation.EventType.STARTED) {
            publishSynchronousStart(process);
            return;
        }
        if (process.eventType() == ProcessObservation.EventType.COMPLETED
                || process.eventType() == ProcessObservation.EventType.TERMINATED) {
            projections.observe(new ProcessCompletionObservation(process.caseId(),
                    process.processInstanceId(), string(process, "processDefinitionKey"),
                    process.eventType() == ProcessObservation.EventType.TERMINATED
                            ? "cancelled" : "completed",
                    at(process.engineOccurredAt()), at(process.receivedAt())));
        }
    }

    private void publishSynchronousStart(ProcessObservation process) {
        // Operaton also publishes a history start snapshot. The synchronous execution callback
        // has no revision and is the single compatibility event owner.
        if (events == null || process.entityRevision() != null) return;
        Map<String, Object> details = Map.of(
                "processDefinitionKey", string(process, "processDefinitionKey"),
                "processInstanceId", process.processInstanceId());
        events.publish(new CaseEvent(CaseIds.newId(), process.source(),
                EventTypes.PROCESS_STARTED, process.caseId(), process.tenantId(),
                at(process.engineOccurredAt()), details));
        events.audit(process.caseId(), process.tenantId(), "engine", "engine.process.started",
                "Process", process.entityId(), null, details);
    }

    private static TaskObservation taskProjection(UserTaskObservation task) {
        String activityInstanceId = string(task, "activityInstanceId");
        return new TaskObservation(task.caseId(), task.processInstanceId(), task.entityId(),
                activityInstanceId == null ? task.entityId() : activityInstanceId,
                string(task, "taskDefinitionKey"), string(task, "name"),
                switch (task.eventType()) {
                    case CREATED -> "create";
                    case CLAIMED -> "claim";
                    case UNCLAIMED -> "unclaim";
                    case ASSIGNED -> "assignment";
                    case COMPLETED -> "complete";
                    case DELETED -> "delete";
                },
                string(task, "assignee"), strings(task, "candidateGroups"),
                string(task, "formKey"), integer(task, "priority"),
                date(task, "dueAt"), at(task.engineOccurredAt()), at(task.receivedAt()));
    }

    private static ActivityObservation activityProjection(
            ActivityLifecycleObservation activity) {
        return new ActivityObservation(activity.caseId(), activity.processInstanceId(),
                activity.entityId(), string(activity, "activityId"), string(activity, "name"),
                ActivityObservation.Kind.STAGE, null,
                switch (activity.eventType()) {
                    case STARTED -> "start";
                    case COMPLETED -> "end";
                    case CANCELLED -> "delete";
                }, at(activity.engineOccurredAt()), at(activity.receivedAt()));
    }

    private static ActivityObservation milestoneProjection(MilestoneObservation milestone) {
        return new ActivityObservation(milestone.caseId(), milestone.processInstanceId(),
                milestone.entityId(), string(milestone, "activityId"),
                string(milestone, "name"), ActivityObservation.Kind.MILESTONE,
                string(milestone, "milestoneId"),
                switch (milestone.eventType()) {
                    case REACHED -> "end";
                    case REOPENED -> "start";
                    case CANCELLED -> "delete";
                }, at(milestone.engineOccurredAt()), at(milestone.receivedAt()));
    }

    private static String string(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static List<String> strings(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static int integer(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        return value instanceof BigDecimal number ? number.intValue() : 0;
    }

    private static OffsetDateTime date(EngineObservation observation, String name) {
        String value = string(observation, name);
        return value == null ? null : OffsetDateTime.parse(value);
    }

    private static OffsetDateTime at(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
