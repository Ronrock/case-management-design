package org.casemgmt.starter;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.domain.TaskState;
import org.casemgmt.engine.embedded.EmbeddedTransactionResourceValidator;
import org.casemgmt.event.EventTypes;
import org.casemgmt.observation.SlaLifecyclePort;
import org.casemgmt.observation.EngineObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.MilestoneObservation;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.LegacyPlanModelObservationHandler;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CaseTaskService;
import org.casemgmt.service.LinkedProcessService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Operaton commands wired to the production observation handler and lifecycle persistence.
 * H2 runs both schemas on one local transaction resource; production Oracle runtime remains a
 * separate Docker-backed gate.
 */
@SpringBootTest(classes = ProductionEmbeddedLifecycleIT.TestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:production-lifecycle;MODE=LEGACY;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE CONSTANT IF NOT EXISTS SYSTIMESTAMP VALUE "
                + "'2026-08-28 12:00:00+00'",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.liquibase.enabled=true",
        "spring.liquibase.change-log=classpath:production-embedded-lifecycle-changelog.xml",
        "operaton.bpm.database.schema-update=true",
        "operaton.bpm.history-level=full",
        "operaton.bpm.job-execution.enabled=false",
        "operaton.bpm.generic-properties.properties.history-time-to-live=180",
        "casemgmt.enabled=true",
        "casemgmt.engine.mode=embedded",
        "casemgmt.engine-id=engine-a",
        "casemgmt.events.type-prefix=org.casemgmt.test",
        "casemgmt.webhooks.secret-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "casemgmt.schedulers.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionEmbeddedLifecycleIT {

    private static final String TENANT = "tenant-a";
    private static final Actor ACTOR = new Actor("alice", List.of("handlers"));

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean("dataSource")
        @Primary
        DataSource caseDataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setJdbcUrl(
                    "jdbc:h2:mem:production-lifecycle;MODE=LEGACY;DB_CLOSE_DELAY=-1;"
                            + "INIT=CREATE CONSTANT IF NOT EXISTS SYSTIMESTAMP VALUE "
                            + "'2026-08-28 12:00:00+00'");
            return dataSource;
        }

        @Bean
        FailingSlaLifecyclePort failingSlaLifecyclePort() {
            return new FailingSlaLifecyclePort();
        }

        @Bean("consumerDataSource")
        DataSource consumerDataSource() {
            return new SimpleDriverDataSource(new org.h2.Driver(),
                    "jdbc:h2:mem:consumer-primary-jdbc;DB_CLOSE_DELAY=-1");
        }

        @Bean("consumerJdbcClient")
        @Primary
        JdbcClient consumerJdbcClient(@Qualifier("consumerDataSource") DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        @Primary
        RecordingPlanModelObservationHandler recordingPlanModelObservationHandler(
                CaseProjectionPort projections, EventPublisher events) {
            return new RecordingPlanModelObservationHandler(projections, events);
        }
    }

    static final class RecordingPlanModelObservationHandler
            extends LegacyPlanModelObservationHandler {
        private final List<EngineObservation> recorded = new CopyOnWriteArrayList<>();

        RecordingPlanModelObservationHandler(CaseProjectionPort projections,
                                             EventPublisher events) {
            super(projections, events);
        }

        @Override
        public void apply(EngineObservation observation) {
            recorded.add(observation);
            super.apply(observation);
        }

        void clear() {
            recorded.clear();
        }
    }

    static final class FailingSlaLifecyclePort implements SlaLifecyclePort {
        private volatile boolean failCreatedTask;
        private volatile boolean failCompletedTask;
        private volatile boolean failTerminatedProcess;

        @Override
        public void observeAnchor(Anchor anchor) {
            if ("user-task".equals(anchor.observationKind())
                    && ((failCreatedTask && "CREATED".equals(anchor.eventType()))
                    || (failCompletedTask && "COMPLETED".equals(anchor.eventType())))) {
                throw new IllegalStateException("injected lifecycle persistence failure");
            }
            if (failTerminatedProcess && "process".equals(anchor.observationKind())
                    && "TERMINATED".equals(anchor.eventType())) {
                throw new IllegalStateException("injected cancellation persistence failure");
            }
        }

        @Override
        public void terminalizeRoot(String caseId, TerminalState state,
                                    java.time.Instant occurredAt) { }
    }

    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired @Qualifier("caseJdbcClient") JdbcClient jdbc;
    @Autowired @Qualifier("consumerJdbcClient") JdbcClient consumerJdbc;
    @Autowired DataSource dataSource;
    @Autowired CaseRepository cases;
    @Autowired LinkedProcessRepository processes;
    @Autowired LinkedProcessService linkedProcesses;
    @Autowired CaseTaskService caseTasks;
    @Autowired FailingSlaLifecyclePort failures;
    @Autowired EmbeddedTransactionResourceValidator transactionResourceValidator;
    @Autowired RecordingPlanModelObservationHandler planModelObservations;
    @Autowired @Qualifier("caseManagementCaseService") CaseService caseService;

    private ProcessDefinition rootDefinition;
    private ProcessDefinition childDefinition;
    private ProcessDefinition planChildDefinition;

    @BeforeAll
    void deployAndPublishDefinition() {
        var deployment = repository.createDeployment()
                .tenantId(TENANT)
                .addClasspathResource("production-embedded-lifecycle.bpmn")
                .deploy();
        rootDefinition = repository.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionKey("production-root")
                .singleResult();
        childDefinition = repository.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionKey("production-child")
                .singleResult();
        planChildDefinition = repository.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionKey("production-plan-child")
                .singleResult();
        publishCaseDefinition(rootDefinition);
        publishPlanModelDefinition();
    }

    @BeforeEach
    void resetFailure() {
        failures.failCreatedTask = false;
        failures.failCompletedTask = false;
        failures.failTerminatedProcess = false;
        planModelObservations.clear();
    }

    @Test
    void caseCreateAndLinkedChildUseProductionAuthorityAndLifecycleEffects() {
        assertThat(transactionResourceValidator).isNotNull();
        assertThatThrownBy(() -> consumerJdbc.sql("SELECT COUNT(*) FROM CM_CASE")
                .query(Integer.class).single()).isInstanceOf(RuntimeException.class);
        var created = caseService.create("production-root", TENANT, "business-1",
                "Production lifecycle", CasePriority.MEDIUM, Map.of(), ACTOR);

        assertThat(created.rootProcessInstanceId()).isNotBlank();
        assertThat(processes.findByCase(created.id())).singleElement().satisfies(root -> {
            assertThat(root.caseRoot()).isTrue();
            assertThat(root.processInstanceId()).isEqualTo(created.rootProcessInstanceId());
            assertThat(root.processDefinitionId()).isEqualTo(rootDefinition.getId());
            assertThat(root.engineSync().name()).isEqualTo("SYNCED");
        });
        var rootTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        assertProjectedTask(rootTask.getId(), created.id(), "OPEN", null);
        assertThat(observationCount(created.id(), "PROCESS", "STARTED")).isEqualTo(1);

        tasks.claim(rootTask.getId(), "alice");
        assertProjectedTask(rootTask.getId(), created.id(), "CLAIMED", "alice");

        var child = linkedProcesses.start(created.id(), null, "production-child",
                Map.of(), ACTOR);
        assertThat(child.processInstanceId()).isNotBlank();
        assertThat(child.processDefinitionId()).isEqualTo(childDefinition.getId());
        var childTask = tasks.createTaskQuery()
                .processInstanceId(child.processInstanceId()).singleResult();
        assertProjectedTask(childTask.getId(), created.id(), "OPEN", null);
        assertThat(eventCount(created.id(), EventTypes.PROCESS_STARTED)).isEqualTo(2);
        assertThat(auditActionCount(created.id(), "engine.process.started")).isEqualTo(2);

        tasks.complete(childTask.getId());

        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.ACTIVE);
        assertThat(processes.findByProcessInstanceId(child.processInstanceId()).orElseThrow().state())
                .isEqualTo("COMPLETED");

        tasks.complete(rootTask.getId());

        assertPlanItem(created.id(), "STAGE", "ACTIVE");
        var stageTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        assertThat(stageTask.getTaskDefinitionKey()).isEqualTo("root-stage-review");
        assertProjectedTask(stageTask.getId(), created.id(), "OPEN", null);
        tasks.complete(stageTask.getId());

        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.CLOSED);
        assertThat(processes.findByProcessInstanceId(created.rootProcessInstanceId())
                .orElseThrow().state()).isEqualTo("COMPLETED");
        assertPlanItem(created.id(), "STAGE", "COMPLETED");
        assertPlanItem(created.id(), "MILESTONE", "COMPLETED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM CM_MILESTONE WHERE CASE_ID_ = :caseId "
                        + "AND ACHIEVED_ = 1")
                .param("caseId", created.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(auditActionCount(created.id(), "engine.activity.started")).isEqualTo(1);
        assertThat(auditActionCount(created.id(), "engine.activity.completed")).isEqualTo(1);
        assertThat(auditActionCount(created.id(), "engine.milestone.reached")).isEqualTo(1);
        assertThat(eventCount(created.id(), EventTypes.MILESTONE_ACHIEVED)).isEqualTo(1);
    }

    @Test
    void unmanagedBusinessKeyProcessIsIgnoredByProductionBridge() {
        var managed = caseService.create("production-root", TENANT, "business-foreign",
                "Managed", CasePriority.MEDIUM, Map.of(), ACTOR);

        var foreign = runtime.startProcessInstanceById(
                childDefinition.getId(), managed.id(), Map.of());
        var foreignTask = tasks.createTaskQuery()
                .processInstanceId(foreign.getId()).singleResult();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM CM_TASK WHERE CAMUNDA_TASK_ID_ = :taskId")
                .param("taskId", foreignTask.getId()).query(Integer.class).single()).isZero();
        assertThat(processes.findByProcessInstanceId(foreign.getId())).isEmpty();
    }

    @Test
    void lifecycleFailureRollsBackEngineAndPlatformRowsTogether() {
        var created = caseService.create("production-root", TENANT, "business-rollback",
                "Rollback", CasePriority.MEDIUM, Map.of(), ACTOR);
        var task = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        int observationsBefore = observationCount(created.id(), null, null);
        failures.failCompletedTask = true;

        assertThatThrownBy(() -> tasks.complete(task.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("injected lifecycle persistence failure");

        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult()).isNotNull();
        assertProjectedTask(task.getId(), created.id(), "OPEN", null);
        assertThat(observationCount(created.id(), null, null)).isEqualTo(observationsBefore);
        assertThat(observationCount(created.id(), "USER_TASK", "COMPLETED")).isZero();
    }

    @Test
    void cancellingAProcessInsideAnActiveStagePersistsAllProductionEffects() {
        var created = caseService.create("production-root", TENANT, "business-cancel-effects",
                "Cancel effects", CasePriority.MEDIUM, Map.of(), ACTOR);
        var rootTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        tasks.complete(rootTask.getId());
        var stageTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        assertPlanItem(created.id(), "STAGE", "ACTIVE");

        runtime.deleteProcessInstance(created.rootProcessInstanceId(), "operator cancellation");

        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.CANCELLED);
        assertThat(processes.findByProcessInstanceId(created.rootProcessInstanceId())
                .orElseThrow().state()).isEqualTo("TERMINATED");
        assertProjectedTask(stageTask.getId(), created.id(), "TERMINATED", null);
        assertThat(auditActionCount(created.id(), "engine.activity.cancelled")).isEqualTo(1);
        assertPlanItem(created.id(), "STAGE", "TERMINATED");
        assertThat(auditActionCount(created.id(), "engine.process.terminated")).isEqualTo(1);
        assertThat(eventCount(created.id(), EventTypes.CASE_CANCELLED)).isEqualTo(1);
    }

    @Test
    void publicCaseCancellationKeepsTheSynchronousEngineTransitionAndPersistsUserReasonOnce() {
        var created = caseService.create("production-root", TENANT, "business-api-cancel",
                "API cancel", CasePriority.MEDIUM, Map.of(), ACTOR);
        var rootTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        tasks.complete(rootTask.getId());
        var stageTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        assertPlanItem(created.id(), "STAGE", "ACTIVE");

        var cancelled = caseService.cancel(created.id(), cases.require(created.id()).version(),
                "customer withdrew", ACTOR);

        assertThat(cancelled.state()).isEqualTo(CaseState.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("customer withdrew");
        assertThat(cases.require(created.id()).cancelReason()).isEqualTo("customer withdrew");
        assertThat(runtime.createProcessInstanceQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult()).isNull();
        assertThat(processes.findByProcessInstanceId(created.rootProcessInstanceId())
                .orElseThrow().state()).isEqualTo("TERMINATED");
        assertProjectedTask(stageTask.getId(), created.id(), "TERMINATED", null);
        assertPlanItem(created.id(), "STAGE", "TERMINATED");
        assertThat(eventCount(created.id(), EventTypes.CASE_CANCELLED)).isEqualTo(1);
        assertThat(auditActionCount(created.id(), "engine.process.terminated")).isEqualTo(1);
        assertThat(auditActionCount(created.id(), "case.cancel")).isEqualTo(1);
    }

    @Test
    void publicCaseCancellationRollsBackEngineAndPlatformOnHandlerFailure() {
        var created = caseService.create("production-root", TENANT, "business-api-cancel-fail",
                "API cancel failure", CasePriority.MEDIUM, Map.of(), ACTOR);
        var rootTask = tasks.createTaskQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult();
        int eventsBefore = rowCount("CM_EVENT");
        int auditsBefore = rowCount("CM_AUDIT_LOG");
        int observationsBefore = observationCount(created.id(), null, null);
        failures.failTerminatedProcess = true;

        assertThatThrownBy(() -> caseService.cancel(created.id(),
                cases.require(created.id()).version(), "must roll back", ACTOR))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("injected cancellation persistence failure");

        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.ACTIVE);
        assertThat(cases.require(created.id()).cancelReason()).isNull();
        assertThat(runtime.createProcessInstanceQuery()
                .processInstanceId(created.rootProcessInstanceId()).singleResult()).isNotNull();
        assertThat(processes.findByProcessInstanceId(created.rootProcessInstanceId())
                .orElseThrow().state()).isEqualTo("ACTIVE");
        assertProjectedTask(rootTask.getId(), created.id(), "OPEN", null);
        assertThat(rowCount("CM_EVENT")).isEqualTo(eventsBefore);
        assertThat(rowCount("CM_AUDIT_LOG")).isEqualTo(auditsBefore);
        assertThat(observationCount(created.id(), null, null)).isEqualTo(observationsBefore);
    }

    @Test
    void lifecycleFailureDuringRootStartRollsBackEngineAndPendingAuthorityTogether() {
        long engineProcessesBefore = runtime.createProcessInstanceQuery()
                .processDefinitionId(rootDefinition.getId()).count();
        int casesBefore = rowCount("CM_CASE");
        int linksBefore = rowCount("CM_LINKED_PROCESS");
        int tasksBefore = rowCount("CM_TASK");
        int observationsBefore = rowCount("CM_APPLIED_ENGINE_OBSERVATION");
        failures.failCreatedTask = true;

        assertThatThrownBy(() -> caseService.create("production-root", TENANT,
                "business-start-rollback", "Start rollback", CasePriority.MEDIUM,
                Map.of(), ACTOR))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("injected lifecycle persistence failure");

        assertThat(runtime.createProcessInstanceQuery()
                .processDefinitionId(rootDefinition.getId()).count())
                .isEqualTo(engineProcessesBefore);
        assertThat(rowCount("CM_CASE")).isEqualTo(casesBefore);
        assertThat(rowCount("CM_LINKED_PROCESS")).isEqualTo(linksBefore);
        assertThat(rowCount("CM_TASK")).isEqualTo(tasksBefore);
        assertThat(rowCount("CM_APPLIED_ENGINE_OBSERVATION")).isEqualTo(observationsBefore);
    }

    @Test
    void lifecycleFailureDuringChildStartRollsBackEngineAndPendingAuthorityTogether() {
        var created = caseService.create("production-root", TENANT, "business-child-rollback",
                "Child rollback", CasePriority.MEDIUM, Map.of(), ACTOR);
        long engineProcessesBefore = runtime.createProcessInstanceQuery()
                .processDefinitionId(childDefinition.getId()).count();
        int linksBefore = rowCount("CM_LINKED_PROCESS");
        int tasksBefore = rowCount("CM_TASK");
        int observationsBefore = observationCount(created.id(), null, null);
        failures.failCreatedTask = true;

        assertThatThrownBy(() -> linkedProcesses.start(created.id(), null,
                "production-child", Map.of(), ACTOR))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("injected lifecycle persistence failure");

        assertThat(runtime.createProcessInstanceQuery()
                .processDefinitionId(childDefinition.getId()).count())
                .isEqualTo(engineProcessesBefore);
        assertThat(rowCount("CM_LINKED_PROCESS")).isEqualTo(linksBefore);
        assertThat(rowCount("CM_TASK")).isEqualTo(tasksBefore);
        assertThat(observationCount(created.id(), null, null)).isEqualTo(observationsBefore);
    }

    @Test
    void planModelProcessTaskUsesPersistedCompatibilityAuthorityThroughNestedTaskCompletion() {
        var created = caseService.create("production-plan", TENANT, "business-plan",
                "Plan compatibility", CasePriority.MEDIUM, Map.of(), ACTOR);
        var link = processes.findByCase(created.id()).getFirst();
        var legacyTask = caseTasks.forCase(created.id()).getFirst();

        assertThat(legacyTask.state()).isEqualTo(TaskState.OPEN);
        var claimed = caseTasks.claim(legacyTask.id(), legacyTask.version(), ACTOR);
        var completed = caseTasks.complete(
                claimed.id(), claimed.version(), Map.of("outcome", "approved"), ACTOR);
        assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);

        assertThat(link.processDefinitionId()).isEqualTo(planChildDefinition.getId());
        assertThat(link.planItemId()).isNotBlank();
        var nestedTask = tasks.createTaskQuery()
                .processInstanceId(link.processInstanceId()).singleResult();
        assertThat(nestedTask).isNotNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM CM_TASK WHERE CAMUNDA_TASK_ID_ = :taskId")
                .param("taskId", nestedTask.getId()).query(Integer.class).single()).isZero();

        jdbc.sql("UPDATE CM_LINKED_PROCESS SET PROC_DEF_ID_ = NULL WHERE ID_ = :id")
                .param("id", link.id()).update();
        planModelObservations.clear();
        tasks.claim(nestedTask.getId(), "alice");
        assertThat(processes.findByProcessInstanceId(link.processInstanceId())
                .orElseThrow().processDefinitionId()).isEqualTo(planChildDefinition.getId());

        tasks.complete(nestedTask.getId());
        var stageTask = tasks.createTaskQuery()
                .processInstanceId(link.processInstanceId()).singleResult();
        assertThat(stageTask.getTaskDefinitionKey()).isEqualTo("plan-child-stage-review");
        tasks.complete(stageTask.getId());

        assertThat(runtime.createProcessInstanceQuery()
                .processInstanceId(link.processInstanceId()).singleResult()).isNull();
        assertThat(processes.findByProcessInstanceId(link.processInstanceId()).orElseThrow().state())
                .isEqualTo("COMPLETED");
        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.ACTIVE);
        assertThat(eventCount(created.id(), EventTypes.PROCESS_STARTED)).isEqualTo(1);
        assertThat(auditActionCount(created.id(), "engine.process.started")).isEqualTo(1);
        assertThat(planModelObservations.recorded)
                .anyMatch(UserTaskObservation.class::isInstance)
                .anyMatch(ActivityLifecycleObservation.class::isInstance)
                .anyMatch(MilestoneObservation.class::isInstance)
                .anyMatch(observation -> observation instanceof ProcessObservation process
                        && process.eventType() == ProcessObservation.EventType.COMPLETED);
    }

    private void publishCaseDefinition(ProcessDefinition definition) {
        String definitionId = "production-root:1";
        OffsetDateTime now = OffsetDateTime.now();
        new CaseDefinitionRepository(dataSource).insert(new CaseDefinition(
                definitionId, "production-root", 1, "Production root", TENANT,
                null, null, List.of("handlers"), List.of(), Map.of(), List.of(),
                OrchestrationMode.BPMN, now, "publisher"));

        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                definition.getDeploymentId(), definition.getId(), definition.getKey(),
                definition.getVersion(), definition.getTenantId());
        CaseDefinitionReleaseRepository releases =
                new CaseDefinitionReleaseRepository(dataSource);
        releases.insert(CaseDefinitionRelease.storedWithEngineIdentity(
                "production-root-orchestration", "production-root", TENANT,
                ReleaseKind.ORCHESTRATION, "application/xml",
                "<definitions/>".getBytes(StandardCharsets.UTF_8), sha('a'),
                ReleaseStatus.ACTIVE, identity, null, "publisher"));
        releases.insert(CaseDefinitionRelease.stored(
                "production-root-contract", "production-root", TENANT,
                ReleaseKind.CONTRACT, "application/json", contract(), sha('b'),
                ReleaseStatus.ACTIVE, null, null, "publisher"));
        releases.insert(CaseDefinitionRelease.stored(
                "production-root-presentation", "production-root", TENANT,
                ReleaseKind.PRESENTATION, "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                sha('c'), ReleaseStatus.ACTIVE, null, null, "publisher"));
        new CaseDefinitionVersionBindingRepository(dataSource).insert(
                new CaseDefinitionVersionBinding(definitionId, "production-root", TENANT,
                        "production-root-orchestration", sha('a'),
                        "production-root-contract", sha('b'),
                        "production-root-presentation", sha('c'), ReleaseStatus.ACTIVE,
                        OrchestrationMode.BPMN, BindingStatus.ACTIVE, identity, null,
                        now, now, null, "publisher"));
    }

    private void publishPlanModelDefinition() {
        String definitionId = "production-plan:1";
        OffsetDateTime now = OffsetDateTime.now();
        var processTask = new PlanItemDefinition("production-plan-task:1", definitionId,
                "nested-process", PlanItemType.PROCESS_TASK, "Nested process", null,
                false, true, false, List.of(), List.of(), null,
                "production-plan-child", List.of(), 10);
        var humanTask = new PlanItemDefinition("production-human-task:1", definitionId,
                "legacy-review", PlanItemType.HUMAN_TASK, "Legacy review", null,
                false, true, false, List.of(), List.of(), null,
                null, List.of("handlers"), 20);
        new CaseDefinitionRepository(dataSource).insert(new CaseDefinition(
                definitionId, "production-plan", 1, "Production plan", TENANT,
                null, null, List.of("handlers"), List.of(), Map.of(),
                List.of(processTask, humanTask),
                OrchestrationMode.PLAN_MODEL, now, "publisher"));

        CaseDefinitionReleaseRepository releases =
                new CaseDefinitionReleaseRepository(dataSource);
        releases.insert(CaseDefinitionRelease.stored(
                "production-plan-orchestration", "production-plan", TENANT,
                ReleaseKind.ORCHESTRATION, "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                sha('d'), ReleaseStatus.ACTIVE, null, null, "publisher"));
        releases.insert(CaseDefinitionRelease.stored(
                "production-plan-contract", "production-plan", TENANT,
                ReleaseKind.CONTRACT, "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                sha('e'), ReleaseStatus.ACTIVE, null, null, "publisher"));
        releases.insert(CaseDefinitionRelease.stored(
                "production-plan-presentation", "production-plan", TENANT,
                ReleaseKind.PRESENTATION, "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                sha('f'), ReleaseStatus.ACTIVE, null, null, "publisher"));
        new CaseDefinitionVersionBindingRepository(dataSource).insert(
                new CaseDefinitionVersionBinding(definitionId, "production-plan", TENANT,
                        "production-plan-orchestration", sha('d'),
                        "production-plan-contract", sha('e'),
                        "production-plan-presentation", sha('f'), ReleaseStatus.ACTIVE,
                        OrchestrationMode.PLAN_MODEL, BindingStatus.ACTIVE, null, null,
                        now, now, null, "publisher"));
    }

    private void assertProjectedTask(String taskId, String caseId, String state, String assignee) {
        Map<String, Object> row = jdbc.sql("""
                SELECT CASE_ID_, STATE_, ASSIGNEE_ FROM CM_TASK
                WHERE CAMUNDA_TASK_ID_ = :taskId""")
                .param("taskId", taskId).query().singleRow();
        assertThat(row.get("CASE_ID_")).isEqualTo(caseId);
        assertThat(row.get("STATE_")).isEqualTo(state);
        assertThat(row.get("ASSIGNEE_")).isEqualTo(assignee);
    }

    private void assertPlanItem(String caseId, String type, String state) {
        assertThat(jdbc.sql("SELECT STATE_ FROM CM_PLAN_ITEM WHERE CASE_ID_ = :caseId "
                        + "AND TYPE_ = :type")
                .param("caseId", caseId).param("type", type)
                .query(String.class).single()).isEqualTo(state);
    }

    private int observationCount(String caseId, String kind, String eventType) {
        String sql = "SELECT COUNT(*) FROM CM_APPLIED_ENGINE_OBSERVATION WHERE CASE_ID_ = :caseId"
                + (kind == null ? "" : " AND OBSERVATION_KIND_ = :kind")
                + (eventType == null ? "" : " AND EVENT_TYPE_ = :eventType");
        var query = jdbc.sql(sql).param("caseId", caseId);
        if (kind != null) query = query.param("kind", kind);
        if (eventType != null) query = query.param("eventType", eventType);
        return query.query(Integer.class).single();
    }

    private int eventCount(String caseId, String type) {
        return jdbc.sql("SELECT COUNT(*) FROM CM_EVENT WHERE SUBJECT_ = :caseId "
                        + "AND TYPE_ LIKE :type")
                .param("caseId", caseId)
                .param("type", "%" + type)
                .query(Integer.class).single();
    }

    private int auditActionCount(String caseId, String action) {
        return jdbc.sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE CASE_ID_ = :caseId "
                        + "AND ACTION_ = :action")
                .param("caseId", caseId)
                .param("action", action)
                .query(Integer.class).single();
    }

    private int rowCount(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static byte[] contract() {
        return """
                {"key":"production-root","orchestrationMode":"BPMN",
                 "fields":{},"forms":{},"mappings":[]}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }
}
