package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.casemgmt.projection.ProjectionStatus;
import org.casemgmt.projection.RemotePollingCheckpointRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends OracleTestBase {

    // No cleanup call here: OracleTestBase resets the schema before/after every test in every
    // extending class automatically (see its class-level @BeforeEach/@AfterAll).

    @Test
    void createsAllDesignAndPocInfrastructureTables() {
        Integer tables = jdbc().sql("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'CM!_%' ESCAPE '!'")
                .query(Integer.class).single();
        // 25 from db-design.sql + CM_ENGINE_COMMAND, CM_EVENT_APPEND_LOCK,
        // CM_CASE_DEF_RELEASE, CM_CASE_DEF_BINDING, and CM_ENGINE_POLL_CHECKPOINT from changesets.
        // CM_APPLIED_ENGINE_OBSERVATION is the WS3 lifecycle-effect claim ledger.
        // DATABASECHANGELOG* do not match the CM_ prefix.
        assertThat(tables).isEqualTo(31);
    }

    @Test
    void enforcesTheIsJsonConstraintOnCaseVariables() {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('d:1', 'd', 1, 'D')""").update();

        assertThatThrownBy(() -> jdbc().sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_,
                                     STATE_, VARIABLES_JSON_)
                VALUES ('e:1', 'e', 'd:1', 'd', 1, 'ACTIVE', 'not json')""").update())
                .hasMessageContaining("CK_CM_CASE_VARS");
    }

    @Test
    void addsEngineSyncColumnToTasks() {
        Integer count = jdbc().sql("""
                SELECT COUNT(*) FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_TASK' AND COLUMN_NAME = 'ENGINE_SYNC_'""")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void webhookRetryDefaultMatchesTheRuntimeBackoffLadder() {
        String defaultValue = jdbc().sql("""
                SELECT DATA_DEFAULT
                FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_WEBHOOK_SUB' AND COLUMN_NAME = 'MAX_RETRIES_'""")
            .query(String.class)
            .single();

        assertThat(defaultValue.trim()).isEqualTo("5");
    }

    @Test
    void storesAnInitialRemotePollingFailureWithNoSuccessTimestamp() {
        RemotePollingCheckpointRepository checkpoints =
                new RemotePollingCheckpointRepository(jdbc());

        checkpoints.failed("operaton-history", "engine unavailable");

        RemotePollingCheckpointRepository.Checkpoint checkpoint =
                checkpoints.find("operaton-history").orElseThrow();
        assertThat(checkpoint.status()).isEqualTo(ProjectionStatus.STALE);
        assertThat(checkpoint.lastError()).isEqualTo("engine unavailable");
        assertThat(checkpoint.lastSuccessAt()).isNull();
        assertThat(checkpoint.watermark()).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    @Test
    void addsExactEngineIdentityAndLifecycleColumns() {
        List<String> releaseColumns = jdbc().sql("""
                SELECT COLUMN_NAME FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_CASE_DEF_RELEASE'
                  AND COLUMN_NAME IN ('ENGINE_PROC_DEF_ID_', 'ENGINE_PROC_DEF_KEY_',
                                      'ENGINE_PROC_DEF_VER_', 'ENGINE_TENANT_ID_',
                                      'VALIDATED_AT_', 'ACTIVATED_AT_', 'RETIRED_AT_')
                ORDER BY COLUMN_NAME""")
                .query(String.class).list();
        assertThat(releaseColumns).containsExactly(
                "ACTIVATED_AT_", "ENGINE_PROC_DEF_ID_", "ENGINE_PROC_DEF_KEY_",
                "ENGINE_PROC_DEF_VER_", "ENGINE_TENANT_ID_", "RETIRED_AT_",
                "VALIDATED_AT_");

        List<String> bindingColumns = jdbc().sql("""
                SELECT COLUMN_NAME FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_CASE_DEF_BINDING'
                  AND COLUMN_NAME IN ('ORCHESTRATION_MODE_', 'STATUS_', 'FAILURE_DETAIL_',
                                      'CASE_DEF_KEY_', 'TENANT_ID_',
                                      'ENGINE_DEPLOYMENT_ID_', 'ENGINE_PROC_DEF_ID_',
                                      'ENGINE_PROC_DEF_KEY_', 'ENGINE_PROC_DEF_VER_',
                                      'ENGINE_TENANT_ID_', 'ACTIVATED_AT_', 'RETIRED_AT_')
                ORDER BY COLUMN_NAME""")
                .query(String.class).list();
        assertThat(bindingColumns).containsExactly(
                "ACTIVATED_AT_", "CASE_DEF_KEY_", "ENGINE_DEPLOYMENT_ID_", "ENGINE_PROC_DEF_ID_",
                "ENGINE_PROC_DEF_KEY_", "ENGINE_PROC_DEF_VER_", "ENGINE_TENANT_ID_",
                "FAILURE_DETAIL_", "ORCHESTRATION_MODE_", "RETIRED_AT_", "STATUS_", "TENANT_ID_");
    }

    @Test
    void allowsTheExpandedReleaseLifecycleButRequiresExactIdentityForDeployedActiveBpmn() {
        insertRelease("draft", "DRAFT", null, null, null, null, null);
        insertRelease("retired", "RETIRED", null, null, null, null, null);
        insertRelease("active-exact", "ACTIVE", "deployment-1", "definition-1",
                "claim", 7, "tenant-a");

        assertThatThrownBy(() -> insertRelease(
                "active-unresolved", "ACTIVE", "deployment-2", null,
                null, null, null))
                .hasMessageContaining("CK_CM_CDR_EXACT_ID");
    }

    @Test
    void preservesPlanModelBindingsButRejectsActiveBpmnBindingsWithoutExactIdentity() {
        insertCaseDefinition("plan:1", "plan", "PLAN_MODEL");
        insertCaseDefinition("bpmn:1", "bpmn", "BPMN");
        insertRelease("plan-orch", "ACTIVE", null, null, null, null, null);
        insertRelease("bpmn-orch", "VALIDATED", null, null, null, null, null);
        insertRelease("contract", "ACTIVE", null, null, null, null, null);
        insertRelease("presentation", "ACTIVE", null, null, null, null, null);

        insertBinding("plan:1", "plan-orch", "PLAN_MODEL", "ACTIVE",
                null, null, null, null, null);

        assertThatThrownBy(() -> insertBinding(
                "bpmn:1", "bpmn-orch", "BPMN", "ACTIVE",
                "deployment-1", null, null, null, null))
                .hasMessageContaining("CK_CM_CDB_EXACT_ID");

        insertBinding("bpmn:1", "bpmn-orch", "BPMN", "ACTIVE",
                "deployment-1", "definition-1", "bpmn", 3, "tenant-a");
    }

    @Test
    void separatesPendingRootCorrelationFromConfirmedEngineIdentity() {
        insertCaseDefinition("root:1", "root", "BPMN");
        insertCase("case-1", "root:1", "root");

        jdbc().sql("""
                INSERT INTO CM_LINKED_PROCESS
                  (ID_, CASE_ID_, PROC_INST_ID_, PROC_DEF_KEY_, ENGINE_SYNC_,
                   CORRELATION_ID_, IS_CASE_ROOT_)
                VALUES
                  ('root-pending', 'case-1', NULL, 'root', 'PENDING',
                   'correlation-1', 1)""").update();

        String processInstanceId = jdbc().sql("""
                SELECT PROC_INST_ID_ FROM CM_LINKED_PROCESS WHERE ID_ = 'root-pending'""")
                .query(String.class).optional().orElse(null);
        assertThat(processInstanceId).isNull();
    }

    @Test
    void preventsTwoConfirmedRootProcessesForOneCase() {
        insertCaseDefinition("root:1", "root", "BPMN");
        insertCase("case-1", "root:1", "root");

        insertConfirmedRoot("root-1", "case-1", "process-1");

        assertThatThrownBy(() -> insertConfirmedRoot("root-2", "case-1", "process-2"))
                .hasMessageContaining("UQ_CM_LPROC_CONF_ROOT");
    }

    @Test
    void createsIndexesForActiveAndExactEngineIdentityLookups() {
        List<String> indexes = jdbc().sql("""
                SELECT INDEX_NAME FROM USER_INDEXES
                WHERE INDEX_NAME IN ('IX_CM_CDR_ENGINE_ID', 'IX_CM_CDB_ACTIVE',
                                     'IX_CM_CDB_ENGINE_ID', 'IX_CM_CASE_ROOT_CORR',
                                     'IX_CM_LPROC_CORRELATION', 'UQ_CM_LPROC_CONF_ROOT',
                                     'UQ_CM_CDB_ACTIVE_KEY')
                ORDER BY INDEX_NAME""")
                .query(String.class).list();

        assertThat(indexes).containsExactly(
                "IX_CM_CASE_ROOT_CORR", "IX_CM_CDB_ACTIVE", "IX_CM_CDB_ENGINE_ID",
                "IX_CM_CDR_ENGINE_ID", "IX_CM_LPROC_CORRELATION",
                "UQ_CM_CDB_ACTIVE_KEY", "UQ_CM_LPROC_CONF_ROOT");
    }

    @Test
    void preventsTwoActiveBindingsForTheSameTenantAndDefinitionKey() {
        insertCaseDefinition("bpmn:1", "bpmn", "BPMN", 1);
        insertCaseDefinition("bpmn:2", "bpmn", "BPMN", 2);
        insertRelease("orch-1", "ACTIVE", "deployment-1", "definition-1",
                "bpmn", 1, null);
        insertRelease("orch-2", "ACTIVE", "deployment-2", "definition-2",
                "bpmn", 2, null);
        insertRelease("contract", "ACTIVE", null, null, null, null, null);
        insertRelease("presentation", "ACTIVE", null, null, null, null, null);

        insertBinding("bpmn:1", "orch-1", "BPMN", "ACTIVE",
                "deployment-1", "definition-1", "bpmn", 1, null);

        assertThatThrownBy(() -> insertBinding("bpmn:2", "orch-2", "BPMN", "ACTIVE",
                "deployment-2", "definition-2", "bpmn", 2, null))
                .hasMessageContaining("UQ_CM_CDB_ACTIVE_KEY");
    }

    @Test
    void derivesAuthorityForOldBindingWritersAndRejectsSuppliedMismatches() {
        insertCaseDefinition("rolling:1", "rolling", "PLAN_MODEL", 1, "tenant-a");
        insertRelease("plan-orch", "ACTIVE", null, null, null, null, null);
        insertRelease("contract", "ACTIVE", null, null, null, null, null);
        insertRelease("presentation", "ACTIVE", null, null, null, null, null);

        insertLegacyBinding("rolling:1", "plan-orch", null, null, false);

        assertThat(jdbc().sql("""
                SELECT CASE_DEF_KEY_, TENANT_ID_, STATUS_
                FROM CM_CASE_DEF_BINDING WHERE CASE_DEF_ID_ = 'rolling:1'""")
                .query((rs, row) -> List.of(
                        rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"),
                        rs.getString("STATUS_")))
                .single())
                .containsExactly("rolling", "tenant-a", "DRAFT");

        insertCaseDefinition("forged:1", "forged", "PLAN_MODEL", 1, "tenant-a");
        assertThatThrownBy(() -> insertLegacyBinding(
                "forged:1", "plan-orch", "not-forged", "tenant-a", true))
                .hasMessageContaining("Binding key/tenant must match its immutable case definition");
        assertThatThrownBy(() -> insertLegacyBinding(
                "forged:1", "plan-orch", "forged", "tenant-b", true))
                .hasMessageContaining("Binding key/tenant must match its immutable case definition");
    }

    @Test
    void reapplyingTheMasterChangelogIsANoOp() throws Exception {
        Integer before = activationChangesetsApplied();
        assertThat(before).isEqualTo(4);

        try (Connection connection = dataSource().getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
                liquibase.update("");
            }
        }

        assertThat(activationChangesetsApplied()).isEqualTo(before);
    }

    private Integer activationChangesetsApplied() {
        return jdbc().sql("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE AUTHOR = 'casemgmt'
                  AND ID IN ('cm-bpmn-release-exact-identity',
                             'cm-bpmn-binding-lifecycle-identity',
                             'cm-bpmn-binding-active-authority',
                             'cm-bpmn-root-correlation-separation')""")
                .query(Integer.class).single();
    }

    private void insertRelease(String id, String status, String deploymentId,
                               String processDefinitionId, String processDefinitionKey,
                               Integer processDefinitionVersion, String engineTenantId) {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF_RELEASE
                  (ID_, CASE_DEF_KEY_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_, STATUS_,
                   ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                   ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_, PUBLISHED_AT_, PUBLISHED_BY_,
                   ACTIVATED_AT_, RETIRED_AT_)
                VALUES
                  (:id, :definitionKey, 'ORCHESTRATION', 'application/xml', :content, :sha, :status,
                   :deploymentId, :processDefinitionId, :processDefinitionKey,
                   :processDefinitionVersion, :engineTenantId, SYSTIMESTAMP, 'migration-test',
                   CASE WHEN :status IN ('ACTIVE', 'RETIRED') THEN SYSTIMESTAMP ELSE NULL END,
                   CASE WHEN :status = 'RETIRED' THEN SYSTIMESTAMP ELSE NULL END)""")
                .param("id", id)
                .param("definitionKey", id)
                .param("content", "<definitions/>".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .param("sha", String.format("%064d", Math.abs(id.hashCode())))
                .param("status", status)
                .param("deploymentId", deploymentId)
                .param("processDefinitionId", processDefinitionId)
                .param("processDefinitionKey", processDefinitionKey)
                .param("processDefinitionVersion", processDefinitionVersion)
                .param("engineTenantId", engineTenantId)
                .update();
    }

    private void insertCaseDefinition(String id, String key, String orchestrationMode) {
        insertCaseDefinition(id, key, orchestrationMode, 1);
    }

    private void insertCaseDefinition(
            String id, String key, String orchestrationMode, int version) {
        insertCaseDefinition(id, key, orchestrationMode, version, null);
    }

    private void insertCaseDefinition(
            String id, String key, String orchestrationMode, int version, String tenantId) {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF
                  (ID_, KEY_, VERSION_NO_, TENANT_ID_, NAME_, ORCHESTRATION_MODE_)
                VALUES (:id, :key, :version, :tenantId, :key, :mode)""")
                .param("id", id).param("key", key).param("version", version)
                .param("tenantId", tenantId).param("mode", orchestrationMode).update();
    }

    private void insertLegacyBinding(
            String caseDefinitionId, String orchestrationReleaseId,
            String caseDefinitionKey, String tenantId, boolean supplyAuthority) {
        String authorityColumns = supplyAuthority ? ", CASE_DEF_KEY_, TENANT_ID_" : "";
        String authorityValues = supplyAuthority ? ", :caseDefinitionKey, :tenantId" : "";
        var statement = jdbc().sql("""
                INSERT INTO CM_CASE_DEF_BINDING
                  (CASE_DEF_ID_, ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                   CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                   BOUND_AT_, BOUND_BY_%s)
                VALUES
                  (:caseDefinitionId, :orchestrationReleaseId, :sha, 'contract', :sha,
                   'presentation', :sha, 'ACTIVE', SYSTIMESTAMP, 'old-writer'%s)
                """.formatted(authorityColumns, authorityValues))
                .param("caseDefinitionId", caseDefinitionId)
                .param("orchestrationReleaseId", orchestrationReleaseId)
                .param("sha", String.format("%064d", Math.abs(caseDefinitionId.hashCode())));
        if (supplyAuthority) {
            statement.param("caseDefinitionKey", caseDefinitionKey)
                    .param("tenantId", tenantId);
        }
        statement.update();
    }

    private void insertBinding(String caseDefinitionId, String orchestrationReleaseId,
                               String orchestrationMode, String status, String deploymentId,
                               String processDefinitionId, String processDefinitionKey,
                               Integer processDefinitionVersion, String engineTenantId) {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF_BINDING
                  (CASE_DEF_ID_, CASE_DEF_KEY_, TENANT_ID_,
                   ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                   CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                   BOUND_AT_, BOUND_BY_, ORCHESTRATION_MODE_, STATUS_,
                   ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                   ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_, ACTIVATED_AT_)
                VALUES
                  (:caseDefinitionId,
                   (SELECT KEY_ FROM CM_CASE_DEF WHERE ID_ = :caseDefinitionId),
                   (SELECT TENANT_ID_ FROM CM_CASE_DEF WHERE ID_ = :caseDefinitionId),
                   :orchestrationReleaseId, :sha, 'contract', :sha,
                   'presentation', :sha, :legacyStatus, SYSTIMESTAMP, 'migration-test',
                   :orchestrationMode, :status, :deploymentId, :processDefinitionId,
                   :processDefinitionKey, :processDefinitionVersion, :engineTenantId,
                   CASE WHEN :status = 'ACTIVE' THEN SYSTIMESTAMP ELSE NULL END)""")
                .param("caseDefinitionId", caseDefinitionId)
                .param("orchestrationReleaseId", orchestrationReleaseId)
                .param("sha", String.format("%064d", Math.abs(caseDefinitionId.hashCode())))
                .param("legacyStatus", "ACTIVE".equals(status) ? "ACTIVE" : "FAILED")
                .param("orchestrationMode", orchestrationMode)
                .param("status", status)
                .param("deploymentId", deploymentId)
                .param("processDefinitionId", processDefinitionId)
                .param("processDefinitionKey", processDefinitionKey)
                .param("processDefinitionVersion", processDefinitionVersion)
                .param("engineTenantId", engineTenantId)
                .update();
    }

    private void insertCase(String id, String caseDefinitionId, String definitionKey) {
        jdbc().sql("""
                INSERT INTO CM_CASE
                  (ID_, ENGINE_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_, STATE_)
                VALUES (:id, 'engine-a', :caseDefinitionId, :definitionKey, 1, 'ACTIVE')""")
                .param("id", id).param("caseDefinitionId", caseDefinitionId)
                .param("definitionKey", definitionKey).update();
    }

    private void insertConfirmedRoot(String id, String caseId, String processInstanceId) {
        jdbc().sql("""
                INSERT INTO CM_LINKED_PROCESS
                  (ID_, CASE_ID_, PROC_INST_ID_, PROC_DEF_KEY_, ENGINE_SYNC_, IS_CASE_ROOT_)
                VALUES (:id, :caseId, :processInstanceId, 'root', 'SYNCED', 1)""")
                .param("id", id).param("caseId", caseId)
                .param("processInstanceId", processInstanceId).update();
    }
}
