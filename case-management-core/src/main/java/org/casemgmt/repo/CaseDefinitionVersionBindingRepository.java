package org.casemgmt.repo;

import org.casemgmt.error.CaseConflictException;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.EngineDeploymentIdentityResolver;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;

public class CaseDefinitionVersionBindingRepository implements EngineDeploymentIdentityResolver {

    private final JdbcClient jdbc;

    public CaseDefinitionVersionBindingRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void insert(CaseDefinitionVersionBinding binding) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF_BINDING
                  (CASE_DEF_ID_, CASE_DEF_KEY_, TENANT_ID_,
                   ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                   CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                   ORCHESTRATION_MODE_, STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_,
                   ENGINE_PROC_DEF_KEY_, ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_,
                   FAILURE_DETAIL_, BOUND_AT_, ACTIVATED_AT_, RETIRED_AT_, BOUND_BY_)
                VALUES
                  (:definition, :definitionKey, :tenant, :orchestration,
                   :orchestrationSha, :contract, :contractSha,
                   :presentation, :presentationSha, :deploymentStatus, :mode, :bindingStatus,
                   :deployment, :processDefinitionId, :processDefinitionKey,
                   :processDefinitionVersion, :engineTenant, :failure, :boundAt, :activatedAt,
                   :retiredAt, :boundBy)""")
                .param("definition", binding.caseDefinitionId())
                .param("definitionKey", binding.caseDefinitionKey())
                .param("tenant", binding.tenantId())
                .param("orchestration", binding.orchestrationReleaseId())
                .param("orchestrationSha", binding.orchestrationSha256())
                .param("contract", binding.contractReleaseId())
                .param("contractSha", binding.contractSha256())
                .param("presentation", binding.presentationReleaseId())
                .param("presentationSha", binding.presentationSha256())
                .param("deploymentStatus", binding.deploymentStatus().name())
                .param("mode", binding.orchestrationMode().name())
                .param("bindingStatus", binding.status().name())
                .param("deployment", deploymentId(binding.engineIdentity()))
                .param("processDefinitionId", processDefinitionId(binding.engineIdentity()))
                .param("processDefinitionKey", processDefinitionKey(binding.engineIdentity()))
                .param("processDefinitionVersion", processDefinitionVersion(binding.engineIdentity()))
                .param("engineTenant", engineTenant(binding.engineIdentity()))
                .param("failure", binding.failureDetail())
                .param("boundAt", binding.boundAt())
                .param("activatedAt", binding.activatedAt())
                .param("retiredAt", binding.retiredAt())
                .param("boundBy", binding.boundBy())
                .update();
    }

    public Optional<CaseDefinitionVersionBinding> find(String caseDefinitionId) {
        return jdbc.sql("""
                SELECT CASE_DEF_ID_, CASE_DEF_KEY_, TENANT_ID_,
                       ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                       CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                       ORCHESTRATION_MODE_, STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_,
                       ENGINE_PROC_DEF_KEY_, ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_,
                       FAILURE_DETAIL_, BOUND_AT_, ACTIVATED_AT_, RETIRED_AT_, BOUND_BY_
                FROM CM_CASE_DEF_BINDING WHERE CASE_DEF_ID_ = :id""")
                .param("id", caseDefinitionId)
                .query((rs, rowNum) -> new CaseDefinitionVersionBinding(
                        rs.getString("CASE_DEF_ID_"), rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"), rs.getString("ORCH_RELEASE_ID_"),
                        rs.getString("ORCH_SHA256_"), rs.getString("CONTRACT_RELEASE_ID_"),
                        rs.getString("CONTRACT_SHA256_"), rs.getString("PRESENT_RELEASE_ID_"),
                        rs.getString("PRESENT_SHA256_"),
                        ReleaseStatus.valueOf(rs.getString("DEPLOY_STATUS_")),
                        OrchestrationMode.valueOf(rs.getString("ORCHESTRATION_MODE_")),
                        BindingStatus.valueOf(rs.getString("STATUS_")),
                        identity(rs.getString("ENGINE_DEPLOYMENT_ID_"),
                                rs.getString("ENGINE_PROC_DEF_ID_"),
                                rs.getString("ENGINE_PROC_DEF_KEY_"),
                                integer(rs.getObject("ENGINE_PROC_DEF_VER_")),
                                rs.getString("ENGINE_TENANT_ID_")),
                        rs.getString("FAILURE_DETAIL_"),
                        rs.getObject("BOUND_AT_", OffsetDateTime.class),
                        rs.getObject("ACTIVATED_AT_", OffsetDateTime.class),
                        rs.getObject("RETIRED_AT_", OffsetDateTime.class),
                        rs.getString("BOUND_BY_")))
                .optional();
    }

    public List<CaseDefinitionVersionBinding> findDraftByOrchestrationRelease(
            String orchestrationReleaseId) {
        return jdbc.sql("""
                SELECT CASE_DEF_ID_, CASE_DEF_KEY_, TENANT_ID_,
                       ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                       CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                       ORCHESTRATION_MODE_, STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_,
                       ENGINE_PROC_DEF_KEY_, ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_,
                       FAILURE_DETAIL_, BOUND_AT_, ACTIVATED_AT_, RETIRED_AT_, BOUND_BY_
                FROM CM_CASE_DEF_BINDING
                WHERE ORCH_RELEASE_ID_ = :release AND STATUS_ = 'DRAFT'
                ORDER BY BOUND_AT_, CASE_DEF_ID_""")
                .param("release", orchestrationReleaseId)
                .query((rs, rowNum) -> new CaseDefinitionVersionBinding(
                        rs.getString("CASE_DEF_ID_"), rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"), rs.getString("ORCH_RELEASE_ID_"),
                        rs.getString("ORCH_SHA256_"), rs.getString("CONTRACT_RELEASE_ID_"),
                        rs.getString("CONTRACT_SHA256_"), rs.getString("PRESENT_RELEASE_ID_"),
                        rs.getString("PRESENT_SHA256_"),
                        ReleaseStatus.valueOf(rs.getString("DEPLOY_STATUS_")),
                        OrchestrationMode.valueOf(rs.getString("ORCHESTRATION_MODE_")),
                        BindingStatus.valueOf(rs.getString("STATUS_")),
                        identity(rs.getString("ENGINE_DEPLOYMENT_ID_"),
                                rs.getString("ENGINE_PROC_DEF_ID_"),
                                rs.getString("ENGINE_PROC_DEF_KEY_"),
                                integer(rs.getObject("ENGINE_PROC_DEF_VER_")),
                                rs.getString("ENGINE_TENANT_ID_")),
                        rs.getString("FAILURE_DETAIL_"),
                        rs.getObject("BOUND_AT_", OffsetDateTime.class),
                        rs.getObject("ACTIVATED_AT_", OffsetDateTime.class),
                        rs.getObject("RETIRED_AT_", OffsetDateTime.class),
                        rs.getString("BOUND_BY_")))
                .list();
    }

    public void fail(String caseDefinitionId, String failureDetail) {
        int failed = jdbc.sql("""
                UPDATE CM_CASE_DEF_BINDING
                SET DEPLOY_STATUS_ = 'FAILED', STATUS_ = 'FAILED',
                    FAILURE_DETAIL_ = :failure
                WHERE CASE_DEF_ID_ = :definition AND STATUS_ = 'DRAFT'""")
                .param("failure", failureDetail)
                .param("definition", caseDefinitionId)
                .update();
        if (failed != 1) {
            throw lifecycleConflict(caseDefinitionId,
                    "binding state changed concurrently before failure was recorded");
        }
    }

    public void failWaitingByRelease(String orchestrationReleaseId, String failureDetail) {
        jdbc.sql("""
                UPDATE CM_CASE_DEF_BINDING
                SET DEPLOY_STATUS_ = 'FAILED', STATUS_ = 'FAILED',
                    FAILURE_DETAIL_ = :failure
                WHERE ORCH_RELEASE_ID_ = :release AND STATUS_ = 'DRAFT'""")
                .param("failure", failureDetail)
                .param("release", orchestrationReleaseId)
                .update();
    }

    /**
     * Replaces the currently active version for one immutable (tenant,key) authority.
     * The function-based unique index added by the WS2 migration is the final concurrency
     * backstop: competing transactions can never commit two ACTIVE rows.
     */
    public void activate(CaseDefinitionVersionBinding binding) {
        if (binding.status() != BindingStatus.ACTIVE || binding.engineIdentity() == null) {
            throw new IllegalArgumentException(
                    "Binding activation requires an ACTIVE binding with exact engine identity");
        }
        ActivationAuthority authority = lockAuthorityAndRequireCandidateVersion(binding);
        if (authority.candidateVersion() < authority.expectedVersion()) {
            markSuperseded(binding.caseDefinitionId(), authority.candidateVersion(),
                    authority.expectedVersion());
            return;
        }
        if (eligibleForActivation(binding) != 1) {
            throw lifecycleConflict(binding.caseDefinitionId(),
                    "pinned releases or exact engine identity are no longer ACTIVE and matching");
        }
        try {
            // Retirement happens only after the complete candidate check above. Both changes are
            // called from a @Transactional service and therefore commit or roll back together.
            jdbc.sql("""
                    UPDATE CM_CASE_DEF_BINDING
                    SET STATUS_ = 'RETIRED', RETIRED_AT_ = SYSTIMESTAMP
                    WHERE STATUS_ = 'ACTIVE' AND CASE_DEF_ID_ != :definition
                      AND CASE_DEF_KEY_ = :key
                      AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                    .param("definition", binding.caseDefinitionId())
                    .param("key", binding.caseDefinitionKey())
                    .param("tenant", binding.tenantId())
                    .update();

            int activated = jdbc.sql("""
                    UPDATE CM_CASE_DEF_BINDING
                    SET DEPLOY_STATUS_ = 'ACTIVE', STATUS_ = 'ACTIVE',
                        ENGINE_DEPLOYMENT_ID_ = :deployment,
                        ENGINE_PROC_DEF_ID_ = :processDefinitionId,
                        ENGINE_PROC_DEF_KEY_ = :processDefinitionKey,
                        ENGINE_PROC_DEF_VER_ = :processDefinitionVersion,
                        ENGINE_TENANT_ID_ = :engineTenant,
                        FAILURE_DETAIL_ = NULL, ACTIVATED_AT_ = :activatedAt
                    WHERE CASE_DEF_ID_ = :definition AND STATUS_ = 'DRAFT'
                      AND CASE_DEF_KEY_ = :key
                      AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))
                      AND EXISTS (
                        SELECT 1
                        FROM CM_CASE_DEF_RELEASE orchestration
                        JOIN CM_CASE_DEF_RELEASE contract
                          ON contract.ID_ = CM_CASE_DEF_BINDING.CONTRACT_RELEASE_ID_
                        JOIN CM_CASE_DEF_RELEASE presentation
                          ON presentation.ID_ = CM_CASE_DEF_BINDING.PRESENT_RELEASE_ID_
                        WHERE orchestration.ID_ = CM_CASE_DEF_BINDING.ORCH_RELEASE_ID_
                          AND orchestration.STATUS_ = 'ACTIVE'
                          AND contract.STATUS_ = 'ACTIVE'
                          AND presentation.STATUS_ = 'ACTIVE'
                          AND orchestration.SHA256_ = CM_CASE_DEF_BINDING.ORCH_SHA256_
                          AND contract.SHA256_ = CM_CASE_DEF_BINDING.CONTRACT_SHA256_
                          AND presentation.SHA256_ = CM_CASE_DEF_BINDING.PRESENT_SHA256_
                          AND orchestration.ENGINE_DEPLOYMENT_ID_ = :deployment
                          AND orchestration.ENGINE_PROC_DEF_ID_ = :processDefinitionId
                          AND orchestration.ENGINE_PROC_DEF_KEY_ = :processDefinitionKey
                          AND orchestration.ENGINE_PROC_DEF_VER_ = :processDefinitionVersion
                          AND (orchestration.ENGINE_TENANT_ID_ = :engineTenant
                            OR (:engineTenant IS NULL
                              AND orchestration.ENGINE_TENANT_ID_ IS NULL)))""")
                    .param("deployment", binding.engineIdentity().deploymentId())
                    .param("processDefinitionId", binding.engineIdentity().processDefinitionId())
                    .param("processDefinitionKey", binding.engineIdentity().processDefinitionKey())
                    .param("processDefinitionVersion", binding.engineIdentity().processDefinitionVersion())
                    .param("engineTenant", binding.engineIdentity().tenantId())
                    .param("activatedAt", binding.activatedAt())
                    .param("definition", binding.caseDefinitionId())
                    .param("key", binding.caseDefinitionKey())
                    .param("tenant", binding.tenantId())
                    .update();
            if (activated != 1) {
                throw lifecycleConflict(binding.caseDefinitionId(),
                        "binding state changed concurrently during activation");
            }
        } catch (DataIntegrityViolationException conflict) {
            throw lifecycleConflict(binding.caseDefinitionId(),
                    "another version became ACTIVE concurrently");
        }
    }

    /**
     * Locks the complete existing authority for one tenant/key before activation decisions.
     * Every report for an already-created business version therefore takes the same row locks in
     * the same order. The candidate and current expected (highest) business version are derived
     * from that locked set, so an older report cannot race a sibling version into authority.
     */
    private ActivationAuthority lockAuthorityAndRequireCandidateVersion(
            CaseDefinitionVersionBinding binding) {
        List<DefinitionVersion> authority = jdbc.sql("""
                SELECT ID_, VERSION_NO_
                FROM CM_CASE_DEF
                WHERE KEY_ = :key
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))
                ORDER BY VERSION_NO_
                FOR UPDATE""")
                .param("key", binding.caseDefinitionKey())
                .param("tenant", binding.tenantId())
                .query((rs, rowNum) -> new DefinitionVersion(
                        rs.getString("ID_"), rs.getInt("VERSION_NO_")))
                .list();
        int candidateVersion = authority.stream()
                .filter(version -> version.definitionId().equals(binding.caseDefinitionId()))
                .mapToInt(DefinitionVersion::version)
                .findFirst()
                .orElseThrow(() -> lifecycleConflict(binding.caseDefinitionId(),
                        "candidate is not part of the locked definition authority"));
        int expectedVersion = authority.stream()
                .mapToInt(DefinitionVersion::version)
                .max()
                .orElseThrow();
        return new ActivationAuthority(candidateVersion, expectedVersion);
    }

    private void markSuperseded(String definitionId, int candidateVersion, int expectedVersion) {
        String detail = "Binding for business version " + candidateVersion
                + " was superseded by newer business version " + expectedVersion;
        int updated = jdbc.sql("""
                UPDATE CM_CASE_DEF_BINDING
                SET DEPLOY_STATUS_ = 'FAILED', STATUS_ = 'FAILED',
                    FAILURE_DETAIL_ = :failure
                WHERE CASE_DEF_ID_ = :definition AND STATUS_ = 'DRAFT'""")
                .param("failure", detail)
                .param("definition", definitionId)
                .update();
        if (updated != 1) {
            throw lifecycleConflict(definitionId,
                    "binding state changed concurrently before supersession was recorded");
        }
    }

    private int eligibleForActivation(CaseDefinitionVersionBinding binding) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM CM_CASE_DEF_BINDING candidate
                JOIN CM_CASE_DEF_RELEASE orchestration
                  ON orchestration.ID_ = candidate.ORCH_RELEASE_ID_
                JOIN CM_CASE_DEF_RELEASE contract
                  ON contract.ID_ = candidate.CONTRACT_RELEASE_ID_
                JOIN CM_CASE_DEF_RELEASE presentation
                  ON presentation.ID_ = candidate.PRESENT_RELEASE_ID_
                WHERE candidate.CASE_DEF_ID_ = :definition
                  AND candidate.CASE_DEF_KEY_ = :key
                  AND (candidate.TENANT_ID_ = :tenant
                    OR (:tenant IS NULL AND candidate.TENANT_ID_ IS NULL))
                  AND candidate.STATUS_ = 'DRAFT'
                  AND orchestration.STATUS_ = 'ACTIVE'
                  AND contract.STATUS_ = 'ACTIVE'
                  AND presentation.STATUS_ = 'ACTIVE'
                  AND orchestration.SHA256_ = candidate.ORCH_SHA256_
                  AND contract.SHA256_ = candidate.CONTRACT_SHA256_
                  AND presentation.SHA256_ = candidate.PRESENT_SHA256_
                  AND orchestration.ENGINE_DEPLOYMENT_ID_ = :deployment
                  AND orchestration.ENGINE_PROC_DEF_ID_ = :processDefinitionId
                  AND orchestration.ENGINE_PROC_DEF_KEY_ = :processDefinitionKey
                  AND orchestration.ENGINE_PROC_DEF_VER_ = :processDefinitionVersion
                  AND (orchestration.ENGINE_TENANT_ID_ = :engineTenant
                    OR (:engineTenant IS NULL AND orchestration.ENGINE_TENANT_ID_ IS NULL))""")
                .param("definition", binding.caseDefinitionId())
                .param("key", binding.caseDefinitionKey())
                .param("tenant", binding.tenantId())
                .param("deployment", binding.engineIdentity().deploymentId())
                .param("processDefinitionId", binding.engineIdentity().processDefinitionId())
                .param("processDefinitionKey", binding.engineIdentity().processDefinitionKey())
                .param("processDefinitionVersion", binding.engineIdentity().processDefinitionVersion())
                .param("engineTenant", binding.engineIdentity().tenantId())
                .query(Integer.class).single();
    }

    private static CaseConflictException lifecycleConflict(String definitionId, String reason) {
        return new CaseConflictException("binding-lifecycle-conflict",
                "Binding '" + definitionId + "' could not activate: " + reason, List.of());
    }

    @Override
    public EngineDeploymentIdentity requireActive(String caseDefinitionId, String tenantId) {
        return jdbc.sql("""
                SELECT ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                       ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_
                FROM CM_CASE_DEF_BINDING
                WHERE CASE_DEF_ID_ = :definition AND STATUS_ = 'ACTIVE'
                  AND ORCHESTRATION_MODE_ = 'BPMN'
                  AND ENGINE_DEPLOYMENT_ID_ IS NOT NULL
                  AND ENGINE_PROC_DEF_ID_ IS NOT NULL
                  AND ENGINE_PROC_DEF_KEY_ IS NOT NULL
                  AND ENGINE_PROC_DEF_VER_ IS NOT NULL
                  AND (ENGINE_TENANT_ID_ = :tenant
                    OR (:tenant IS NULL AND ENGINE_TENANT_ID_ IS NULL))""")
                .param("definition", caseDefinitionId)
                .param("tenant", tenantId)
                .query((rs, rowNum) -> identity(
                        rs.getString("ENGINE_DEPLOYMENT_ID_"),
                        rs.getString("ENGINE_PROC_DEF_ID_"),
                        rs.getString("ENGINE_PROC_DEF_KEY_"),
                        integer(rs.getObject("ENGINE_PROC_DEF_VER_")),
                        rs.getString("ENGINE_TENANT_ID_")))
                .optional()
                .orElseThrow(() -> new CaseConflictException(
                        "case-definition-binding-not-active",
                        "Case definition '" + caseDefinitionId
                                + "' has no ACTIVE BPMN binding for tenant '"
                                + (tenantId == null ? "<none>" : tenantId) + "'",
                        List.of()));
    }

    private static EngineDeploymentIdentity identity(
            String deploymentId, String processDefinitionId, String processDefinitionKey,
            Integer processDefinitionVersion, String tenantId) {
        if (deploymentId == null || processDefinitionId == null || processDefinitionKey == null
                || processDefinitionVersion == null) return null;
        return new EngineDeploymentIdentity(deploymentId, processDefinitionId,
                processDefinitionKey, processDefinitionVersion, tenantId);
    }

    private static String deploymentId(EngineDeploymentIdentity identity) {
        return identity == null ? null : identity.deploymentId();
    }

    private static String processDefinitionId(EngineDeploymentIdentity identity) {
        return identity == null ? null : identity.processDefinitionId();
    }

    private static String processDefinitionKey(EngineDeploymentIdentity identity) {
        return identity == null ? null : identity.processDefinitionKey();
    }

    private static Integer processDefinitionVersion(EngineDeploymentIdentity identity) {
        return identity == null ? null : identity.processDefinitionVersion();
    }

    private static String engineTenant(EngineDeploymentIdentity identity) {
        return identity == null ? null : identity.tenantId();
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private record DefinitionVersion(String definitionId, int version) {}

    private record ActivationAuthority(int candidateVersion, int expectedVersion) {}
}
