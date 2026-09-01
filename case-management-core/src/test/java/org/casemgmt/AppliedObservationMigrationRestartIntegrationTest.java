package org.casemgmt;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** Oracle proof that every durable-observation DDL step resumes after an auto-committed prefix. */
class AppliedObservationMigrationRestartIntegrationTest extends OracleTestBase {

    private static final String CHANGELOG =
            "db/changelog/cm-engine-observation-effects.xml";
    private static final String MASTER_CHANGELOG =
            "db/changelog/db.changelog-master.xml";
    private static final String SCHEMA = "WS3_AEO_RESTART";
    private static final String SCHEMA_PASSWORD = "Ws3Restart42";
    private static final String SYSTEM_USER = "system";
    private static final String SYSTEM_PASSWORD = "cm";
    private static String jdbcUrl;

    private static final List<String> EXPECTED_CHANGESETS = List.of(
            "cm-applied-engine-observation-structure-guard",
            "cm-applied-engine-observation",
            "cm-applied-engine-observation-status-constraint",
            "cm-applied-engine-observation-status-timestamps-constraint",
            "cm-applied-engine-observation-authority-index-structure-guard",
            "cm-applied-engine-observation-authority-index",
            "cm-applied-engine-observation-status-index-structure-guard",
            "cm-applied-engine-observation-status-index",
            "cm-engine-observation-hardening-structure-guard",
            "cm-engine-observation-hardening-kind",
            "cm-engine-observation-hardening-ignored-at",
            "cm-engine-observation-hardening-drop-status",
            "cm-engine-observation-hardening-add-status",
            "cm-engine-observation-hardening-drop-status-ts",
            "cm-engine-observation-hardening-add-status-ts",
            "cm-engine-observation-hardening-plan-process-width",
            "cm-engine-observation-hardening-task-process",
            "cm-engine-observation-hardening-plan-process-index",
            "cm-engine-observation-hardening-task-process-index",
            "cm-engine-observation-channel-engine-id",
            "cm-engine-observation-channel-child-definition",
            "cm-engine-observation-channel-engine-index",
            "cm-engine-observation-byte-semantics",
            "cm-engine-observation-final-state-guard");

    @BeforeAll
    static void captureOracleUrl() throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }
    }

    @AfterAll
    static void dropRestartSchema() throws Exception {
        if (jdbcUrl == null) return;
        try (Connection system = DriverManager.getConnection(
                jdbcUrl, SYSTEM_USER, SYSTEM_PASSWORD)) {
            dropSchemaIfPresent(system);
        }
    }

    @ParameterizedTest(name = "resumes from {0}")
    @EnumSource(PartialState.class)
    void completesAndRerunsFromRepresentativeAutoCommittedPrefixes(PartialState partialState)
            throws Exception {
        recreateSchema();
        DataSource scenarioDataSource = new DriverManagerDataSource(
                jdbcUrl, SCHEMA, SCHEMA_PASSWORD);
        applyMasterBeforeObservationLedger(scenarioDataSource);
        recreateInitialTable(scenarioDataSource, partialState);
        JdbcClient scenarioJdbc = JdbcClient.create(scenarioDataSource);

        migrate(scenarioDataSource);
        assertFinalSchema(scenarioJdbc);
        assertThat(appliedChangeSets(scenarioJdbc))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CHANGESETS);

        migrate(scenarioDataSource);
        assertFinalSchema(scenarioJdbc);
        assertThat(appliedChangeSets(scenarioJdbc))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CHANGESETS);
    }

    @ParameterizedTest(name = "halts on malformed pre-existing {0}")
    @EnumSource(MalformedState.class)
    void malformedSameNamedObjectsHaltWithoutRecordingTheGuardedChangeSet(
            MalformedState malformedState) throws Exception {
        recreateSchema();
        DataSource scenarioDataSource = new DriverManagerDataSource(
                jdbcUrl, SCHEMA, SCHEMA_PASSWORD);
        applyMasterBeforeObservationLedger(scenarioDataSource);
        recreateMalformedPrefix(scenarioDataSource, malformedState);
        JdbcClient scenarioJdbc = JdbcClient.create(scenarioDataSource);
        List<Long> guardedExecutionsBefore = appliedChangeSetExecutions(
                scenarioJdbc, malformedState.guardedChangeSet());

        assertThatThrownBy(() -> migrate(scenarioDataSource))
                .hasStackTraceContaining(malformedState.expectedFailureMessage());
        assertThat(appliedChangeSetExecutions(scenarioJdbc, malformedState.guardedChangeSet()))
                .containsExactlyElementsOf(guardedExecutionsBefore);
    }

    @ParameterizedTest(name = "an applicable observation guard rejects post-apply drift: {0}")
    @EnumSource(FinalMutation.class)
    void applicableObservationGuardRejectsPostApplyDrift(FinalMutation mutation) throws Exception {
        recreateSchema();
        DataSource scenario = new DriverManagerDataSource(
                jdbcUrl, SCHEMA, SCHEMA_PASSWORD);
        migrate(scenario);
        mutation.apply(JdbcClient.create(scenario));

        assertThatThrownBy(() -> migrate(scenario))
                .hasStackTraceContaining(mutation.expectedFailureMessage());
    }

    private static void recreateInitialTable(DataSource scenarioDataSource,
                                             PartialState partialState) throws Exception {
        if (partialState == PartialState.EMPTY_SCHEMA) {
            return;
        }
        createCorrectInitialTable(scenarioDataSource);
        if (partialState == PartialState.SOME_CONSTRAINTS_AND_INDEXES) {
            execute(scenarioDataSource, """
                    ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION ADD CONSTRAINT CK_CM_AEO_STATUS
                      CHECK (STATUS_ IN ('CLAIMED','APPLIED','FAILED'))""");
            execute(scenarioDataSource, """
                    CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT
                      ON CM_APPLIED_ENGINE_OBSERVATION (
                        CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END,
                        TENANT_ID_, FINGERPRINT_)""");
        } else if (partialState != PartialState.TABLE_ONLY) {
            if (partialState != PartialState.ONLY_OLD_TIMESTAMP) {
                String values = partialState == PartialState.ONLY_FINAL_STATUS
                        || partialState == PartialState.BOTH_FINAL
                        ? "'CLAIMED','APPLIED','IGNORED_STALE','FAILED'"
                        : "'CLAIMED','APPLIED','FAILED'";
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD CONSTRAINT CK_CM_AEO_STATUS CHECK (STATUS_ IN (" + values + "))");
            }
            if (partialState != PartialState.ONLY_FINAL_STATUS) {
                String check = partialState == PartialState.BOTH_FINAL
                        ? "CHECK ((STATUS_ != 'APPLIED' OR APPLIED_AT_ IS NOT NULL) "
                        + "AND (STATUS_ != 'IGNORED_STALE' OR IGNORED_AT_ IS NOT NULL) "
                        + "AND (STATUS_ != 'FAILED' OR FAILED_AT_ IS NOT NULL))"
                        : "CHECK ((STATUS_ != 'APPLIED' OR APPLIED_AT_ IS NOT NULL) "
                        + "AND (STATUS_ != 'FAILED' OR FAILED_AT_ IS NOT NULL))";
                if (partialState == PartialState.BOTH_FINAL) {
                    execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                            + "ADD IGNORED_AT_ TIMESTAMP WITH TIME ZONE");
                }
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD CONSTRAINT CK_CM_AEO_STATUS_TS " + check);
            }
        }
    }

    private static void createCorrectInitialTable(DataSource scenarioDataSource) throws Exception {
        execute(scenarioDataSource, """
                CREATE TABLE CM_APPLIED_ENGINE_OBSERVATION (
                  OBSERVATION_ID_ VARCHAR2(128) NOT NULL,
                  TENANT_ID_ VARCHAR2(64),
                  FINGERPRINT_ VARCHAR2(64) NOT NULL,
                  CLAIM_TOKEN_ VARCHAR2(43) NOT NULL,
                  STATUS_ VARCHAR2(16) NOT NULL,
                  SOURCE_ VARCHAR2(128) NOT NULL,
                  CASE_ID_ VARCHAR2(128) NOT NULL,
                  PROCESS_INSTANCE_ID_ VARCHAR2(128) NOT NULL,
                  ENTITY_ID_ VARCHAR2(128) NOT NULL,
                  ENTITY_REVISION_ NUMBER(19),
                  EVENT_TYPE_ VARCHAR2(64) NOT NULL,
                  ENGINE_OCCURRED_AT_ TIMESTAMP WITH TIME ZONE NOT NULL,
                  CLAIMED_AT_ TIMESTAMP WITH TIME ZONE NOT NULL,
                  APPLIED_AT_ TIMESTAMP WITH TIME ZONE,
                  FAILED_AT_ TIMESTAMP WITH TIME ZONE,
                  FAILURE_DETAIL_ VARCHAR2(2000)
                )""");
    }

    private static void recreateMalformedPrefix(
            DataSource scenarioDataSource, MalformedState malformedState) throws Exception {
        if (malformedState == MalformedState.TABLE_WRONG_COLUMN_SIGNATURE) {
            createCorrectInitialTable(scenarioDataSource);
            execute(scenarioDataSource, """
                    ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION
                    MODIFY CLAIM_TOKEN_ VARCHAR2(64)""");
            return;
        }
        createCorrectInitialTable(scenarioDataSource);
        String authorityDefinition = switch (malformedState) {
            case AUTHORITY_INDEX_NONUNIQUE -> """
                    CREATE INDEX UQ_CM_AEO_AUTH_FINGERPRINT
                    ON CM_APPLIED_ENGINE_OBSERVATION (
                      CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END,
                      TENANT_ID_, FINGERPRINT_)""";
            case AUTHORITY_INDEX_WRONG_COLUMN -> """
                    CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT
                    ON CM_APPLIED_ENGINE_OBSERVATION (
                      CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END,
                      TENANT_ID_, OBSERVATION_ID_)""";
            case AUTHORITY_INDEX_WRONG_EXPRESSION -> """
                    CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT
                    ON CM_APPLIED_ENGINE_OBSERVATION (
                      CASE WHEN TENANT_ID_ IS NULL THEN 0 ELSE 1 END,
                      TENANT_ID_, FINGERPRINT_)""";
            case STATUS_INDEX_WRONG_COLUMN -> null;
            case OBSERVATION_KIND_WRONG_DEFAULT -> {
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD OBSERVATION_KIND_ VARCHAR2(32) DEFAULT 'FORGED' NOT NULL");
                yield null;
            }
            case ENGINE_ID_WRONG_WIDTH -> {
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD ENGINE_ID_ VARCHAR2(64)");
                yield null;
            }
            case FINAL_STATUS_CONSTRAINT_WRONG -> {
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD CONSTRAINT CK_CM_AEO_STATUS CHECK (STATUS_ IN ('CLAIMED'))");
                yield null;
            }
            case FINAL_STATUS_CONSTRAINT_DISABLED -> {
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD CONSTRAINT CK_CM_AEO_STATUS CHECK (STATUS_ IN "
                        + "('CLAIMED','APPLIED','FAILED')) DISABLE NOVALIDATE");
                yield null;
            }
            case PLAN_PROCESS_INDEX_WRONG -> {
                execute(scenarioDataSource, "CREATE INDEX IX_CM_PI_PROC_INST "
                        + "ON CM_PLAN_ITEM(PROC_INST_ID_,CASE_ID_)");
                yield null;
            }
            case TASK_PROCESS_INDEX_TRAILING -> {
                execute(scenarioDataSource, "ALTER TABLE CM_TASK ADD PROC_INST_ID_ VARCHAR2(128)");
                execute(scenarioDataSource, "CREATE INDEX IX_CM_TASK_PROC_INST "
                        + "ON CM_TASK(CASE_ID_,PROC_INST_ID_,ID_)");
                yield null;
            }
            case ENGINE_ENTITY_INDEX_TRAILING -> {
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD OBSERVATION_KIND_ VARCHAR2(32) DEFAULT 'LEGACY' NOT NULL");
                execute(scenarioDataSource, "ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION "
                        + "ADD ENGINE_ID_ VARCHAR2(128)");
                execute(scenarioDataSource, "CREATE INDEX IX_CM_AEO_ENGINE_ENTITY ON "
                        + "CM_APPLIED_ENGINE_OBSERVATION(TENANT_ID_,ENGINE_ID_,CASE_ID_,"
                        + "PROCESS_INSTANCE_ID_,OBSERVATION_KIND_,ENTITY_ID_,STATUS_,CLAIMED_AT_)");
                yield null;
            }
            case TABLE_WRONG_COLUMN_SIGNATURE -> throw new IllegalStateException();
        };
        if (authorityDefinition != null) {
            execute(scenarioDataSource, authorityDefinition);
        } else if (malformedState == MalformedState.STATUS_INDEX_WRONG_COLUMN) {
            execute(scenarioDataSource, """
                    CREATE INDEX IX_CM_AEO_STATUS
                    ON CM_APPLIED_ENGINE_OBSERVATION (STATUS_, APPLIED_AT_)""");
        }
    }

    private static void assertFinalSchema(JdbcClient scenarioJdbc) {
        assertThat(scenarioJdbc.sql("""
                SELECT COLUMN_NAME FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_APPLIED_ENGINE_OBSERVATION'
                ORDER BY COLUMN_ID""").query(String.class).list())
                .containsExactlyInAnyOrder(
                        "OBSERVATION_ID_", "TENANT_ID_", "FINGERPRINT_", "CLAIM_TOKEN_",
                        "STATUS_", "SOURCE_", "CASE_ID_", "PROCESS_INSTANCE_ID_", "ENTITY_ID_",
                        "ENTITY_REVISION_", "EVENT_TYPE_", "ENGINE_OCCURRED_AT_", "CLAIMED_AT_",
                        "APPLIED_AT_", "FAILED_AT_", "FAILURE_DETAIL_", "OBSERVATION_KIND_",
                        "IGNORED_AT_", "ENGINE_ID_");
        assertThat(scenarioJdbc.sql("""
                SELECT CONSTRAINT_NAME || ':' || CONSTRAINT_TYPE FROM USER_CONSTRAINTS
                WHERE TABLE_NAME = 'CM_APPLIED_ENGINE_OBSERVATION'
                  AND CONSTRAINT_NAME IN ('CK_CM_AEO_STATUS', 'CK_CM_AEO_STATUS_TS')
                ORDER BY CONSTRAINT_NAME""").query(String.class).list())
                .containsExactly("CK_CM_AEO_STATUS:C", "CK_CM_AEO_STATUS_TS:C");
        assertThat(scenarioJdbc.sql("""
                SELECT INDEX_NAME || ':' || UNIQUENESS FROM USER_INDEXES
                WHERE TABLE_NAME = 'CM_APPLIED_ENGINE_OBSERVATION'
                  AND INDEX_NAME IN ('UQ_CM_AEO_AUTH_FINGERPRINT', 'IX_CM_AEO_STATUS',
                                     'IX_CM_AEO_ENGINE_ENTITY')
                ORDER BY INDEX_NAME""").query(String.class).list())
                .containsExactly(
                        "IX_CM_AEO_ENGINE_ENTITY:NONUNIQUE",
                        "IX_CM_AEO_STATUS:NONUNIQUE",
                        "UQ_CM_AEO_AUTH_FINGERPRINT:UNIQUE");
        assertThat(indexColumns(scenarioJdbc, "IX_CM_AEO_STATUS"))
                .satisfiesExactly(
                        entry -> assertThat(entry).isEqualTo("STATUS_"),
                        entry -> assertThat(entry).startsWith("SYS_NC"));
        assertThat(indexExpressions(scenarioJdbc, "IX_CM_AEO_STATUS"))
                .singleElement()
                .satisfies(expression -> assertThat(expression)
                        .containsIgnoringCase("SYS_EXTRACT_UTC")
                        .containsIgnoringCase("CLAIMED_AT_"));
        assertThat(indexExpressions(scenarioJdbc, "UQ_CM_AEO_AUTH_FINGERPRINT"))
                .singleElement()
                .satisfies(expression -> assertThat(normalizeSql(expression))
                        .isEqualTo("CASE WHEN \"TENANT_ID_\" IS NULL THEN 1 ELSE 0 END"));
        assertThat(indexColumns(scenarioJdbc, "IX_CM_AEO_ENGINE_ENTITY"))
                .containsExactly("TENANT_ID_", "ENGINE_ID_", "CASE_ID_",
                        "PROCESS_INSTANCE_ID_", "OBSERVATION_KIND_", "ENTITY_ID_", "STATUS_");
    }

    private static List<String> indexColumns(JdbcClient scenarioJdbc, String indexName) {
        return scenarioJdbc.sql("""
                SELECT COLUMN_NAME FROM USER_IND_COLUMNS
                WHERE INDEX_NAME = :indexName
                ORDER BY COLUMN_POSITION""")
                .param("indexName", indexName).query(String.class).list();
    }

    private static List<String> indexExpressions(JdbcClient scenarioJdbc, String indexName) {
        return scenarioJdbc.sql("""
                SELECT COLUMN_EXPRESSION FROM USER_IND_EXPRESSIONS
                WHERE INDEX_NAME = :indexName
                ORDER BY COLUMN_POSITION""")
                .param("indexName", indexName).query(String.class).list();
    }

    private static List<String> appliedChangeSets(JdbcClient scenarioJdbc) {
        return scenarioJdbc.sql("""
                SELECT ID FROM DATABASECHANGELOG
                WHERE FILENAME = :filename
                ORDER BY ORDEREXECUTED""")
                .param("filename", CHANGELOG).query(String.class).list();
    }

    private static List<Long> appliedChangeSetExecutions(
            JdbcClient scenarioJdbc, String changeSetId) {
        return scenarioJdbc.sql("""
                SELECT ORDEREXECUTED FROM DATABASECHANGELOG
                WHERE FILENAME = :filename AND ID = :changeSetId
                ORDER BY ORDEREXECUTED""")
                .param("filename", CHANGELOG)
                .param("changeSetId", changeSetId)
                .query(Long.class)
                .list();
    }

    private static String normalizeSql(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static void applyMasterBeforeObservationLedger(DataSource scenarioDataSource)
            throws Exception {
        try (Connection connection = scenarioDataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    MASTER_CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var changes = liquibase.getDatabaseChangeLog().getChangeSets();
                int firstObservation = -1;
                for (int index = 0; index < changes.size(); index++) {
                    if ("cm-applied-engine-observation".equals(changes.get(index).getId())) {
                        firstObservation = index;
                        break;
                    }
                }
                assertThat(firstObservation).isPositive();
                liquibase.update(firstObservation, new Contexts(), new LabelExpression());
            }
        }
    }

    private static void migrate(DataSource scenarioDataSource) throws Exception {
        try (Connection connection = scenarioDataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    MASTER_CHANGELOG,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    private static void execute(DataSource scenarioDataSource, String sql) throws Exception {
        try (Connection connection = scenarioDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void recreateSchema() throws Exception {
        try (Connection system = DriverManager.getConnection(
                jdbcUrl, SYSTEM_USER, SYSTEM_PASSWORD)) {
            dropSchemaIfPresent(system);
            try (Statement statement = system.createStatement()) {
                statement.execute("CREATE USER " + SCHEMA + " IDENTIFIED BY \""
                        + SCHEMA_PASSWORD
                        + "\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
                statement.execute("GRANT CREATE SESSION, RESOURCE TO " + SCHEMA);
            }
        }
    }

    private static void dropSchemaIfPresent(Connection system) throws SQLException {
        try (Statement statement = system.createStatement()) {
            statement.execute("DROP USER " + SCHEMA + " CASCADE");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1918) throw e;
        }
    }

    private enum PartialState {
        EMPTY_SCHEMA,
        TABLE_ONLY,
        SOME_CONSTRAINTS_AND_INDEXES,
        BOTH_OLD,
        ONLY_OLD_TIMESTAMP,
        ONLY_FINAL_STATUS,
        BOTH_FINAL
    }

    private enum MalformedState {
        TABLE_WRONG_COLUMN_SIGNATURE(
                "cm-applied-engine-observation-structure-guard",
                "CM_APPLIED_ENGINE_OBSERVATION has an incompatible structure"),
        AUTHORITY_INDEX_NONUNIQUE(
                "cm-applied-engine-observation-authority-index-structure-guard",
                "UQ_CM_AEO_AUTH_FINGERPRINT has an incompatible structure"),
        AUTHORITY_INDEX_WRONG_COLUMN(
                "cm-applied-engine-observation-authority-index-structure-guard",
                "UQ_CM_AEO_AUTH_FINGERPRINT has an incompatible structure"),
        AUTHORITY_INDEX_WRONG_EXPRESSION(
                "cm-applied-engine-observation-authority-index-structure-guard",
                "UQ_CM_AEO_AUTH_FINGERPRINT has an incompatible structure"),
        STATUS_INDEX_WRONG_COLUMN(
                "cm-applied-engine-observation-status-index-structure-guard",
                "IX_CM_AEO_STATUS has an incompatible structure"),
        OBSERVATION_KIND_WRONG_DEFAULT(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible"),
        ENGINE_ID_WRONG_WIDTH(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible"),
        FINAL_STATUS_CONSTRAINT_WRONG(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible"),
        FINAL_STATUS_CONSTRAINT_DISABLED(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible"),
        PLAN_PROCESS_INDEX_WRONG(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible"),
        TASK_PROCESS_INDEX_TRAILING(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible"),
        ENGINE_ENTITY_INDEX_TRAILING(
                "cm-engine-observation-hardening-structure-guard",
                "Engine observation hardening structure is incompatible");

        private final String guardedChangeSet;
        private final String expectedFailureMessage;

        MalformedState(String guardedChangeSet, String expectedFailureMessage) {
            this.guardedChangeSet = guardedChangeSet;
            this.expectedFailureMessage = expectedFailureMessage;
        }

        String guardedChangeSet() { return guardedChangeSet; }
        String expectedFailureMessage() { return expectedFailureMessage; }
    }

    private enum FinalMutation {
        CHANGED_INITIAL_COLUMN("CM_APPLIED_ENGINE_OBSERVATION has an incompatible structure") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION MODIFY SOURCE_ VARCHAR2(129)")
                        .update();
            }
        },
        CHANGED_LATER_COLUMN("Engine observation hardening structure is incompatible") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION MODIFY ENGINE_ID_ VARCHAR2(129)")
                        .update();
            }
        },
        REVERTED_OLD_STATUS("Engine observation final structure is incompatible") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION DROP CONSTRAINT CK_CM_AEO_STATUS")
                        .update();
                jdbc.sql("ALTER TABLE CM_APPLIED_ENGINE_OBSERVATION ADD CONSTRAINT CK_CM_AEO_STATUS "
                        + "CHECK (STATUS_ IN ('CLAIMED','APPLIED','FAILED'))").update();
            }
        },
        REVERTED_PLAN_PROCESS_WIDTH("Engine observation final structure is incompatible") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_PLAN_ITEM MODIFY PROC_INST_ID_ VARCHAR2(64)").update();
            }
        },
        REMOVED_PLAN_PROCESS_INDEX("Engine observation final structure is incompatible") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("DROP INDEX IX_CM_PI_PROC_INST").update();
            }
        },
        REMOVED_TASK_PROCESS_INDEX("Engine observation final structure is incompatible") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("DROP INDEX IX_CM_TASK_PROC_INST").update();
            }
        },
        REPLACED_AUTHORITY_INDEX("UQ_CM_AEO_AUTH_FINGERPRINT has an incompatible structure") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("DROP INDEX UQ_CM_AEO_AUTH_FINGERPRINT").update();
                jdbc.sql("CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT "
                        + "ON CM_APPLIED_ENGINE_OBSERVATION(TENANT_ID_, FINGERPRINT_)").update();
            }
        },
        REPLACED_STATUS_INDEX("IX_CM_AEO_STATUS has an incompatible structure") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("DROP INDEX IX_CM_AEO_STATUS").update();
                jdbc.sql("CREATE INDEX IX_CM_AEO_STATUS "
                        + "ON CM_APPLIED_ENGINE_OBSERVATION(STATUS_)").update();
            }
        },
        UNUSABLE_ENGINE_ENTITY_INDEX("Engine observation hardening structure is incompatible") {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER INDEX IX_CM_AEO_ENGINE_ENTITY UNUSABLE").update();
            }
        };

        private final String expectedFailureMessage;

        FinalMutation(String expectedFailureMessage) {
            this.expectedFailureMessage = expectedFailureMessage;
        }

        String expectedFailureMessage() {
            return expectedFailureMessage;
        }

        abstract void apply(JdbcClient jdbc);
    }
}
