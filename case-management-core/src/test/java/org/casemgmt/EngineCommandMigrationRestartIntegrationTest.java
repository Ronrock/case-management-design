package org.casemgmt;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Oracle proof for legacy command mapping and restart-safe production command DDL. */
class EngineCommandMigrationRestartIntegrationTest extends OracleTestBase {

    private static final String MASTER = "db/changelog/db.changelog-master.xml";
    private static final String CHANGELOG = "db/changelog/cm-production-engine-command.xml";
    private static final String SCHEMA = "WS4_COMMAND_RESTART";
    private static final String PASSWORD = "Ws4Command42";
    private static final List<String> EXPECTED_CHANGESETS = List.of(
            "cm-production-engine-command-columns-guard",
            "cm-production-engine-command-columns",
            "cm-production-engine-command-backfill",
            "cm-production-engine-command-required",
            "cm-production-engine-command-status-guard",
            "cm-production-engine-command-drop-poc-status",
            "cm-production-engine-command-new-status-guard",
            "cm-production-engine-command-status",
            "cm-engine-command-action-table-guard",
            "cm-engine-command-action-table",
            "cm-production-engine-command-invariants-guard",
            "cm-production-engine-command-counter-invariants",
            "cm-production-engine-command-lease-invariants",
            "cm-engine-command-action-invariants",
            "cm-production-engine-command-objects-guard",
            "cm-engine-command-action-fk",
            "cm-engine-command-action-id-unique",
            "cm-engine-command-action-seq-unique",
            "cm-engine-command-operation-unique",
            "cm-engine-command-idempotency-unique",
            "cm-engine-command-due-index",
            "cm-engine-command-lease-index",
            "cm-engine-command-case-status-index",
            "cm-engine-command-review-index");
    private static String jdbcUrl;

    @BeforeAll
    static void captureOracleUrl() throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (jdbcUrl != null) {
            try (Connection system = DriverManager.getConnection(jdbcUrl, "system", "cm")) {
                dropSchema(system);
            }
        }
    }

    @Test
    void mapsEveryPocStatusDeterministicallyAndRetainsRawHistoricalEvidence() throws Exception {
        DataSource scenario = recreateBaseline();
        JdbcClient jdbc = JdbcClient.create(scenario);
        seedLegacyRows(jdbc);

        migrate(scenario);
        List<String> first = mappedRows(jdbc);
        migrate(scenario);

        assertThat(first).containsExactly(
                "claimed|AWAITING_CONFIRMATION|3|3|CLAIMED|old-claimed|boom-claimed|-|-|-|old-token|Y",
                "dead|FAILED|4|4|DEAD|old-dead|boom-dead|-|-|-|-|N",
                "done|CONFIRMED|4|4|DONE|old-done|boom-done|done|DONE|3|-|N",
                "pending|PENDING|0|0|PENDING|old-pending|boom-pending|-|-|-|-|N",
                "retrying|RETRYABLE|1|1|RETRYING|old-retrying|boom-retrying|-|-|-|-|N");
        assertThat(mappedRows(jdbc)).containsExactlyElementsOf(first);
        var rehydratedDone = new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", "done");
        assertThat(rehydratedDone.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThat(rehydratedDone.state().committedDecision().totalDispatchAttempts())
                .isEqualTo(4);
        assertThat(appliedChangeSets(jdbc)).containsExactlyElementsOf(EXPECTED_CHANGESETS);
        assertFinalSchema(jdbc);

        jdbc.sql("UPDATE CM_ENGINE_COMMAND SET LEGACY_MIGRATION_REF_='forged' WHERE ID_='done'")
                .update();
        assertThatThrownBy(() -> new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", "done"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy DONE provenance");
    }

    @ParameterizedTest(name = "resumes after {0} production changesets")
    @ValueSource(ints = {0, 1, 2, 3, 6, 9, 13, 18, 24})
    void resumesAndRerunsAfterEveryRepresentativeOracleDdlPrefix(int prefix) throws Exception {
        DataSource scenario = recreateBaseline();
        updateNext(scenario, prefix);

        migrate(scenario);
        assertFinalSchema(JdbcClient.create(scenario));
        migrate(scenario);
        assertThat(appliedChangeSets(JdbcClient.create(scenario)))
                .containsExactlyElementsOf(EXPECTED_CHANGESETS);
    }

    @ParameterizedTest(name = "malformed same-named {0} halts")
    @EnumSource(MalformedObject.class)
    void malformedSameNamedProductionObjectsHaltWithoutRecordingTheirGuard(
            MalformedObject malformed) throws Exception {
        DataSource scenario = recreateBaseline();
        updateNext(scenario, malformed.prefix);
        JdbcClient jdbc = JdbcClient.create(scenario);
        installMalformed(jdbc, malformed);

        assertThatThrownBy(() -> migrate(scenario))
                .hasStackTraceContaining("incompatible");
        assertThat(appliedChangeSets(jdbc)).doesNotContain(malformed.guard);
    }

    private static void installMalformed(JdbcClient jdbc, MalformedObject malformed) {
        switch (malformed) {
            case STATUS_CONSTRAINT -> {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND DROP CONSTRAINT CK_CM_ENGCMD_STATUS")
                        .update();
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT CK_CM_ENGCMD_STATUS "
                        + "CHECK (STATUS_ IN ('PENDING'))").update();
            }
            case ACTION_TABLE -> jdbc.sql("CREATE TABLE CM_ENGINE_COMMAND_ACTION "
                    + "(COMMAND_ID_ VARCHAR2(64) NOT NULL)").update();
            default -> jdbc.sql(malformed.ddl).update();
        }
    }

    private enum MalformedObject {
        PRODUCTION_COLUMNS(0, "ALTER TABLE CM_ENGINE_COMMAND ADD OPERATION_ID_ VARCHAR2(10)",
                "cm-production-engine-command-columns-guard"),
        STATUS_CONSTRAINT(0, null, "cm-production-engine-command-status-guard"),
        ACTION_TABLE(0, null, "cm-engine-command-action-table-guard"),
        COUNTER_CONSTRAINT(10, "ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT "
                + "CK_CM_ENGCMD_COUNTERS CHECK (TOTAL_DISPATCH_ATTEMPTS_ >= 0)",
                "cm-production-engine-command-invariants-guard"),
        LEASE_CONSTRAINT(10, "ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT "
                + "CK_CM_ENGCMD_LEASE CHECK (LEASE_TOKEN_ IS NULL)",
                "cm-production-engine-command-invariants-guard"),
        ACTION_CONSTRAINT(10, "ALTER TABLE CM_ENGINE_COMMAND_ACTION ADD CONSTRAINT "
                + "CK_CM_ECA_INVARIANTS CHECK (SEQUENCE_ > 0)",
                "cm-production-engine-command-invariants-guard"),
        ACTION_FK(10, "ALTER TABLE CM_ENGINE_COMMAND_ACTION ADD CONSTRAINT FK_CM_ECA_COMMAND "
                + "FOREIGN KEY (OPERATION_ID_) REFERENCES CM_ENGINE_COMMAND(ID_)",
                "cm-production-engine-command-objects-guard"),
        ACTION_ID_INDEX(10, "CREATE UNIQUE INDEX UQ_CM_ECA_ACTION "
                + "ON CM_ENGINE_COMMAND_ACTION(ACTION_ID_)",
                "cm-production-engine-command-objects-guard"),
        ACTION_SEQUENCE_INDEX(10, "CREATE UNIQUE INDEX UQ_CM_ECA_SEQUENCE "
                + "ON CM_ENGINE_COMMAND_ACTION(SEQUENCE_)",
                "cm-production-engine-command-objects-guard"),
        OPERATION_INDEX(10, "CREATE INDEX UQ_CM_ENGCMD_OPERATION "
                + "ON CM_ENGINE_COMMAND(TENANT_ID_, OPERATION_ID_)",
                "cm-production-engine-command-objects-guard"),
        IDEMPOTENCY_INDEX(10, "CREATE UNIQUE INDEX UQ_CM_ENGCMD_IDEMPOTENCY "
                + "ON CM_ENGINE_COMMAND(TENANT_ID_, OPERATION_ID_)",
                "cm-production-engine-command-objects-guard"),
        DUE_INDEX(10, "CREATE INDEX IX_CM_ENGCMD_PROD_DUE ON CM_ENGINE_COMMAND(STATUS_)",
                "cm-production-engine-command-objects-guard"),
        LEASE_INDEX(10, "CREATE INDEX IX_CM_ENGCMD_LEASE ON CM_ENGINE_COMMAND(LEASE_EXPIRES_AT_)",
                "cm-production-engine-command-objects-guard"),
        CASE_STATUS_INDEX(10, "CREATE INDEX IX_CM_ENGCMD_CASE_STATUS "
                + "ON CM_ENGINE_COMMAND(CASE_ID_, STATUS_)",
                "cm-production-engine-command-objects-guard"),
        REVIEW_INDEX(10, "CREATE INDEX IX_CM_ENGCMD_REVIEW ON CM_ENGINE_COMMAND(UPDATED_AT_)",
                "cm-production-engine-command-objects-guard");

        private final int prefix;
        private final String ddl;
        private final String guard;

        MalformedObject(int prefix, String ddl, String guard) {
            this.prefix = prefix;
            this.ddl = ddl;
            this.guard = guard;
        }
    }

    private static DataSource recreateBaseline() throws Exception {
        try (Connection system = DriverManager.getConnection(jdbcUrl, "system", "cm")) {
            dropSchema(system);
            try (Statement statement = system.createStatement()) {
                statement.execute("CREATE USER " + SCHEMA + " IDENTIFIED BY \"" + PASSWORD
                        + "\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
                statement.execute("GRANT CREATE SESSION, RESOURCE TO " + SCHEMA);
            }
        }
        DataSource scenario = new DriverManagerDataSource(jdbcUrl, SCHEMA, PASSWORD);
        try (Connection connection = scenario.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    MASTER, new ClassLoaderResourceAccessor(), database)) {
                int beforeProduction = 0;
                for (var changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
                    if (EXPECTED_CHANGESETS.getFirst().equals(changeSet.getId())) break;
                    beforeProduction++;
                }
                assertThat(beforeProduction).isPositive();
                liquibase.update(beforeProduction, new Contexts(), new LabelExpression());
            }
        }
        return scenario;
    }

    private static void seedLegacyRows(JdbcClient jdbc) {
        insertLegacy(jdbc, "pending", "PENDING", 0);
        insertLegacy(jdbc, "retrying", "RETRYING", 1);
        insertLegacy(jdbc, "claimed", "CLAIMED", 2);
        insertLegacy(jdbc, "done", "DONE", 3);
        insertLegacy(jdbc, "dead", "DEAD", 4);
    }

    private static void insertLegacy(JdbcClient jdbc, String id, String status, int attempts) {
        jdbc.sql("""
                INSERT INTO CM_ENGINE_COMMAND
                  (ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_,
                   NEXT_ATTEMPT_AT_, LAST_ERROR_, CREATED_AT_, CLAIM_TOKEN_, CLAIMED_AT_)
                VALUES (:id, 'case-a', 'COMPLETE_TASK', :payload, :status, :attempts,
                        SYSTIMESTAMP, :error, SYSTIMESTAMP,
                        CASE WHEN :status='CLAIMED' THEN 'old-token' END,
                        CASE WHEN :status='CLAIMED' THEN SYSTIMESTAMP END)
                """).param("id", id).param("payload", "{\"raw\":\"old-" + id + "\"}")
                .param("status", status).param("attempts", attempts)
                .param("error", "boom-" + id).update();
    }

    private static List<String> mappedRows(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT ID_ || '|' || STATUS_ || '|' || TOTAL_DISPATCH_ATTEMPTS_ || '|' ||
                       AUTO_ATTEMPTS_ || '|' || ORIGINAL_STATUS_ || '|' ||
                       JSON_VALUE(RAW_LEGACY_PAYLOAD_, '$.raw') || '|' || RAW_LEGACY_ERROR_ || '|' ||
                       NVL(LEGACY_ROW_ID_, '-') || '|' || NVL(LEGACY_STATUS_, '-') || '|' ||
                       NVL(TO_CHAR(LEGACY_FAILURE_COUNT_), '-') || '|' ||
                       NVL(RAW_LEGACY_CLAIM_TOKEN_, '-') || '|' ||
                       CASE WHEN RAW_LEGACY_CLAIMED_AT_ IS NULL THEN 'N' ELSE 'Y' END AS SNAPSHOT
                FROM CM_ENGINE_COMMAND ORDER BY ID_
                """).query(String.class).list();
    }

    private static void assertFinalSchema(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME='CM_ENGINE_COMMAND'
                  AND COLUMN_NAME IN ('OPERATION_ID_','TENANT_ID_','IDEMPOTENCY_KEY_',
                    'PAYLOAD_DIGEST_','LEASE_TOKEN_','LEASE_OWNER_','LEASE_EXPIRES_AT_',
                    'TOTAL_DISPATCH_ATTEMPTS_','AUTO_ATTEMPTS_','BUDGET_EPOCH_','ROW_VERSION_',
                    'ACTION_COUNT_','ACTION_HIGH_WATER_','LEGACY_STATUS_','RAW_LEGACY_PAYLOAD_',
                    'RAW_LEGACY_CLAIM_TOKEN_','RAW_LEGACY_CLAIMED_AT_')
                ORDER BY COLUMN_NAME
                """).query(String.class).list()).hasSize(17);
        assertThat(jdbc.sql("""
                SELECT INDEX_NAME FROM USER_INDEXES WHERE INDEX_NAME IN
                  ('UQ_CM_ENGCMD_OPERATION','UQ_CM_ENGCMD_IDEMPOTENCY','IX_CM_ENGCMD_PROD_DUE',
                   'IX_CM_ENGCMD_LEASE','IX_CM_ENGCMD_CASE_STATUS','IX_CM_ENGCMD_REVIEW',
                   'UQ_CM_ECA_ACTION','UQ_CM_ECA_SEQUENCE') ORDER BY INDEX_NAME
                """).query(String.class).list()).hasSize(8);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='CM_ENGINE_COMMAND_ACTION'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    private static void updateNext(DataSource scenario, int count) throws Exception {
        if (count == 0) return;
        try (Connection connection = scenario.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(MASTER,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(count, new Contexts(), new LabelExpression());
            }
        }
    }

    private static void migrate(DataSource scenario) throws Exception {
        try (Connection connection = scenario.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(MASTER,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    private static List<String> appliedChangeSets(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT ID FROM DATABASECHANGELOG WHERE FILENAME=:filename ORDER BY ORDEREXECUTED
                """).param("filename", CHANGELOG).query(String.class).list();
    }

    private static void dropSchema(Connection system) throws SQLException {
        try (Statement statement = system.createStatement()) {
            statement.execute("DROP USER " + SCHEMA + " CASCADE");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1918) throw e;
        }
    }
}
