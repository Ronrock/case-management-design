package org.casemgmt.observation;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.projection.ActivityObservation;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.JdbcCaseProjectionPort;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.ProcessProjectionResult;
import org.casemgmt.projection.ProjectionEntityIdentity;
import org.casemgmt.projection.ProjectionStatus;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.service.CanonicalPatch;
import org.casemgmt.service.CaseDataMappingService;
import org.casemgmt.service.ContractCaseDataMappingService;
import org.casemgmt.sla.SlaRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Oracle-backed proof that every accepted observation is one local transaction. */
class EngineObservationTransactionalIntegrationTest extends OracleTestBase {

    private static final String CASE_ID = "case-atomicity";
    private static final String TENANT_ID = "tenant-a";
    private static final String ENGINE_ID = "engine-a";
    private static final String PROCESS_INSTANCE_ID = "process-1";
    private static final String PROCESS_DEFINITION_ID = "claim-process:7:deployment-a";
    private static final String PROCESS_DEFINITION_KEY = "claim-process";
    private static final Instant OCCURRED = Instant.parse("2026-08-28T08:30:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-08-28T08:30:05Z");

    private AnnotationConfigApplicationContext context;
    private EngineObservationHandler handler;
    private FailureControl failures;
    private UserTaskObservation observation;

    @BeforeEach
    void setUp() {
        seedPublishedBpmnCase();
        context = springContext(AtomicityTestConfig.class);
        handler = context.getBean(EngineObservationHandler.class);
        failures = context.getBean(FailureControl.class);
        observation = completedTask("observation-1");
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @ParameterizedTest(name = "failure {0} rolls back every lifecycle table")
    @EnumSource(value = FailurePoint.class, mode = EnumSource.Mode.EXCLUDE,
            names = "AFTER_ROOT_TERMINAL")
    void aFailureAfterEachLifecycleEffectRollsBackEveryAffectedTable(FailurePoint point) {
        DatabaseState before = state();
        failures.failAfter(point);

        assertThatThrownBy(() -> handler.apply(observation))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(point.name());

        assertThat(state()).isEqualTo(before);
        assertThat(count("CM_APPLIED_ENGINE_OBSERVATION")).isZero();
    }

    @Test
    void duplicateReplayChangesNoVersionOrRowCountAndProducesNoSecondBusinessEffect() {
        ApplyResult first = handler.apply(observation);
        DatabaseState afterFirst = state();

        ApplyResult replay = handler.apply(completedTask("observation-redelivery"));

        assertThat(first.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(first.caseVersion()).isEqualTo(8);
        assertThat(first.eventIds()).hasSize(1);
        assertThat(replay).isEqualTo(new ApplyResult("observation-redelivery",
                ApplyStatus.DUPLICATE, ApplyResult.UNCHANGED_CASE_VERSION, List.of()));
        assertThat(state()).isEqualTo(afterFirst);
        assertThat(afterFirst).satisfies(committed -> {
            assertThat(committed.caseRow().variablesJson()).contains("approved");
            assertThat(committed.caseRow().version()).isEqualTo(8);
            assertThat(committed.planItem().state()).isEqualTo("COMPLETED");
            assertThat(committed.task().state()).isEqualTo("COMPLETED");
            assertThat(committed.sla().status()).isEqualTo("RUNNING");
            assertThat(committed.sla().version()).isEqualTo(1);
            assertThat(committed.appliedCount()).isEqualTo(1);
            assertThat(committed.appliedStatus()).isEqualTo("APPLIED");
            assertThat(committed.auditCount()).isEqualTo(1);
            assertThat(committed.eventCount()).isEqualTo(1);
            assertThat(committed.deliveryCount()).isEqualTo(1);
        });
    }

    @Test
    void rootCompletionAndSlaTerminalizationRollBackTogether() {
        DatabaseState before = state();
        failures.failAfter(FailurePoint.AFTER_ROOT_TERMINAL);

        assertThatThrownBy(() -> handler.apply(completedRootProcess()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(FailurePoint.AFTER_ROOT_TERMINAL.name());

        assertThat(state()).isEqualTo(before);
        assertThat(count("CM_APPLIED_ENGINE_OBSERVATION")).isZero();
    }

    private void seedPublishedBpmnCase() {
        OffsetDateTime now = OffsetDateTime.ofInstant(OCCURRED.minusSeconds(60), ZoneOffset.UTC);
        JdbcClient jdbc = jdbc();
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF
                  (ID_, KEY_, VERSION_NO_, TENANT_ID_, NAME_, ORCHESTRATION_MODE_)
                VALUES ('claim:1', 'claim', 1, :tenant, 'Claim', 'BPMN')""")
                .param("tenant", TENANT_ID).update();

        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                "deployment-a", PROCESS_DEFINITION_ID, PROCESS_DEFINITION_KEY, 7, TENANT_ID);
        CaseDefinitionReleaseRepository releases = new CaseDefinitionReleaseRepository(dataSource());
        releases.insert(CaseDefinitionRelease.storedWithEngineIdentity(
                "orchestration-1", "claim", TENANT_ID, ReleaseKind.ORCHESTRATION,
                "application/xml", "<definitions/>".getBytes(StandardCharsets.UTF_8),
                sha('a'), ReleaseStatus.ACTIVE, identity, null, "publisher"));
        releases.insert(CaseDefinitionRelease.stored(
                "contract-1", "claim", TENANT_ID, ReleaseKind.CONTRACT,
                "application/json", contract().getBytes(StandardCharsets.UTF_8),
                sha('b'), ReleaseStatus.ACTIVE, null, null, "publisher"));
        releases.insert(CaseDefinitionRelease.stored(
                "presentation-1", "claim", TENANT_ID, ReleaseKind.PRESENTATION,
                "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                sha('c'), ReleaseStatus.ACTIVE, null, null, "publisher"));
        new CaseDefinitionVersionBindingRepository(dataSource()).insert(
                new CaseDefinitionVersionBinding("claim:1", "claim", TENANT_ID,
                        "orchestration-1", sha('a'), "contract-1", sha('b'),
                        "presentation-1", sha('c'), ReleaseStatus.ACTIVE,
                        OrchestrationMode.BPMN, BindingStatus.ACTIVE, identity, null,
                        now, now, null, "publisher"));

        new CaseRepository(jdbc).insert(new CaseInstance(CASE_ID, ENGINE_ID, TENANT_ID,
                "claim:1", "claim", 1, "business-1", "Claim", CaseState.ACTIVE,
                CasePriority.MEDIUM, null, null, "starter", "NONE", null, null,
                Map.of("decision", "pending"), 7, now, now, null, null,
                ProjectionStatus.CURRENT, null, now));
        new LinkedProcessRepository(jdbc).insertRoot("root-link", CASE_ID,
                PROCESS_INSTANCE_ID, PROCESS_DEFINITION_ID, PROCESS_DEFINITION_KEY,
                CaseTask.EngineSync.SYNCED);

        jdbc.sql("""
                INSERT INTO CM_PLAN_ITEM
                  (ID_, CASE_ID_, TYPE_, NAME_, STATE_, AD_HOC_, REPETITION_NO_,
                   ENGINE_ACTIVITY_ID_, PROC_INST_ID_, VERSION_, CREATED_AT_, UPDATED_AT_,
                   PROJECTION_STATUS_)
                VALUES ('plan-task-1', :caseId, 'HUMAN_TASK', 'Review', 'ACTIVE', 0, 1,
                        'activity-1', :processId, 0, :createdAt, :createdAt, 'CURRENT')""")
                .param("caseId", CASE_ID).param("processId", PROCESS_INSTANCE_ID)
                .param("createdAt", now).update();
        jdbc.sql("""
                INSERT INTO CM_TASK
                  (ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_, STATE_, ASSIGNEE_,
                   CAND_GROUPS_JSON_, PRIORITY_, ENGINE_SYNC_, PROC_INST_ID_, VERSION_,
                   CREATED_AT_, UPDATED_AT_, PROJECTION_STATUS_)
                VALUES ('task-row-1', :caseId, 'plan-task-1', 'task-1', 'Review', 'CLAIMED',
                        'alice', '[]', 50, 'SYNCED', :processId, 0, :createdAt, :createdAt,
                        'CURRENT')""")
                .param("caseId", CASE_ID).param("processId", PROCESS_INSTANCE_ID)
                .param("createdAt", now).update();

        SlaRepository sla = new SlaRepository(jdbc);
        sla.insertCalendar("calendar-1", Map.of());
        sla.insertPolicy("policy-1", "Policy", null, "calendar-1");
        sla.insertTarget("target-1", "policy-1", "taskAnchor", "Task anchor",
                "PT1H", null, List.of(), List.of());
        sla.insertRecord(new SlaRecord("sla-1", CASE_ID, "target-1", "RUNNING", now,
                now.plusHours(1), null, null, null, 0, 0));
        new WebhookRepository(jdbc).insert("webhook-1", TENANT_ID,
                "http://localhost/atomicity", List.of("*"), "unused", 3);
    }

    private UserTaskObservation completedTask(String observationId) {
        return new UserTaskObservation(observationId, 1, "operaton:embedded", ENGINE_ID,
                TENANT_ID, CASE_ID, PROCESS_INSTANCE_ID, "task-1", 5L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED, RECEIVED, Map.of(
                "processDefinitionId", PROCESS_DEFINITION_ID,
                "processDefinitionKey", PROCESS_DEFINITION_KEY,
                "taskDefinitionKey", "reviewTask",
                "activityInstanceId", "activity-1",
                "name", "Review",
                "assignee", "alice",
                "variables", Map.of("decisionVar", "approved")));
    }

    private ProcessObservation completedRootProcess() {
        return new ProcessObservation("observation-root", 1, "operaton:embedded", ENGINE_ID,
                TENANT_ID, CASE_ID, PROCESS_INSTANCE_ID, PROCESS_INSTANCE_ID, 9L,
                ProcessObservation.EventType.COMPLETED, OCCURRED, RECEIVED, Map.of(
                "processDefinitionId", PROCESS_DEFINITION_ID,
                "processDefinitionKey", PROCESS_DEFINITION_KEY));
    }

    private DatabaseState state() {
        CaseRow caseRow = jdbc().sql("""
                SELECT VARIABLES_JSON_, STATE_, VERSION_, UPDATED_AT_, CLOSED_AT_,
                       LAST_ENGINE_UPDATE_AT_
                FROM CM_CASE WHERE ID_ = :caseId""").param("caseId", CASE_ID)
                .query((rs, row) -> new CaseRow(rs.getString("VARIABLES_JSON_"),
                        rs.getString("STATE_"), rs.getLong("VERSION_"),
                        rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                        rs.getObject("CLOSED_AT_", OffsetDateTime.class),
                        rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class))).single();
        ProjectionRow planItem = jdbc().sql("""
                SELECT STATE_, VERSION_, UPDATED_AT_, ENDED_AT_, LAST_ENGINE_UPDATE_AT_
                FROM CM_PLAN_ITEM WHERE ID_ = 'plan-task-1'""")
                .query((rs, row) -> new ProjectionRow(rs.getString("STATE_"),
                        rs.getLong("VERSION_"), rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                        rs.getObject("ENDED_AT_", OffsetDateTime.class),
                        rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class))).single();
        ProjectionRow task = jdbc().sql("""
                SELECT STATE_, VERSION_, UPDATED_AT_, COMPLETED_AT_, LAST_ENGINE_UPDATE_AT_
                FROM CM_TASK WHERE ID_ = 'task-row-1'""")
                .query((rs, row) -> new ProjectionRow(rs.getString("STATE_"),
                        rs.getLong("VERSION_"), rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                        rs.getObject("COMPLETED_AT_", OffsetDateTime.class),
                        rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class))).single();
        SlaRow sla = jdbc().sql("SELECT STATUS_, VERSION_ FROM CM_SLA_RECORD WHERE ID_ = 'sla-1'")
                .query((rs, row) -> new SlaRow(rs.getString("STATUS_"), rs.getLong("VERSION_")))
                .single();
        LinkedProcessRow linkedProcess = jdbc().sql("""
                SELECT STATE_, ENDED_AT_, LAST_ENGINE_UPDATE_AT_
                FROM CM_LINKED_PROCESS WHERE ID_ = 'root-link'""")
                .query((rs, row) -> new LinkedProcessRow(rs.getString("STATE_"),
                        rs.getObject("ENDED_AT_", OffsetDateTime.class),
                        rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class))).single();
        String appliedStatus = jdbc().sql("""
                SELECT STATUS_ FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE CASE_ID_ = :caseId""").param("caseId", CASE_ID)
                .query(String.class).optional().orElse(null);
        return new DatabaseState(caseRow, planItem, task, sla, linkedProcess,
                count("CM_APPLIED_ENGINE_OBSERVATION"), appliedStatus,
                count("CM_AUDIT_LOG"), count("CM_EVENT"), count("CM_WEBHOOK_DELIVERY"));
    }

    private int count(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static String contract() {
        return """
                {
                  "key":"claim",
                  "orchestrationMode":"BPMN",
                  "fields":{
                    "decision":{"schema":{"type":"string","enum":["pending","approved"]}}
                  },
                  "forms":{},
                  "mappings":[
                    {"direction":"ENGINE_TO_CASE","source":"decisionVar",
                     "target":"decision","type":"string","required":true}
                  ]
                }
                """;
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    enum FailurePoint {
        AFTER_PROJECTION,
        AFTER_CANONICAL_PATCH,
        AFTER_SLA,
        AFTER_AUDIT,
        AFTER_EVENT_INSERT,
        AFTER_WEBHOOK_DELIVERY,
        AFTER_ROOT_TERMINAL
    }

    static final class FailureControl {
        private FailurePoint selected;

        void failAfter(FailurePoint point) {
            selected = point;
        }

        void after(FailurePoint point) {
            if (selected == point) {
                throw new IllegalStateException("injected failure " + point.name());
            }
        }
    }

    record CaseRow(String variablesJson, String state, long version, OffsetDateTime updatedAt,
                   OffsetDateTime closedAt, OffsetDateTime lastEngineUpdateAt) { }

    record ProjectionRow(String state, long version, OffsetDateTime updatedAt,
                         OffsetDateTime terminalAt, OffsetDateTime lastEngineUpdateAt) { }

    record SlaRow(String status, long version) { }

    record LinkedProcessRow(String state, OffsetDateTime endedAt,
                            OffsetDateTime lastEngineUpdateAt) { }

    record DatabaseState(CaseRow caseRow, ProjectionRow planItem, ProjectionRow task, SlaRow sla,
                         LinkedProcessRow linkedProcess, int appliedCount, String appliedStatus,
                         int auditCount, int eventCount, int deliveryCount) { }

    @Configuration
    static class AtomicityTestConfig {

        @Bean
        FailureControl failureControl() {
            return new FailureControl();
        }

        @Bean
        CaseRepository caseRepository(DataSource dataSource) {
            return new CaseRepository(dataSource);
        }

        @Bean
        LinkedProcessRepository linkedProcesses(DataSource dataSource) {
            return new LinkedProcessRepository(JdbcClient.create(dataSource));
        }

        @Bean
        AppliedObservationRepository appliedObservations(DataSource dataSource) {
            return new AppliedObservationRepository(JdbcClient.create(dataSource));
        }

        @Bean
        CaseDefinitionVersionBindingRepository bindings(DataSource dataSource) {
            return new CaseDefinitionVersionBindingRepository(dataSource);
        }

        @Bean
        CaseDefinitionReleaseRepository releases(DataSource dataSource) {
            return new CaseDefinitionReleaseRepository(dataSource);
        }

        @Bean
        CaseProjectionPort projections(DataSource dataSource, FailureControl failures) {
            return new FailureInjectingProjection(
                    new JdbcCaseProjectionPort(JdbcClient.create(dataSource)), failures);
        }

        @Bean
        CaseDataMappingService mappings(CaseRepository cases,
                                        CaseDefinitionVersionBindingRepository bindings,
                                        CaseDefinitionReleaseRepository releases,
                                        FailureControl failures) {
            return new FailureInjectingMapping(new ContractCaseDataMappingService(
                    cases, bindings, releases, new JsonSchemaCaseContractValidator()), failures);
        }

        @Bean
        SlaLifecyclePort slaLifecycle(DataSource dataSource, FailureControl failures) {
            return new DatabaseSlaLifecyclePort(JdbcClient.create(dataSource), failures);
        }

        @Bean
        EventPublisher eventPublisher(DataSource dataSource, FailureControl failures) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new FailureInjectingPublisher(
                    new FailureInjectingEventRepository(jdbc, failures),
                    new AuditRepository(jdbc),
                    new FailureInjectingWebhookRepository(jdbc, failures),
                    failures);
        }

        @Bean
        EngineObservationAuthorityValidator authority(
                CaseDefinitionVersionBindingRepository bindings,
                LinkedProcessRepository processes) {
            return new DefaultEngineObservationAuthorityValidator(bindings, processes, ENGINE_ID);
        }

        @Bean
        EngineObservationHandler engineObservationHandler(
                AppliedObservationRepository claims, CaseRepository cases,
                LinkedProcessRepository processes, CaseProjectionPort projections,
                CaseDataMappingService mappings, EventPublisher events, SlaLifecyclePort sla,
                EngineObservationAuthorityValidator authority) {
            return new DefaultEngineObservationHandler(claims, cases, processes, projections,
                    mappings, events, sla, authority, ObservationSecurityTelemetry.none());
        }
    }

    static final class FailureInjectingProjection implements CaseProjectionPort {
        private final CaseProjectionPort delegate;
        private final FailureControl failures;

        FailureInjectingProjection(CaseProjectionPort delegate, FailureControl failures) {
            this.delegate = delegate;
            this.failures = failures;
        }

        @Override
        public void assertEntityOwnership(ProjectionEntityIdentity identity) {
            delegate.assertEntityOwnership(identity);
        }

        @Override
        public void observe(TaskObservation observation) {
            delegate.observe(observation);
            failures.after(FailurePoint.AFTER_PROJECTION);
        }

        @Override
        public void observe(ActivityObservation observation) {
            delegate.observe(observation);
            failures.after(FailurePoint.AFTER_PROJECTION);
        }

        @Override
        public void observe(ProcessCompletionObservation observation) {
            delegate.observe(observation);
            failures.after(FailurePoint.AFTER_PROJECTION);
        }

        @Override
        public ProcessProjectionResult observeFromHandler(ProcessCompletionObservation observation) {
            ProcessProjectionResult result = delegate.observeFromHandler(observation);
            failures.after(FailurePoint.AFTER_PROJECTION);
            return result;
        }
    }

    static final class FailureInjectingMapping implements CaseDataMappingService {
        private final CaseDataMappingService delegate;
        private final FailureControl failures;

        FailureInjectingMapping(CaseDataMappingService delegate, FailureControl failures) {
            this.delegate = delegate;
            this.failures = failures;
        }

        @Override
        public CanonicalPatch mapTaskOutput(String caseId, String taskDefinitionKey,
                                            Map<String, Object> engineVariables) {
            return delegate.mapTaskOutput(caseId, taskDefinitionKey, engineVariables);
        }

        @Override
        public PatchResult apply(CanonicalPatch patch) {
            PatchResult result = delegate.apply(patch);
            failures.after(FailurePoint.AFTER_CANONICAL_PATCH);
            return result;
        }
    }

    static final class DatabaseSlaLifecyclePort implements SlaLifecyclePort {
        private final JdbcClient jdbc;
        private final FailureControl failures;

        DatabaseSlaLifecyclePort(JdbcClient jdbc, FailureControl failures) {
            this.jdbc = jdbc;
            this.failures = failures;
        }

        @Override
        public void observeAnchor(Anchor anchor) {
            int updated = jdbc.sql("""
                    UPDATE CM_SLA_RECORD SET VERSION_ = VERSION_ + 1
                    WHERE CASE_ID_ = :caseId""").param("caseId", anchor.caseId()).update();
            if (updated != 1) {
                throw new IllegalStateException("expected one SLA record");
            }
            failures.after(FailurePoint.AFTER_SLA);
        }

        @Override
        public void terminalizeRoot(String caseId, TerminalState state, Instant occurredAt) {
            int updated = jdbc.sql("""
                    UPDATE CM_SLA_RECORD SET STATUS_ = 'MET', VERSION_ = VERSION_ + 1
                    WHERE CASE_ID_ = :caseId""")
                    .param("caseId", caseId).update();
            if (updated != 1) {
                throw new IllegalStateException("expected one SLA record");
            }
            failures.after(FailurePoint.AFTER_ROOT_TERMINAL);
        }
    }

    static final class FailureInjectingPublisher extends EventPublisher {
        private final FailureControl failures;

        FailureInjectingPublisher(EventRepository events, AuditRepository audit,
                                  WebhookRepository webhooks, FailureControl failures) {
            super(events, audit, webhooks, "org.example.cm", ENGINE_ID);
            this.failures = failures;
        }

        @Override
        public void audit(String caseId, String tenantId, String actor, String action,
                          String resourceType, String resourceId, Object before, Object after) {
            super.audit(caseId, tenantId, actor, action, resourceType, resourceId, before, after);
            failures.after(FailurePoint.AFTER_AUDIT);
        }
    }

    static final class FailureInjectingEventRepository extends EventRepository {
        private final FailureControl failures;

        FailureInjectingEventRepository(JdbcClient jdbc, FailureControl failures) {
            super(jdbc);
            this.failures = failures;
        }

        @Override
        public long append(CaseEvent event) {
            long sequence = super.append(event);
            failures.after(FailurePoint.AFTER_EVENT_INSERT);
            return sequence;
        }
    }

    static final class FailureInjectingWebhookRepository extends WebhookRepository {
        private final FailureControl failures;

        FailureInjectingWebhookRepository(JdbcClient jdbc, FailureControl failures) {
            super(jdbc);
            this.failures = failures;
        }

        @Override
        public void enqueueDelivery(String id, String webhookId, long eventSeq) {
            super.enqueueDelivery(id, webhookId, eventSeq);
            failures.after(FailurePoint.AFTER_WEBHOOK_DELIVERY);
        }
    }
}
