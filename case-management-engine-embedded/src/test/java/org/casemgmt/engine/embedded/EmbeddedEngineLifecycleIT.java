package org.casemgmt.engine.embedded;

import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.ApplyResult;
import org.casemgmt.observation.ApplyStatus;
import org.casemgmt.observation.EngineObservation;
import org.casemgmt.observation.EngineObservationHandler;
import org.casemgmt.observation.MilestoneObservation;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = EmbeddedEngineLifecycleIT.TestApp.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedEngineLifecycleIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        CapturingHandler capturingHandler() {
            return new CapturingHandler();
        }

        @Bean
        ProcessActivityClassifier processActivityClassifier(RepositoryService repository) {
            return new RepositoryProcessActivityClassifier(repository);
        }

        @Bean
        ProcessCaseCorrelation processCaseCorrelation(RuntimeService runtime) {
            return processInstanceId -> {
                Object caseId = runtime.getVariable(processInstanceId,
                        EmbeddedEngineGateway.CASE_ID_VARIABLE);
                if (caseId != null) {
                    return caseId.toString();
                }
                ProcessInstance process = runtime.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId).singleResult();
                return process == null ? null : process.getBusinessKey();
            };
        }

        @Bean
        EmbeddedEngineEventBridge embeddedEngineEventBridge(
                CapturingHandler handler,
                ProcessCaseCorrelation correlation,
                ProcessActivityClassifier classifier,
                RepositoryService repository,
                TaskService tasks) {
            return new EmbeddedEngineEventBridge(handler, correlation, classifier,
                    repository, tasks, "engine-a");
        }
    }

    static final class CapturingHandler implements EngineObservationHandler {
        private final List<EngineObservation> observations = new CopyOnWriteArrayList<>();
        private volatile boolean failTaskCompletion;

        @Override
        public ApplyResult apply(EngineObservation observation) {
            observations.add(observation);
            if (failTaskCompletion
                    && observation instanceof UserTaskObservation task
                    && task.eventType() == UserTaskObservation.EventType.COMPLETED) {
                throw new IllegalStateException("simulated lifecycle persistence failure");
            }
            return new ApplyResult(observation.observationId(), ApplyStatus.APPLIED, 1, List.of());
        }

        void reset() {
            observations.clear();
            failTaskCompletion = false;
        }
    }

    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired CapturingHandler handler;

    private String processDefinitionId;

    @BeforeAll
    void deploy() {
        var deployment = repository.createDeployment()
                .tenantId("tenant-a")
                .addClasspathResource("canonical-lifecycle-process.bpmn")
                .deploy();
        processDefinitionId = repository.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult().getId();
    }

    @BeforeEach
    void resetCapture() {
        handler.reset();
    }

    @Test
    void emitsCanonicalLifecycleForRealOperatonCommands() {
        ProcessInstance process = start("case-happy");
        var task = tasks.createTaskQuery().processInstanceId(process.getId()).singleResult();

        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(UserTaskObservation.class,
                        observation -> assertThat(observation.eventType())
                                .isEqualTo(UserTaskObservation.EventType.CREATED)));

        tasks.claim(task.getId(), "alice");
        tasks.complete(task.getId(), Map.of("decision", "approved"));

        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(UserTaskObservation.class,
                        observation -> {
                            assertThat(observation.eventType())
                                    .isEqualTo(UserTaskObservation.EventType.CLAIMED);
                            assertThat(observation.attributes()).containsEntry("assignee", "alice");
                        }));
        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(UserTaskObservation.class,
                        observation -> {
                            assertThat(observation.eventType())
                                    .isEqualTo(UserTaskObservation.EventType.COMPLETED);
                            assertThat(observation.attributes().get("variables"))
                                    .isEqualTo(Map.of("decision", "approved",
                                            EmbeddedEngineGateway.CASE_ID_VARIABLE, "case-happy"));
                        }));
        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(ActivityLifecycleObservation.class,
                        observation -> {
                            assertThat(observation.attributes()).containsEntry("activityId", "assessment");
                            assertThat(observation.eventType())
                                    .isEqualTo(ActivityLifecycleObservation.EventType.STARTED);
                        }));
        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(ActivityLifecycleObservation.class,
                        observation -> {
                            assertThat(observation.attributes()).containsEntry("activityId", "assessment");
                            assertThat(observation.eventType())
                                    .isEqualTo(ActivityLifecycleObservation.EventType.COMPLETED);
                        }));
        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(MilestoneObservation.class,
                        observation -> {
                            assertThat(observation.eventType())
                                    .isEqualTo(MilestoneObservation.EventType.REACHED);
                            assertThat(observation.attributes()).containsEntry("milestoneId", "approved");
                        }));
        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(ProcessObservation.class,
                        observation -> {
                            assertThat(observation.processInstanceId()).isEqualTo(process.getId());
                            assertThat(observation.eventType())
                                    .isEqualTo(ProcessObservation.EventType.COMPLETED);
                            assertThat(observation.entityRevision()).isNull();
                        }));
        assertThat(handler.observations).noneMatch(value ->
                value instanceof UserTaskObservation observation
                        && observation.eventType() == UserTaskObservation.EventType.ASSIGNED);
        assertThat(handler.observations).noneMatch(value ->
                value instanceof MilestoneObservation observation
                        && observation.eventType() == MilestoneObservation.EventType.REOPENED);
        assertThat(handler.observations.stream()
                .filter(UserTaskObservation.class::isInstance)
                .map(UserTaskObservation.class::cast)
                .map(UserTaskObservation::entityRevision))
                .containsOnlyNulls();
        assertCanonicalAuthority("case-happy", process.getId());
    }

    @Test
    void emitsCancellationForARealDeletedProcess() {
        ProcessInstance process = start("case-cancelled");

        runtime.deleteProcessInstance(process.getId(), "operator cancellation");

        assertThat(handler.observations).anySatisfy(value ->
                assertThat(value).isInstanceOfSatisfying(ProcessObservation.class,
                        observation -> {
                            assertThat(observation.processInstanceId()).isEqualTo(process.getId());
                            assertThat(observation.eventType())
                                    .isEqualTo(ProcessObservation.EventType.TERMINATED);
                        }));
    }

    @Test
    void lifecycleFailureRollsBackTheOperatonTaskCompletionCommand() {
        ProcessInstance process = start("case-rollback");
        var task = tasks.createTaskQuery().processInstanceId(process.getId()).singleResult();
        handler.failTaskCompletion = true;

        assertThatThrownBy(() -> tasks.complete(task.getId(), Map.of("decision", "approved")))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("simulated lifecycle persistence failure");

        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult()).isNotNull();
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(process.getId())
                .singleResult()).isNotNull();
    }

    private ProcessInstance start(String caseId) {
        return runtime.startProcessInstanceById(processDefinitionId, caseId,
                Map.of(EmbeddedEngineGateway.CASE_ID_VARIABLE, caseId));
    }

    private void assertCanonicalAuthority(String caseId, String processInstanceId) {
        assertThat(handler.observations).allSatisfy(observation -> {
            assertThat(observation.source()).isEqualTo(EmbeddedEngineEventBridge.SOURCE);
            assertThat(observation.engineId()).isEqualTo("engine-a");
            assertThat(observation.tenantId()).isEqualTo("tenant-a");
            assertThat(observation.caseId()).isEqualTo(caseId);
            assertThat(observation.processInstanceId()).isEqualTo(processInstanceId);
            assertThat(observation.attributes())
                    .containsEntry("processDefinitionId", processDefinitionId)
                    .containsEntry("processDefinitionKey", "canonical-lifecycle");
            assertThat(observation.engineOccurredAt()).isNotNull();
            assertThat(observation.receivedAt()).isNotNull();
        });
    }
}
