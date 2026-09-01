package org.casemgmt;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rehearses the Workstream 2 migration from realistic historical rows rather than asserting only
 * the final empty-schema shape.
 *
 * <p>Two users in the shared Oracle Testcontainers database provide two explicit historical
 * installation variants. The embedded-like schema contains the subset of ACT_RE_PROCDEF that the
 * backfill reads. The remote-like schema deliberately has no local Operaton table. Each schema is
 * migrated with the real master changelog up to the dynamically located WS2 boundary, seeded,
 * and then upgraded with the unchanged production master changelog.
 */
class Ws2HistoricalMigrationRehearsalTest extends OracleTestBase {

    private static final String MASTER_CHANGELOG =
            "db/changelog/db.changelog-master.xml";
    private static final String FIRST_WS2_CHANGESET =
            "cm-bpmn-release-exact-identity";
    private static final String LAST_PRE_WS2_CHANGESET =
            "cm-projected-milestone-idempotency";
    private static final String REPAIR_DIAGNOSTIC =
            "Exact process definition identity unresolved by migration; activation repair required";
    private static final String BINDING_REPAIR_DIAGNOSTIC =
            "Exact process definition identity unresolved by migration; binding repair required";
    private static final String DUPLICATE_ACTIVE_DIAGNOSTIC =
            "Retired by migration because a newer active binding exists for this tenant and case definition key";

    private static final String LOCAL_SCHEMA = "WS2_MIG_LOCAL";
    private static final String REMOTE_SCHEMA = "WS2_MIG_REMOTE";
    private static final String LEGACY_SCHEMA = "WS2_MIG_LEGACY";
    private static final String SCHEMA_PASSWORD = "Ws2Migration42";
    private static final String SYSTEM_USER = "system";
    private static final String SYSTEM_PASSWORD = "cm";

    private static String jdbcUrl;

    @BeforeAll
    static void createMigrationRehearsalSchemas() throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }
        try (Connection system = DriverManager.getConnection(
                jdbcUrl, SYSTEM_USER, SYSTEM_PASSWORD)) {
            recreateSchema(system, LOCAL_SCHEMA);
            recreateSchema(system, REMOTE_SCHEMA);
            recreateSchema(system, LEGACY_SCHEMA);
        }
    }

    @AfterAll
    static void dropMigrationRehearsalSchemas() throws Exception {
        if (jdbcUrl == null) return;
        try (Connection system = DriverManager.getConnection(
                jdbcUrl, SYSTEM_USER, SYSTEM_PASSWORD)) {
            dropSchemaIfPresent(system, LOCAL_SCHEMA);
            dropSchemaIfPresent(system, REMOTE_SCHEMA);
            dropSchemaIfPresent(system, LEGACY_SCHEMA);
        }
    }

    @Test
    void embeddedHistoryResolvesOnlyOneExactIdentity()
            throws Exception {
        DataSource local = schemaDataSource(LOCAL_SCHEMA);
        applyPreWs2Master(local);
        createOperatonProcessDefinitionFixture(local);
        seedLocalHistory(local);

        applyRemainingMasterTwice(local);

        JdbcClient jdbc = JdbcClient.create(local);
        assertThat(release(jdbc, "release-exact"))
                .isEqualTo(new ReleaseState(
                        "ACTIVE", "invoice:7:exact", "invoice", 7, "tenant-a", null));
        assertThat(binding(jdbc, "invoice:1"))
                .isEqualTo(new BindingState(
                        "RETIRED", "deployment-exact", "invoice:7:exact",
                        "invoice", 7, "tenant-a", DUPLICATE_ACTIVE_DIAGNOSTIC));
        assertThat(binding(jdbc, "invoice:2"))
                .isEqualTo(new BindingState(
                        "ACTIVE", "deployment-exact-newer", "invoice:8:newer",
                        "invoice", 8, "tenant-a", null));
        assertThat(release(jdbc, "release-ambiguous"))
                .isEqualTo(new ReleaseState(
                        "FAILED", null, null, null, null, REPAIR_DIAGNOSTIC));
        assertThat(binding(jdbc, "claim:1"))
                .isEqualTo(new BindingState(
                        "FAILED", "deployment-ambiguous", null,
                        null, null, null, BINDING_REPAIR_DIAGNOSTIC));

        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_BINDING WHERE STATUS_ = 'ACTIVE'""")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_BINDING WHERE STATUS_ = 'FAILED'""")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_BINDING
                WHERE STATUS_ = 'RETIRED'
                  AND DEPLOY_STATUS_ = 'FAILED'
                  AND FAILURE_DETAIL_ = :diagnostic""")
                .param("diagnostic", DUPLICATE_ACTIVE_DIAGNOSTIC)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_RELEASE
                WHERE FAILURE_DETAIL_ = :diagnostic""")
                .param("diagnostic", REPAIR_DIAGNOSTIC)
                .query(Integer.class).single()).isEqualTo(1);

        assertEveryWs2ChangesetAppliedOnce(jdbc);
    }

    @Test
    void remoteHistoryWithoutLocalOperatonTablesFailsClosedAndRerunsIdempotently()
            throws Exception {
        DataSource remote = schemaDataSource(REMOTE_SCHEMA);
        applyPreWs2Master(remote);
        JdbcClient jdbc = JdbcClient.create(remote);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'ACT_RE_PROCDEF'""")
                .query(Integer.class).single()).isZero();
        seedRemoteHistory(jdbc);

        applyRemainingMasterTwice(remote);

        assertThat(release(jdbc, "release-remote"))
                .isEqualTo(new ReleaseState(
                        "FAILED", null, null, null, null, REPAIR_DIAGNOSTIC));
        assertThat(binding(jdbc, "remote:1"))
                .isEqualTo(new BindingState(
                        "FAILED", "deployment-remote", null,
                        null, null, null, BINDING_REPAIR_DIAGNOSTIC));
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_BINDING WHERE STATUS_ = 'ACTIVE'""")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_RELEASE
                WHERE STATUS_ = 'FAILED' AND FAILURE_DETAIL_ = :diagnostic""")
                .param("diagnostic", REPAIR_DIAGNOSTIC)
                .query(Integer.class).single()).isEqualTo(1);
        assertEveryWs2ChangesetAppliedOnce(jdbc);
    }

    @Test
    void activeRetiredAndFailedLegacyHistoryHaltsTheBpmnOnlyUpgradeWithARemediationCode()
            throws Exception {
        DataSource legacy = schemaDataSource(LEGACY_SCHEMA);
        applyPreWs2Master(legacy);
        JdbcClient jdbc = JdbcClient.create(legacy);
        insertCaseDefinition(jdbc, "legacy-active:1", "legacy-active", "tenant-r", "PLAN_MODEL");
        insertCaseDefinition(jdbc, "legacy-retired:1", "legacy-retired", "tenant-r", "PLAN_MODEL");
        insertCaseDefinition(jdbc, "legacy-failed:1", "legacy-failed", "tenant-r", "PLAN_MODEL");
        insertSharedArtifactReleases(jdbc, "tenant-r");
        insertHistoricalOrchestrationRelease(jdbc, "legacy-active-release", "legacy-active",
                "tenant-r", null);
        insertHistoricalOrchestrationRelease(jdbc, "legacy-retired-release", "legacy-retired",
                "tenant-r", null);
        insertHistoricalOrchestrationRelease(jdbc, "legacy-failed-release", "legacy-failed",
                "tenant-r", null);
        insertHistoricalBinding(jdbc, "legacy-active:1", "legacy-active-release");
        insertHistoricalBinding(jdbc, "legacy-retired:1", "legacy-retired-release");
        insertHistoricalBinding(jdbc, "legacy-failed:1", "legacy-failed-release");

        applyThroughWs2ChangeSet(legacy, "cm-bpmn-binding-lifecycle-identity");
        jdbc.sql("UPDATE CM_CASE_DEF_BINDING SET STATUS_ = 'RETIRED', RETIRED_AT_ = SYSTIMESTAMP "
                + "WHERE CASE_DEF_ID_ = 'legacy-retired:1'").update();
        jdbc.sql("UPDATE CM_CASE_DEF_BINDING SET STATUS_ = 'FAILED', "
                + "FAILURE_DETAIL_ = 'historical failure' "
                + "WHERE CASE_DEF_ID_ = 'legacy-failed:1'").update();

        assertThatThrownBy(() -> applyRemainingMasterTwice(legacy))
                .hasMessageContaining("CM-BPMN-ONLY-LEGACY-DATA")
                .hasMessageContaining("archive, remove, or migrate all retained PLAN_MODEL");
    }

    private static void applyPreWs2Master(DataSource dataSource) throws Exception {
        withLiquibase(dataSource, liquibase -> {
            liquibase.validate();
            List<ChangeSet> changes = liquibase.getDatabaseChangeLog().getChangeSets();
            int firstWs2 = -1;
            for (int index = 0; index < changes.size(); index++) {
                if (FIRST_WS2_CHANGESET.equals(changes.get(index).getId())) {
                    firstWs2 = index;
                    break;
                }
            }
            assertThat(firstWs2)
                    .as("the WS2 changeset must exist after at least one historical changeset")
                    .isPositive();
            assertThat(changes.get(firstWs2 - 1).getId())
                    .as("the rehearsal must stop immediately before the WS2 include")
                    .isEqualTo(LAST_PRE_WS2_CHANGESET);
            liquibase.update(firstWs2, new Contexts(), new LabelExpression());
        });
    }

    private static void applyRemainingMasterTwice(DataSource dataSource) throws Exception {
        withLiquibase(dataSource, liquibase -> {
            liquibase.update(new Contexts(), new LabelExpression());
            liquibase.update(new Contexts(), new LabelExpression());
            liquibase.validate();
        });
    }

    private static void applyThroughWs2ChangeSet(DataSource dataSource, String changeSetId)
            throws Exception {
        withLiquibase(dataSource, liquibase -> {
            List<ChangeSet> changes = liquibase.getDatabaseChangeLog().getChangeSets();
            int firstWs2 = -1;
            int changeSetIndex = -1;
            for (int index = 0; index < changes.size(); index++) {
                if (FIRST_WS2_CHANGESET.equals(changes.get(index).getId())) {
                    firstWs2 = index;
                }
                if (changeSetId.equals(changes.get(index).getId())) {
                    changeSetIndex = index;
                }
            }
            assertThat(firstWs2).isPositive();
            assertThat(changeSetIndex).isGreaterThanOrEqualTo(firstWs2);
            liquibase.update(changeSetIndex - firstWs2 + 1,
                    new Contexts(), new LabelExpression());
        });
    }

    private static void withLiquibase(DataSource dataSource, LiquibaseWork work)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    MASTER_CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                work.run(liquibase);
            }
        }
    }

    private static void createOperatonProcessDefinitionFixture(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                CREATE TABLE ACT_RE_PROCDEF (
                  ID_ VARCHAR2(64) NOT NULL,
                  REV_ NUMBER(10) DEFAULT 1 NOT NULL,
                  CATEGORY_ VARCHAR2(255),
                  NAME_ VARCHAR2(255),
                  KEY_ VARCHAR2(255) NOT NULL,
                  VERSION_ NUMBER(10) NOT NULL,
                  DEPLOYMENT_ID_ VARCHAR2(64),
                  RESOURCE_NAME_ VARCHAR2(4000),
                  DGRM_RESOURCE_NAME_ VARCHAR2(4000),
                  SUSPENSION_STATE_ NUMBER(10) DEFAULT 1 NOT NULL,
                  TENANT_ID_ VARCHAR2(64),
                  VERSION_TAG_ VARCHAR2(64),
                  CONSTRAINT PK_ACT_RE_PROCDEF PRIMARY KEY (ID_)
                )""").update();

        insertProcessDefinition(jdbc, "invoice:7:exact", "invoice", 7,
                "deployment-exact", "tenant-a");
        insertProcessDefinition(jdbc, "invoice:8:other-tenant", "invoice", 8,
                "deployment-exact", "tenant-b");
        insertProcessDefinition(jdbc, "invoice:8:newer", "invoice", 8,
                "deployment-exact-newer", "tenant-a");
        insertProcessDefinition(jdbc, "claim:3:first", "claim", 3,
                "deployment-ambiguous", "tenant-a");
        insertProcessDefinition(jdbc, "claim:4:second", "claim", 4,
                "deployment-ambiguous", "tenant-a");
    }

    private static void seedLocalHistory(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertCaseDefinition(jdbc, "invoice:1", "invoice", "tenant-a", "BPMN");
        insertCaseDefinition(jdbc, "invoice:2", "invoice", "tenant-a", "BPMN", 2);
        insertCaseDefinition(jdbc, "claim:1", "claim", "tenant-a", "BPMN");
        insertSharedArtifactReleases(jdbc, "tenant-a");
        insertHistoricalOrchestrationRelease(jdbc, "release-exact", "invoice",
                "tenant-a", "deployment-exact");
        insertHistoricalOrchestrationRelease(jdbc, "release-exact-newer", "invoice",
                "tenant-a", "deployment-exact-newer");
        insertHistoricalOrchestrationRelease(jdbc, "release-ambiguous", "claim",
                "tenant-a", "deployment-ambiguous");
        insertHistoricalBinding(jdbc, "invoice:1", "release-exact");
        insertHistoricalBinding(jdbc, "invoice:2", "release-exact-newer");
        insertHistoricalBinding(jdbc, "claim:1", "release-ambiguous");

    }

    private static void seedRemoteHistory(JdbcClient jdbc) {
        insertCaseDefinition(jdbc, "remote:1", "remote", "tenant-r", "BPMN");
        insertSharedArtifactReleases(jdbc, "tenant-r");
        insertHistoricalOrchestrationRelease(jdbc, "release-remote", "remote",
                "tenant-r", "deployment-remote");
        insertHistoricalBinding(jdbc, "remote:1", "release-remote");
    }

    private static void insertCaseDefinition(
            JdbcClient jdbc, String id, String key, String tenantId,
            String orchestrationMode) {
        insertCaseDefinition(jdbc, id, key, tenantId, orchestrationMode, 1);
    }

    private static void insertCaseDefinition(
            JdbcClient jdbc, String id, String key, String tenantId,
            String orchestrationMode, int version) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF
                  (ID_, KEY_, VERSION_NO_, TENANT_ID_, NAME_, ORCHESTRATION_MODE_)
                VALUES (:id, :key, :version, :tenantId, :key, :mode)""")
                .param("id", id)
                .param("key", key)
                .param("version", version)
                .param("tenantId", tenantId)
                .param("mode", orchestrationMode)
                .update();
    }

    private static void insertSharedArtifactReleases(JdbcClient jdbc, String tenantId) {
        insertHistoricalRelease(jdbc, "release-contract", "shared", tenantId,
                "CONTRACT", null);
        insertHistoricalRelease(jdbc, "release-presentation", "shared", tenantId,
                "PRESENTATION", null);
    }

    private static void insertHistoricalOrchestrationRelease(
            JdbcClient jdbc, String id, String key, String tenantId, String deploymentId) {
        insertHistoricalRelease(jdbc, id, key, tenantId, "ORCHESTRATION", deploymentId);
    }

    private static void insertHistoricalRelease(
            JdbcClient jdbc, String id, String key, String tenantId,
            String kind, String deploymentId) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF_RELEASE
                  (ID_, CASE_DEF_KEY_, TENANT_ID_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_, STATUS_,
                   ENGINE_DEPLOYMENT_ID_, PUBLISHED_AT_, PUBLISHED_BY_)
                VALUES
                  (:id, :key, :tenantId, :kind, 'application/octet-stream', :content, :sha,
                   'ACTIVE', :deploymentId, TIMESTAMP '2026-01-15 10:00:00', 'historical-writer')""")
                .param("id", id)
                .param("key", key)
                .param("tenantId", tenantId)
                .param("kind", kind)
                .param("content", ("content:" + id).getBytes(StandardCharsets.UTF_8))
                .param("sha", sha(id))
                .param("deploymentId", deploymentId)
                .update();
    }

    private static void insertHistoricalBinding(
            JdbcClient jdbc, String caseDefinitionId, String orchestrationReleaseId) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF_BINDING
                  (CASE_DEF_ID_, ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                   CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                   BOUND_AT_, BOUND_BY_)
                VALUES
                  (:caseDefinitionId, :orchestrationReleaseId, :orchSha, 'release-contract',
                   :contractSha, 'release-presentation', :presentationSha, 'ACTIVE',
                   TIMESTAMP '2026-01-15 10:05:00', 'historical-writer')""")
                .param("caseDefinitionId", caseDefinitionId)
                .param("orchestrationReleaseId", orchestrationReleaseId)
                .param("orchSha", sha(orchestrationReleaseId))
                .param("contractSha", sha("release-contract"))
                .param("presentationSha", sha("release-presentation"))
                .update();
    }

    private static void insertBindingWithAuthority(
            JdbcClient jdbc, String caseDefinitionId, String orchestrationReleaseId,
            String caseDefinitionKey, String tenantId) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF_BINDING
                  (CASE_DEF_ID_, CASE_DEF_KEY_, TENANT_ID_, ORCH_RELEASE_ID_, ORCH_SHA256_,
                   CONTRACT_RELEASE_ID_, CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_,
                   DEPLOY_STATUS_, BOUND_AT_, BOUND_BY_)
                VALUES
                  (:caseDefinitionId, :caseDefinitionKey, :tenantId, :orchestrationReleaseId,
                   :orchSha, 'release-contract', :contractSha, 'release-presentation',
                   :presentationSha, 'ACTIVE', TIMESTAMP '2026-01-15 10:05:00',
                   'rolling-writer')""")
                .param("caseDefinitionId", caseDefinitionId)
                .param("caseDefinitionKey", caseDefinitionKey)
                .param("tenantId", tenantId)
                .param("orchestrationReleaseId", orchestrationReleaseId)
                .param("orchSha", sha(orchestrationReleaseId))
                .param("contractSha", sha("release-contract"))
                .param("presentationSha", sha("release-presentation"))
                .update();
    }

    private static void insertProcessDefinition(
            JdbcClient jdbc, String id, String key, int version,
            String deploymentId, String tenantId) {
        jdbc.sql("""
                INSERT INTO ACT_RE_PROCDEF
                  (ID_, KEY_, VERSION_, DEPLOYMENT_ID_, RESOURCE_NAME_,
                   SUSPENSION_STATE_, TENANT_ID_)
                VALUES
                  (:id, :key, :version, :deploymentId, :resourceName, 1, :tenantId)""")
                .param("id", id)
                .param("key", key)
                .param("version", version)
                .param("deploymentId", deploymentId)
                .param("resourceName", key + ".bpmn")
                .param("tenantId", tenantId)
                .update();
    }

    private static ReleaseState release(JdbcClient jdbc, String id) {
        return jdbc.sql("""
                SELECT STATUS_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_, ENGINE_PROC_DEF_VER_,
                       ENGINE_TENANT_ID_, FAILURE_DETAIL_
                FROM CM_CASE_DEF_RELEASE WHERE ID_ = :id""")
                .param("id", id)
                .query((rs, row) -> new ReleaseState(
                        rs.getString("STATUS_"),
                        rs.getString("ENGINE_PROC_DEF_ID_"),
                        rs.getString("ENGINE_PROC_DEF_KEY_"),
                        nullableInteger(rs, "ENGINE_PROC_DEF_VER_"),
                        rs.getString("ENGINE_TENANT_ID_"),
                        rs.getString("FAILURE_DETAIL_")))
                .single();
    }

    private static BindingState binding(JdbcClient jdbc, String caseDefinitionId) {
        return jdbc.sql("""
                SELECT STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                       ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_, FAILURE_DETAIL_
                FROM CM_CASE_DEF_BINDING WHERE CASE_DEF_ID_ = :caseDefinitionId""")
                .param("caseDefinitionId", caseDefinitionId)
                .query((rs, row) -> new BindingState(
                        rs.getString("STATUS_"),
                        rs.getString("ENGINE_DEPLOYMENT_ID_"),
                        rs.getString("ENGINE_PROC_DEF_ID_"),
                        rs.getString("ENGINE_PROC_DEF_KEY_"),
                        nullableInteger(rs, "ENGINE_PROC_DEF_VER_"),
                        rs.getString("ENGINE_TENANT_ID_"),
                        rs.getString("FAILURE_DETAIL_")))
                .single();
    }

    private static BindingAuthority bindingAuthority(
            JdbcClient jdbc, String caseDefinitionId) {
        return jdbc.sql("""
                SELECT CASE_DEF_KEY_, TENANT_ID_, STATUS_
                FROM CM_CASE_DEF_BINDING WHERE CASE_DEF_ID_ = :caseDefinitionId""")
                .param("caseDefinitionId", caseDefinitionId)
                .query((rs, row) -> new BindingAuthority(
                        rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"),
                        rs.getString("STATUS_")))
                .single();
    }

    private static Integer nullableInteger(java.sql.ResultSet resultSet, String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void assertEveryWs2ChangesetAppliedOnce(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                SELECT ID, COUNT(*)
                FROM DATABASECHANGELOG
                WHERE AUTHOR = 'casemgmt'
                  AND ID IN ('cm-bpmn-release-exact-identity',
                             'cm-bpmn-binding-lifecycle-identity',
                             'cm-bpmn-binding-active-authority',
                             'cm-bpmn-root-correlation-separation')
                GROUP BY ID
                ORDER BY ID""")
                .query((rs, row) -> new AppliedChangeSet(
                        rs.getString(1), rs.getInt(2)))
                .list())
                .containsExactly(
                        new AppliedChangeSet("cm-bpmn-binding-active-authority", 1),
                        new AppliedChangeSet("cm-bpmn-binding-lifecycle-identity", 1),
                        new AppliedChangeSet("cm-bpmn-release-exact-identity", 1),
                        new AppliedChangeSet("cm-bpmn-root-correlation-separation", 1));
    }

    private static DataSource schemaDataSource(String username) {
        return new DriverManagerDataSource(jdbcUrl, username, SCHEMA_PASSWORD);
    }

    private static void recreateSchema(Connection system, String username) throws SQLException {
        dropSchemaIfPresent(system, username);
        try (Statement statement = system.createStatement()) {
            statement.execute("CREATE USER " + username + " IDENTIFIED BY \""
                    + SCHEMA_PASSWORD + "\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
            statement.execute("GRANT CREATE SESSION, RESOURCE TO " + username);
        }
    }

    private static void dropSchemaIfPresent(Connection system, String username)
            throws SQLException {
        try (Statement statement = system.createStatement()) {
            statement.execute("DROP USER " + username + " CASCADE");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1918) throw e;
        }
    }

    private static String sha(String value) {
        return "%064x".formatted(Integer.toUnsignedLong(value.hashCode()));
    }

    @FunctionalInterface
    private interface LiquibaseWork {
        void run(Liquibase liquibase) throws Exception;
    }

    private record ReleaseState(
            String status, String processDefinitionId, String processDefinitionKey,
            Integer processDefinitionVersion, String tenantId, String failureDetail) { }

    private record BindingState(
            String status, String deploymentId, String processDefinitionId,
            String processDefinitionKey, Integer processDefinitionVersion,
            String tenantId, String failureDetail) { }

    private record BindingAuthority(String caseDefinitionKey, String tenantId, String status) { }

    private record AppliedChangeSet(String id, int occurrences) { }
}
