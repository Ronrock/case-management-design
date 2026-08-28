package org.casemgmt.starter;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.engine.embedded.EmbeddedTransactionResourceValidator;
import org.casemgmt.observation.SlaLifecyclePort;
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
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
        @Bean
        FailingSlaLifecyclePort failingSlaLifecyclePort() {
            return new FailingSlaLifecyclePort();
        }
    }

    static final class FailingSlaLifecyclePort implements SlaLifecyclePort {
        private volatile boolean failCreatedTask;
        private volatile boolean failCompletedTask;

        @Override
        public void observeAnchor(Anchor anchor) {
            if ("user-task".equals(anchor.observationKind())
                    && ((failCreatedTask && "CREATED".equals(anchor.eventType()))
                    || (failCompletedTask && "COMPLETED".equals(anchor.eventType())))) {
                throw new IllegalStateException("injected lifecycle persistence failure");
            }
        }

        @Override
        public void terminalizeRoot(String caseId, TerminalState state,
                                    java.time.Instant occurredAt) { }
    }

    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired CaseRepository cases;
    @Autowired LinkedProcessRepository processes;
    @Autowired LinkedProcessService linkedProcesses;
    @Autowired FailingSlaLifecyclePort failures;
    @Autowired EmbeddedTransactionResourceValidator transactionResourceValidator;
    @Autowired @Qualifier("caseManagementCaseService") CaseService caseService;

    private ProcessDefinition rootDefinition;
    private ProcessDefinition childDefinition;

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
        publishCaseDefinition(rootDefinition);
    }

    @BeforeEach
    void resetFailure() {
        failures.failCreatedTask = false;
        failures.failCompletedTask = false;
    }

    @Test
    void caseCreateAndLinkedChildUseProductionAuthorityAndLifecycleEffects() {
        assertThat(transactionResourceValidator).isNotNull();
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

        tasks.complete(childTask.getId());

        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.ACTIVE);
        assertThat(processes.findByProcessInstanceId(child.processInstanceId()).orElseThrow().state())
                .isEqualTo("COMPLETED");

        tasks.complete(rootTask.getId());

        assertThat(cases.require(created.id()).state()).isEqualTo(CaseState.CLOSED);
        assertThat(processes.findByProcessInstanceId(created.rootProcessInstanceId())
                .orElseThrow().state()).isEqualTo("COMPLETED");
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

    private void assertProjectedTask(String taskId, String caseId, String state, String assignee) {
        Map<String, Object> row = jdbc.sql("""
                SELECT CASE_ID_, STATE_, ASSIGNEE_ FROM CM_TASK
                WHERE CAMUNDA_TASK_ID_ = :taskId""")
                .param("taskId", taskId).query().singleRow();
        assertThat(row.get("CASE_ID_")).isEqualTo(caseId);
        assertThat(row.get("STATE_")).isEqualTo(state);
        assertThat(row.get("ASSIGNEE_")).isEqualTo(assignee);
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
