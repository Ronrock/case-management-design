package org.casemgmt.engine.embedded;

import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.projection.ActivityObservation;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.spring.boot.starter.event.TaskEvent;
import org.operaton.bpm.spring.boot.starter.event.ExecutionEvent;

import java.util.Date;
import java.util.Optional;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedEngineEventBridgeTest {

    @Test
    void translatesBuiltInSpringTaskEventsIntoNeutralObservations() {
        CaseProjectionPort projections = mock(CaseProjectionPort.class);
        EmbeddedEngineEventBridge bridge = new EmbeddedEngineEventBridge(
                projections, processInstanceId -> "case-1", new ProcessActivityClassifier() {
                    @Override
                    public Optional<Classification> classify(String definition, String activity) {
                        return Optional.empty();
                    }

                    @Override
                    public TaskMetadata taskMetadata(String definition, String activity) {
                        return new TaskMetadata(List.of("handlers"), "reviewForm");
                    }
                });
        TaskEvent event = mock(TaskEvent.class);
        when(event.getProcessInstanceId()).thenReturn("proc-1");
        when(event.getExecutionId()).thenReturn("activity-instance-1");
        when(event.getId()).thenReturn("task-1");
        when(event.getTaskDefinitionKey()).thenReturn("review");
        when(event.getName()).thenReturn("Review complaint");
        when(event.getEventName()).thenReturn("create");
        when(event.getCreateTime()).thenReturn(new Date());

        bridge.onTask(event);

        verify(projections).observe(argThat((TaskObservation observation) ->
                observation.caseId().equals("case-1")
                        && observation.engineTaskId().equals("task-1")
                        && observation.activityInstanceId().equals("task-1")
                        && observation.activityId().equals("review")
                        && observation.candidateGroups().equals(List.of("handlers"))
                        && observation.formKey().equals("reviewForm")));
    }

    @Test
    void classifiesTaggedExecutionEventsWithoutLeakingEngineTypesIntoCore() {
        CaseProjectionPort projections = mock(CaseProjectionPort.class);
        EmbeddedEngineEventBridge bridge = new EmbeddedEngineEventBridge(projections,
                processInstanceId -> "case-1", (definition, activity) -> Optional.of(
                        new ProcessActivityClassifier.Classification(
                                ActivityObservation.Kind.STAGE, null)));
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getProcessInstanceId()).thenReturn("proc-1");
        when(event.getProcessDefinitionId()).thenReturn("definition-1");
        when(event.getActivityInstanceId()).thenReturn("stage-instance-1");
        when(event.getCurrentActivityId()).thenReturn("assessment-stage");
        when(event.getCurrentActivityName()).thenReturn("Assessment");
        when(event.getEventName()).thenReturn("start");

        bridge.onExecution(event);

        verify(projections).observe(argThat((ActivityObservation observation) ->
                observation.kind() == ActivityObservation.Kind.STAGE
                        && observation.activityInstanceId().equals("stage-instance-1")));
    }
}
