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
            "cm-engine-observation-channel-engine-index");

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
        assertThat(appliedChangeSets(scenarioJdbc)).containsExactlyElementsOf(EXPECTED_CHANGESETS);

        migrate(scenarioDataSource);
        assertFinalSchema(scenarioJdbc);
        assertThat(appliedChangeSets(scenarioJdbc)).containsExactlyElementsOf(EXPECTED_CHANGESETS);
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

        assertThatThrownBy(() -> migrate(scenarioDataSource))
                .hasRootCauseMessage(malformedState.expectedFailureMessage());
        assertThat(appliedChangeSets(scenarioJdbc))
                .doesNotContain(malformedState.guardedChangeSet());
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
            case TABLE_WRONG_COLUMN_SIGNATURE -> throw new IllegalStateException();
        };
        if (authorityDefinition != null) {
            execute(scenarioDataSource, authorityDefinition);
        } else {
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
                .containsExactly(
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
                .containsExactly("STATUS_", "CLAIMED_AT_");
        assertThat(indexExpressions(scenarioJdbc, "UQ_CM_AEO_AUTH_FINGERPRINT"))
                .containsExactly(
                        "CASE WHEN \"TENANT_ID_\" IS NULL THEN 1 ELSE 0 END", null, null);
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
        SOME_CONSTRAINTS_AND_INDEXES
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
                "IX_CM_AEO_STATUS has an incompatible structure");

        private final String guardedChangeSet;
        private final String expectedFailureMessage;

        MalformedState(String guardedChangeSet, String expectedFailureMessage) {
            this.guardedChangeSet = guardedChangeSet;
            this.expectedFailureMessage = expectedFailureMessage;
        }

        String guardedChangeSet() { return guardedChangeSet; }
        String expectedFailureMessage() { return expectedFailureMessage; }
    }
}
