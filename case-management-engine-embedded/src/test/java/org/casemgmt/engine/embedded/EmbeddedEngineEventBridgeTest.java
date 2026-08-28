package org.casemgmt.engine.embedded;

import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.EngineObservation;
import org.casemgmt.observation.EngineObservationHandler;
import org.casemgmt.observation.MilestoneObservation;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.casemgmt.projection.ActivityObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.spring.boot.starter.event.ExecutionEvent;
import org.operaton.bpm.spring.boot.starter.event.TaskEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedEngineEventBridgeTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-28T10:15:30Z");
    private static final Date ENGINE_AT = Date.from(Instant.parse("2026-08-28T10:15:00Z"));

    private EngineObservationHandler handler;
    private RepositoryService repository;
    private TaskService tasks;
    private ProcessActivityClassifier classifier;
    private EmbeddedEngineEventBridge bridge;

    @BeforeEach
    void setUp() {
        handler = mock(EngineObservationHandler.class);
        repository = mock(RepositoryService.class);
        tasks = mock(TaskService.class);
        classifier = mock(ProcessActivityClassifier.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("complaint-process");
        when(repository.getProcessDefinition("definition-1")).thenReturn(definition);
        when(classifier.taskMetadata(any(), any()))
                .thenReturn(new ProcessActivityClassifier.TaskMetadata(
                        List.of("handlers"), "reviewForm"));
        bridge = new EmbeddedEngineEventBridge(handler, processInstanceId -> "case-1",
                classifier, repository, tasks, "engine-a",
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    @Test
    void capturesCanonicalTaskCreateClaimAndCompleteFixtures() {
        bridge.onTask(task("create", null));
        bridge.onTask(task("assignment", "alice"));
        when(tasks.getVariables("task-1"))
                .thenReturn(Map.of("decision", "approved", "attempt", 3));
        bridge.onTask(task("complete", "alice"));

        ArgumentCaptor<EngineObservation> observations =
                ArgumentCaptor.forClass(EngineObservation.class);
        verify(handler, org.mockito.Mockito.times(3)).apply(observations.capture());

        assertThat(observations.getAllValues()).allSatisfy(observation -> {
            assertThat(observation.source()).isEqualTo("operaton:embedded");
            assertThat(observation.engineId()).isEqualTo("engine-a");
            assertThat(observation.tenantId()).isEqualTo("tenant-a");
            assertThat(observation.caseId()).isEqualTo("case-1");
            assertThat(observation.processInstanceId()).isEqualTo("process-1");
            assertThat(observation.entityId()).isEqualTo("task-1");
            assertThat(observation.entityRevision()).isNull();
            assertThat(observation.engineOccurredAt()).isEqualTo(ENGINE_AT.toInstant());
            assertThat(observation.receivedAt()).isEqualTo(RECEIVED_AT);
            assertThat(observation.attributes())
                    .containsEntry("processDefinitionId", "definition-1")
                    .containsEntry("processDefinitionKey", "complaint-process")
                    .containsEntry("taskDefinitionKey", "review")
                    .containsEntry("activityInstanceId", "task-1");
        });
        assertThat(observations.getAllValues())
                .extracting(value -> ((UserTaskObservation) value).eventType())
                .containsExactly(UserTaskObservation.EventType.CREATED,
                        UserTaskObservation.EventType.CLAIMED,
                        UserTaskObservation.EventType.COMPLETED);
        assertThat(observations.getAllValues().get(2).attributes().get("variables"))
                .isEqualTo(Map.of("decision", "approved",
                        "attempt", new java.math.BigDecimal("3")));
    }

    @Test
    void capturesCanonicalStageAndMilestoneFixtures() {
        when(classifier.classify("definition-1", "assessment-stage"))
                .thenReturn(Optional.of(new ProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.STAGE, null)));
        bridge.onExecution(execution("assessment-stage", "Assessment", "stage-instance", "start"));
        bridge.onExecution(execution("assessment-stage", "Assessment", "stage-instance", "end"));

        when(classifier.classify("definition-1", "approved-milestone"))
                .thenReturn(Optional.of(new ProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.MILESTONE, "approved")));
        bridge.onExecution(execution("approved-milestone", "Approved", "milestone-instance", "end"));

        ArgumentCaptor<EngineObservation> observations =
                ArgumentCaptor.forClass(EngineObservation.class);
        verify(handler, org.mockito.Mockito.times(3)).apply(observations.capture());
        assertThat(observations.getAllValues().get(0))
                .isInstanceOfSatisfying(ActivityLifecycleObservation.class, observation -> {
                    assertThat(observation.eventType())
                            .isEqualTo(ActivityLifecycleObservation.EventType.STARTED);
                    assertCanonicalActivity(observation, "stage-instance", "assessment-stage");
                });
        assertThat(observations.getAllValues().get(1))
                .isInstanceOfSatisfying(ActivityLifecycleObservation.class, observation ->
                        assertThat(observation.eventType())
                                .isEqualTo(ActivityLifecycleObservation.EventType.COMPLETED));
        assertThat(observations.getAllValues().get(2))
                .isInstanceOfSatisfying(MilestoneObservation.class, observation -> {
                    assertThat(observation.eventType())
                            .isEqualTo(MilestoneObservation.EventType.REACHED);
                    assertThat(observation.attributes()).containsEntry("milestoneId", "approved");
                    assertCanonicalActivity(observation, "milestone-instance", "approved-milestone");
                });
    }

    @Test
    void capturesCanonicalSubprocessCompletionCancellationAndRootCompletionFixtures() {
        bridge.onHistory(processHistory("child-1", "child-definition", "child-process",
                "tenant-a", null, 41));
        bridge.onHistory(processHistory("process-1", "definition-1", "complaint-process",
                "tenant-a", "deleted by operator", 42));
        bridge.onHistory(processHistory("process-2", "definition-1", "complaint-process",
                "tenant-a", null, 43));

        ArgumentCaptor<EngineObservation> observations =
                ArgumentCaptor.forClass(EngineObservation.class);
        verify(handler, org.mockito.Mockito.times(3)).apply(observations.capture());
        assertThat(observations.getAllValues())
                .extracting(value -> ((ProcessObservation) value).eventType())
                .containsExactly(ProcessObservation.EventType.COMPLETED,
                        ProcessObservation.EventType.TERMINATED,
                        ProcessObservation.EventType.COMPLETED);
        assertThat(observations.getAllValues())
                .extracting(EngineObservation::entityRevision)
                .containsExactly(41L, 42L, 43L);
        assertThat(observations.getAllValues().get(0).attributes())
                .containsEntry("processDefinitionId", "child-definition")
                .containsEntry("processDefinitionKey", "child-process");
    }

    @Test
    void ignoresUncorrelatedAndUnknownEngineEvents() {
        EmbeddedEngineEventBridge uncorrelated = new EmbeddedEngineEventBridge(handler,
                processInstanceId -> null, classifier, repository, tasks, "engine-a",
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        TaskEvent unknown = task("unexpected", null);

        uncorrelated.onTask(unknown);
        bridge.onTask(unknown);
        bridge.onTask(task("update", "alice"));

        verify(handler, never()).apply(any());
    }

    private TaskEvent task(String eventName, String assignee) {
        TaskEvent event = mock(TaskEvent.class);
        when(event.getProcessInstanceId()).thenReturn("process-1");
        when(event.getProcessDefinitionId()).thenReturn("definition-1");
        when(event.getTenantId()).thenReturn("tenant-a");
        when(event.getId()).thenReturn("task-1");
        when(event.getTaskDefinitionKey()).thenReturn("review");
        when(event.getName()).thenReturn("Review complaint");
        when(event.getEventName()).thenReturn(eventName);
        when(event.getAssignee()).thenReturn(assignee);
        when(event.getPriority()).thenReturn(70);
        when(event.getCreateTime()).thenReturn(ENGINE_AT);
        when(event.getLastUpdated()).thenReturn(ENGINE_AT);
        return event;
    }

    private ExecutionEvent execution(String activityId, String name, String instanceId,
                                     String eventName) {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getProcessInstanceId()).thenReturn("process-1");
        when(event.getProcessDefinitionId()).thenReturn("definition-1");
        when(event.getTenantId()).thenReturn("tenant-a");
        when(event.getProcessBusinessKey()).thenReturn("case-1");
        when(event.getActivityInstanceId()).thenReturn(instanceId);
        when(event.getCurrentActivityId()).thenReturn(activityId);
        when(event.getCurrentActivityName()).thenReturn(name);
        when(event.getEventName()).thenReturn(eventName);
        return event;
    }

    private HistoricProcessInstanceEventEntity processHistory(
            String processInstanceId, String processDefinitionId, String processDefinitionKey,
            String tenantId, String deleteReason, long sequence) {
        HistoricProcessInstanceEventEntity event = new HistoricProcessInstanceEventEntity();
        event.setId(processInstanceId);
        event.setProcessInstanceId(processInstanceId);
        event.setProcessDefinitionId(processDefinitionId);
        event.setProcessDefinitionKey(processDefinitionKey);
        event.setTenantId(tenantId);
        event.setBusinessKey("case-1");
        event.setEndTime(ENGINE_AT);
        event.setDeleteReason(deleteReason);
        event.setSequenceCounter(sequence);
        return event;
    }

    private static void assertCanonicalActivity(EngineObservation observation, String entityId,
                                                String activityId) {
        assertThat(observation.source()).isEqualTo("operaton:embedded");
        assertThat(observation.engineId()).isEqualTo("engine-a");
        assertThat(observation.tenantId()).isEqualTo("tenant-a");
        assertThat(observation.caseId()).isEqualTo("case-1");
        assertThat(observation.processInstanceId()).isEqualTo("process-1");
        assertThat(observation.entityId()).isEqualTo(entityId);
        assertThat(observation.attributes())
                .containsEntry("processDefinitionId", "definition-1")
                .containsEntry("processDefinitionKey", "complaint-process")
                .containsEntry("activityId", activityId);
    }
}
