package org.casemgmt.observation;

import org.casemgmt.projection.ActivityObservation;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class LegacyPlanModelObservationHandlerTest {

    private static final Instant ENGINE_AT = Instant.parse("2026-08-28T10:15:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-28T10:15:30Z");

    @Test
    void routesTheFormerTaskActivityMilestoneAndTerminalProcessProjectionSurface() {
        CaseProjectionPort projections = mock(CaseProjectionPort.class);
        var handler = new LegacyPlanModelObservationHandler(projections);

        handler.apply(new UserTaskObservation("task-observation", 1, "operaton:embedded",
                "engine-a", "tenant-a", "case-1", "process-1", "task-1", null,
                UserTaskObservation.EventType.COMPLETED, ENGINE_AT, RECEIVED_AT,
                Map.of("taskDefinitionKey", "review", "activityInstanceId", "activity-1",
                        "name", "Review", "assignee", "alice",
                        "candidateGroups", List.of("handlers"), "priority", 50)));
        handler.apply(new ActivityLifecycleObservation("stage-observation", 1,
                "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                "stage-instance", null, ActivityLifecycleObservation.EventType.CANCELLED,
                ENGINE_AT, RECEIVED_AT, Map.of("activityId", "assessment", "name", "Assessment")));
        handler.apply(new MilestoneObservation("milestone-observation", 1,
                "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                "milestone-instance", null, MilestoneObservation.EventType.REACHED,
                ENGINE_AT, RECEIVED_AT, Map.of("activityId", "approved", "name", "Approved",
                        "milestoneId", "approved")));
        handler.apply(new ProcessObservation("process-observation", 1, "operaton:embedded",
                "engine-a", "tenant-a", "case-1", "process-1", "process-1", 42L,
                ProcessObservation.EventType.COMPLETED, ENGINE_AT, RECEIVED_AT,
                Map.of("processDefinitionKey", "legacy-process")));

        ArgumentCaptor<TaskObservation> task = ArgumentCaptor.forClass(TaskObservation.class);
        verify(projections).observe(task.capture());
        assertThat(task.getValue().eventName()).isEqualTo("complete");
        assertThat(task.getValue().processInstanceId()).isEqualTo("process-1");

        ArgumentCaptor<ActivityObservation> activity =
                ArgumentCaptor.forClass(ActivityObservation.class);
        verify(projections, org.mockito.Mockito.times(2)).observe(activity.capture());
        assertThat(activity.getAllValues())
                .extracting(ActivityObservation::eventName)
                .containsExactly("delete", "end");
        assertThat(activity.getAllValues())
                .extracting(ActivityObservation::kind)
                .containsExactly(ActivityObservation.Kind.STAGE,
                        ActivityObservation.Kind.MILESTONE);

        ArgumentCaptor<ProcessCompletionObservation> process =
                ArgumentCaptor.forClass(ProcessCompletionObservation.class);
        verify(projections).observe(process.capture());
        assertThat(process.getValue().endState()).isEqualTo("completed");
        assertThat(process.getValue().processDefinitionKey()).isEqualTo("legacy-process");
    }

    @Test
    void publishesOneStartForTheSynchronousFactAndIgnoresTheHistoryDuplicate() {
        CaseProjectionPort projections = mock(CaseProjectionPort.class);
        EventPublisher events = mock(EventPublisher.class);
        var handler = new LegacyPlanModelObservationHandler(projections, events);
        ProcessObservation synchronous = new ProcessObservation("process-start", 1,
                "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                "process-1", null, ProcessObservation.EventType.STARTED, ENGINE_AT, RECEIVED_AT,
                Map.of("processDefinitionKey", "legacy-process"));
        ProcessObservation history = new ProcessObservation("process-start-history", 1,
                "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                "process-1", 17L, ProcessObservation.EventType.STARTED, ENGINE_AT, RECEIVED_AT,
                Map.of("processDefinitionKey", "legacy-process"));

        handler.apply(synchronous);
        handler.apply(history);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(EventTypes.PROCESS_STARTED);
        verify(events).audit("case-1", "tenant-a", "engine", "engine.process.started",
                "Process", "process-1", null,
                Map.of("processDefinitionKey", "legacy-process",
                        "processInstanceId", "process-1"));
        verify(projections, never()).observe(
                org.mockito.ArgumentMatchers.any(ProcessCompletionObservation.class));
    }

    @Test
    void mapsEveryFormerLifecycleVariantWithoutUsingTheBpmnCanonicalHandler() {
        CaseProjectionPort projections = mock(CaseProjectionPort.class);
        var handler = new LegacyPlanModelObservationHandler(projections);

        for (UserTaskObservation.EventType type : UserTaskObservation.EventType.values()) {
            handler.apply(new UserTaskObservation("task-" + type, 1, "operaton:embedded",
                    "engine-a", "tenant-a", "case-1", "process-1", "task-1", null,
                    type, ENGINE_AT, RECEIVED_AT,
                    Map.of("taskDefinitionKey", "review", "activityInstanceId", "activity-1")));
        }
        for (ActivityLifecycleObservation.EventType type
                : ActivityLifecycleObservation.EventType.values()) {
            handler.apply(new ActivityLifecycleObservation("activity-" + type, 1,
                    "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                    "stage-instance", null, type, ENGINE_AT, RECEIVED_AT,
                    Map.of("activityId", "assessment")));
        }
        for (MilestoneObservation.EventType type : MilestoneObservation.EventType.values()) {
            handler.apply(new MilestoneObservation("milestone-" + type, 1,
                    "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                    "milestone-instance", null, type, ENGINE_AT, RECEIVED_AT,
                    Map.of("activityId", "approved", "milestoneId", "approved")));
        }
        for (ProcessObservation.EventType type : List.of(
                ProcessObservation.EventType.COMPLETED,
                ProcessObservation.EventType.TERMINATED)) {
            handler.apply(new ProcessObservation("process-" + type, 1, "operaton:embedded",
                    "engine-a", "tenant-a", "case-1", "process-1", "process-1", null,
                    type, ENGINE_AT, RECEIVED_AT,
                    Map.of("processDefinitionKey", "legacy-process")));
        }

        ArgumentCaptor<TaskObservation> tasks = ArgumentCaptor.forClass(TaskObservation.class);
        verify(projections, org.mockito.Mockito.times(6)).observe(tasks.capture());
        assertThat(tasks.getAllValues()).extracting(TaskObservation::eventName)
                .containsExactly("create", "assignment", "claim", "unclaim", "complete", "delete");
        ArgumentCaptor<ActivityObservation> activities =
                ArgumentCaptor.forClass(ActivityObservation.class);
        verify(projections, org.mockito.Mockito.times(6)).observe(activities.capture());
        assertThat(activities.getAllValues()).extracting(ActivityObservation::eventName)
                .containsExactly("start", "end", "delete", "end", "start", "delete");
        ArgumentCaptor<ProcessCompletionObservation> processes =
                ArgumentCaptor.forClass(ProcessCompletionObservation.class);
        verify(projections, org.mockito.Mockito.times(2)).observe(processes.capture());
        assertThat(processes.getAllValues()).extracting(ProcessCompletionObservation::endState)
                .containsExactly("completed", "cancelled");
    }
}
