package org.casemgmt.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
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
    private static final ObjectMapper STORED_JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

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
        if (context != null) {
            context.close();
        }
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
        assertThat(state().appliedRows()).isEmpty();
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
            assertThat(committed.caseRows()).singleElement().satisfies(row -> {
                assertThat(row.get("VARIABLES_JSON_").toString()).contains("approved");
                assertThat(row.get("VERSION_")).isEqualTo("8");
            });
            assertThat(committed.planItemRows()).singleElement()
                    .extracting(row -> row.get("STATE_")).isEqualTo("COMPLETED");
            assertThat(committed.taskRows()).singleElement()
                    .extracting(row -> row.get("STATE_")).isEqualTo("COMPLETED");
            assertThat(committed.slaRows()).singleElement().satisfies(row -> {
                assertThat(row.get("STATUS_")).isEqualTo("RUNNING");
                assertThat(row.get("VERSION_")).isEqualTo("1");
            });
            assertThat(committed.appliedRows()).singleElement().satisfies(row -> {
                assertThat(row.keySet()).containsExactlyInAnyOrder(
                        "OBSERVATION_ID_", "TENANT_ID_", "FINGERPRINT_", "CLAIM_TOKEN_",
                        "STATUS_", "SOURCE_", "ENGINE_ID_", "CASE_ID_",
                        "PROCESS_INSTANCE_ID_", "ENTITY_ID_", "ENTITY_REVISION_",
                        "EVENT_TYPE_", "ENGINE_OCCURRED_AT_", "CLAIMED_AT_", "APPLIED_AT_",
                        "FAILED_AT_", "FAILURE_DETAIL_", "OBSERVATION_KIND_", "IGNORED_AT_");
                assertThat(row.get("OBSERVATION_ID_")).isEqualTo("observation-1");
                assertThat(row.get("FINGERPRINT_")).isEqualTo(observation.fingerprint());
                assertThat(row.get("CLAIM_TOKEN_").toString())
                        .matches("[A-Za-z0-9_-]{43}");
                assertThat(row.get("STATUS_")).isEqualTo("APPLIED");
                assertThat(row.get("OBSERVATION_KIND_")).isEqualTo("USER_TASK");
                assertThat(row.get("ENTITY_REVISION_")).isEqualTo("5");
                assertThat(row.get("ENGINE_OCCURRED_AT_")).isEqualTo(OCCURRED.toString());
                assertThat(row.get("ENGINE_ID_")).isEqualTo(ENGINE_ID);
                assertThat(row.get("SOURCE_")).isEqualTo("operaton:embedded");
                assertThat(row.get("TENANT_ID_")).isEqualTo(TENANT_ID);
                assertThat(row.get("CASE_ID_")).isEqualTo(CASE_ID);
                assertThat(row.get("PROCESS_INSTANCE_ID_")).isEqualTo(PROCESS_INSTANCE_ID);
                assertThat(row.get("ENTITY_ID_")).isEqualTo("task-1");
                assertThat(row.get("EVENT_TYPE_")).isEqualTo("COMPLETED");
                assertThat(row.get("CLAIMED_AT_")).isNotEqualTo(NullValue.INSTANCE);
                assertThat(row.get("APPLIED_AT_")).isNotEqualTo(NullValue.INSTANCE);
                assertThat(row.get("FAILED_AT_")).isEqualTo(NullValue.INSTANCE);
                assertThat(row.get("FAILURE_DETAIL_")).isEqualTo(NullValue.INSTANCE);
                assertThat(row.get("IGNORED_AT_")).isEqualTo(NullValue.INSTANCE);
            });
            assertThat(committed.auditRows()).hasSize(1);
            assertThat(committed.eventRows()).hasSize(1);
            assertThat(committed.deliveryRows()).hasSize(1);
        });
    }

    @Test
    void directAndStoredObservationProduceTheSameCommittedDatabaseOutcome() throws Exception {
        applyAndCommit(observation);
        DatabaseState direct = canonicalCommittedOutcome(state());
        assertThat(direct.eventRows()).hasSize(1);
        assertThat(direct.deliveryRows()).hasSize(1);

        resetMutableCaseFixture();

        byte[] stored = STORED_JSON.writeValueAsBytes(observation);
        UserTaskObservation restored = STORED_JSON.readValue(stored,
                UserTaskObservation.class);
        applyAndCommit(restored);
        DatabaseState serialized = canonicalCommittedOutcome(state());

        assertThat(restored).isEqualTo(observation);
        assertThat(serialized).isEqualTo(direct);
    }

    private void applyAndCommit(EngineObservation value) {
        TransactionTemplate transaction = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> handler.apply(value));
    }

    @Test
    void rootCompletionAndSlaTerminalizationRollBackTogether() {
        DatabaseState before = state();
        failures.failAfter(FailurePoint.AFTER_ROOT_TERMINAL);

        assertThatThrownBy(() -> handler.apply(completedRootProcess()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(FailurePoint.AFTER_ROOT_TERMINAL.name());

        assertThat(state()).isEqualTo(before);
        assertThat(state().appliedRows()).isEmpty();
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

        SlaRepository sla = new SlaRepository(jdbc);
        sla.insertCalendar("calendar-1", Map.of());
        sla.insertPolicy("policy-1", "Policy", null, "calendar-1");
        sla.insertTarget("target-1", "policy-1", "taskAnchor", "Task anchor",
                "PT1H", null, List.of(), List.of());
        new WebhookRepository(jdbc).insert("webhook-1", TENANT_ID,
                "http://localhost/atomicity", List.of("*"), "unused", 3);
        seedMutableCaseFixture(now);
    }

    private void resetMutableCaseFixture() {
        JdbcClient jdbc = jdbc();
        jdbc.sql("""
                DELETE FROM CM_WEBHOOK_DELIVERY WHERE EVENT_SEQ_ IN
                  (SELECT SEQ_ FROM CM_EVENT WHERE SUBJECT_ = :caseId)""")
                .param("caseId", CASE_ID).update();
        for (String table : List.of("CM_EVENT", "CM_AUDIT_LOG",
                "CM_APPLIED_ENGINE_OBSERVATION", "CM_MILESTONE", "CM_TASK",
                "CM_PLAN_ITEM", "CM_SLA_RECORD", "CM_LINKED_PROCESS")) {
            String column = "CM_EVENT".equals(table) ? "SUBJECT_" : "CASE_ID_";
            jdbc.sql("DELETE FROM " + table + " WHERE " + column + " = :caseId")
                    .param("caseId", CASE_ID).update();
        }
        jdbc.sql("DELETE FROM CM_CASE WHERE ID_ = :caseId")
                .param("caseId", CASE_ID).update();
        seedMutableCaseFixture(
                OffsetDateTime.ofInstant(OCCURRED.minusSeconds(60), ZoneOffset.UTC));
    }

    private void seedMutableCaseFixture(OffsetDateTime now) {
        JdbcClient jdbc = jdbc();
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
        new SlaRepository(jdbc).insertRecord(new SlaRecord(
                "sla-1", CASE_ID, "target-1", "RUNNING", now,
                now.plusHours(1), null, null, null, 0, 0));
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
        return new DatabaseState(
                tableRows("CM_CASE", "ID_", CASE_ID, "ID_"),
                tableRows("CM_PLAN_ITEM", "CASE_ID_", CASE_ID, "ID_"),
                tableRows("CM_TASK", "CASE_ID_", CASE_ID, "ID_"),
                tableRows("CM_LINKED_PROCESS", "CASE_ID_", CASE_ID, "ID_"),
                tableRows("CM_SLA_RECORD", "CASE_ID_", CASE_ID, "ID_"),
                tableRows("CM_APPLIED_ENGINE_OBSERVATION", "CASE_ID_", CASE_ID,
                        "CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END, "
                                + "TENANT_ID_, FINGERPRINT_"),
                tableRows("CM_AUDIT_LOG", "CASE_ID_", CASE_ID, "ID_"),
                tableRows("CM_EVENT", "SUBJECT_", CASE_ID, "SEQ_"),
                deliveryRowsForCase());
    }

    private static DatabaseState canonicalCommittedOutcome(DatabaseState state) {
        assertTimestampSemantics(state);
        Map<Object, Object> eventOrdinals = new LinkedHashMap<>();
        for (int index = 0; index < state.eventRows().size(); index++) {
            eventOrdinals.put(state.eventRows().get(index).get("SEQ_"), "event-" + (index + 1));
        }
        List<Map<String, Object>> events = state.eventRows().stream().map(row ->
                replacing(row, Map.of(
                        "SEQ_", eventOrdinals.get(row.get("SEQ_")),
                        "ID_", "generated-event-id"))).toList();
        List<Map<String, Object>> deliveries = state.deliveryRows().stream().map(row -> {
            Object eventOrdinal = eventOrdinals.get(row.get("EVENT_SEQ_"));
            assertThat(eventOrdinal)
                    .as("delivery EVENT_SEQ_ must reference a captured committed event")
                    .isNotNull();
            return replacing(row, Map.of(
                    "ID_", "generated-delivery-id",
                    "EVENT_SEQ_", eventOrdinal,
                    "NEXT_ATTEMPT_AT_", "present-at-commit"));
        }).toList();
        return new DatabaseState(
                replaceColumn(state.caseRows(), "UPDATED_AT_", "after-created-at"),
                state.planItemRows(),
                state.taskRows(),
                state.linkedProcessRows(),
                state.slaRows(),
                state.appliedRows().stream().map(row -> replacing(row, Map.of(
                        "CLAIM_TOKEN_", "generated-claim-token",
                        "CLAIMED_AT_", "claimed-before-applied",
                        "APPLIED_AT_", "applied-after-claim"))).toList(),
                state.auditRows().stream().map(row -> replacing(row, Map.of(
                        "ID_", "generated-audit-id",
                        "TS_", "present-at-commit"))).toList(),
                events,
                deliveries);
    }

    private static void assertTimestampSemantics(DatabaseState state) {
        java.util.stream.Stream.of(state.caseRows(), state.planItemRows(), state.taskRows())
                .flatMap(List::stream)
                .forEach(row -> {
                    assertThat(row.get("CREATED_AT_")).isNotEqualTo(NullValue.INSTANCE);
                    assertThat(row.get("UPDATED_AT_")).isNotEqualTo(NullValue.INSTANCE);
                    assertThat(Instant.parse(row.get("UPDATED_AT_").toString()))
                            .isAfterOrEqualTo(Instant.parse(row.get("CREATED_AT_").toString()));
                });
        state.appliedRows().forEach(row -> {
            assertThat(row.get("CLAIM_TOKEN_")).isNotEqualTo(NullValue.INSTANCE);
            assertThat(row.get("CLAIMED_AT_")).isNotEqualTo(NullValue.INSTANCE);
            assertThat(row.get("APPLIED_AT_")).isNotEqualTo(NullValue.INSTANCE);
            assertThat(Instant.parse(row.get("APPLIED_AT_").toString()))
                    .isAfterOrEqualTo(Instant.parse(row.get("CLAIMED_AT_").toString()));
            assertThat(row.get("FAILED_AT_")).isEqualTo(NullValue.INSTANCE);
            assertThat(row.get("IGNORED_AT_")).isEqualTo(NullValue.INSTANCE);
        });
        state.auditRows().forEach(row ->
                assertThat(row.get("TS_")).isNotEqualTo(NullValue.INSTANCE));
        state.eventRows().forEach(row ->
                assertThat(row.get("TIME_")).isEqualTo(OCCURRED.toString()));
        state.deliveryRows().forEach(row -> {
            assertThat(row.get("NEXT_ATTEMPT_AT_")).isNotEqualTo(NullValue.INSTANCE);
            assertThat(row.get("CLAIM_TOKEN_")).isEqualTo(NullValue.INSTANCE);
            assertThat(row.get("CLAIMED_AT_")).isEqualTo(NullValue.INSTANCE);
            assertThat(row.get("DELIVERED_AT_")).isEqualTo(NullValue.INSTANCE);
            assertThat(row.get("FAILED_AT_")).isEqualTo(NullValue.INSTANCE);
        });
    }

    private static List<Map<String, Object>> replaceColumn(
            List<Map<String, Object>> rows, String column, Object replacement) {
        return rows.stream().map(row -> replacing(row, Map.of(column, replacement))).toList();
    }

    private static Map<String, Object> replacing(
            Map<String, Object> row, Map<String, Object> replacements) {
        Map<String, Object> canonical = new LinkedHashMap<>(row);
        canonical.putAll(replacements);
        return Map.copyOf(canonical);
    }

    private List<Map<String, Object>> tableRows(String table, String filterColumn,
                                                String filterValue, String orderBy) {
        return jdbc().sql("SELECT * FROM " + table + " WHERE " + filterColumn
                        + " = :filterValue ORDER BY " + orderBy)
                .param("filterValue", filterValue)
                .query((rs, row) -> completeRow(rs))
                .list();
    }

    private List<Map<String, Object>> deliveryRowsForCase() {
        return jdbc().sql("""
                SELECT delivery.*
                FROM CM_WEBHOOK_DELIVERY delivery
                JOIN CM_EVENT event ON event.SEQ_ = delivery.EVENT_SEQ_
                WHERE event.SUBJECT_ = :caseId
                ORDER BY delivery.EVENT_SEQ_, delivery.ID_""")
                .param("caseId", CASE_ID)
                .query((rs, row) -> completeRow(rs))
                .list();
    }

    /** Converts every Oracle column to a stable, content-based value for exact snapshots. */
    private static Map<String, Object> completeRow(ResultSet rs) throws SQLException {
        var metadata = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            row.put(metadata.getColumnLabel(index), stableValue(rs, index,
                    metadata.getColumnType(index)));
        }
        return Map.copyOf(row);
    }

    private static Object stableValue(ResultSet rs, int index, int jdbcType) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null) return NullValue.INSTANCE;
        if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB) {
            return rs.getString(index);
        }
        if (jdbcType == Types.BLOB || value instanceof byte[]) {
            byte[] bytes = value instanceof byte[] raw ? raw : rs.getBytes(index);
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL) {
            return rs.getBigDecimal(index).stripTrailingZeros().toPlainString();
        }
        // Oracle exposes TIMESTAMP WITH TIME ZONE as -101; JDBC 4.2 standardises it as 2014.
        if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || jdbcType == -101) {
            return rs.getObject(index, OffsetDateTime.class).toInstant().toString();
        }
        if (jdbcType == Types.TIMESTAMP) {
            return rs.getTimestamp(index).toInstant().toString();
        }
        return value;
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

    enum NullValue { INSTANCE }

    record DatabaseState(List<Map<String, Object>> caseRows,
                         List<Map<String, Object>> planItemRows,
                         List<Map<String, Object>> taskRows,
                         List<Map<String, Object>> linkedProcessRows,
                         List<Map<String, Object>> slaRows,
                         List<Map<String, Object>> appliedRows,
                         List<Map<String, Object>> auditRows,
                         List<Map<String, Object>> eventRows,
                         List<Map<String, Object>> deliveryRows) { }

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
