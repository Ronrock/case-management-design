package org.casemgmt.repo;

import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class CaseDefinitionReleaseRepository {

    private final JdbcClient jdbc;

    public CaseDefinitionReleaseRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void insert(CaseDefinitionRelease release) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF_RELEASE
                  (ID_, CASE_DEF_KEY_, TENANT_ID_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_,
                   STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                   ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_, FAILURE_DETAIL_, PUBLISHED_AT_,
                   ACTIVATED_AT_, PUBLISHED_BY_)
                VALUES
                  (:id, :key, :tenant, :kind, :media, :content, :sha,
                   :status, :deployment, :processDefinitionId, :processDefinitionKey,
                   :processDefinitionVersion, :engineTenant, :failure, :publishedAt,
                   :activatedAt, :publishedBy)""")
                .param("id", release.id())
                .param("key", release.definitionKey())
                .param("tenant", release.tenantId())
                .param("kind", release.kind().name())
                .param("media", release.mediaType())
                .param("content", release.content())
                .param("sha", release.sha256())
                .param("status", release.status().name())
                .param("deployment", release.engineDeploymentId())
                .param("processDefinitionId", release.engineProcessDefinitionId())
                .param("processDefinitionKey", release.engineProcessDefinitionKey())
                .param("processDefinitionVersion", release.engineProcessDefinitionVersion())
                .param("engineTenant", release.engineTenantId())
                .param("failure", release.failureDetail())
                .param("publishedAt", release.publishedAt())
                .param("activatedAt", release.status() == ReleaseStatus.ACTIVE
                        ? release.publishedAt() : null)
                .param("publishedBy", release.publishedBy())
                .update();
    }

    public Optional<CaseDefinitionRelease> findByDigest(
            String tenantId, String key, ReleaseKind kind, String sha256) {
        return jdbc.sql("""
                SELECT ID_, CASE_DEF_KEY_, TENANT_ID_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_,
                       STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                       ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_, FAILURE_DETAIL_, PUBLISHED_AT_,
                       PUBLISHED_BY_
                FROM CM_CASE_DEF_RELEASE
                WHERE CASE_DEF_KEY_ = :key AND KIND_ = :kind AND SHA256_ = :sha
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                .param("key", key).param("kind", kind.name()).param("sha", sha256)
                .param("tenant", tenantId)
                .query((rs, rowNum) -> new CaseDefinitionRelease(
                        rs.getString("ID_"), rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"), ReleaseKind.valueOf(rs.getString("KIND_")),
                        rs.getString("MEDIA_TYPE_"), rs.getBytes("CONTENT_"),
                        rs.getString("SHA256_"), ReleaseStatus.valueOf(rs.getString("STATUS_")),
                        rs.getString("ENGINE_DEPLOYMENT_ID_"), rs.getString("ENGINE_PROC_DEF_ID_"),
                        rs.getString("ENGINE_PROC_DEF_KEY_"),
                        integer(rs.getObject("ENGINE_PROC_DEF_VER_")),
                        rs.getString("ENGINE_TENANT_ID_"), rs.getString("FAILURE_DETAIL_"),
                        rs.getObject("PUBLISHED_AT_", OffsetDateTime.class),
                        rs.getString("PUBLISHED_BY_")))
                .optional();
    }

    public CaseDefinitionRelease require(String id, String tenantId) {
        return jdbc.sql("""
                SELECT ID_, CASE_DEF_KEY_, TENANT_ID_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_,
                       STATUS_, ENGINE_DEPLOYMENT_ID_, ENGINE_PROC_DEF_ID_, ENGINE_PROC_DEF_KEY_,
                       ENGINE_PROC_DEF_VER_, ENGINE_TENANT_ID_, FAILURE_DETAIL_, PUBLISHED_AT_,
                       PUBLISHED_BY_
                FROM CM_CASE_DEF_RELEASE WHERE ID_ = :id
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                .param("id", id).param("tenant", tenantId)
                .query((rs, rowNum) -> new CaseDefinitionRelease(
                        rs.getString("ID_"), rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"), ReleaseKind.valueOf(rs.getString("KIND_")),
                        rs.getString("MEDIA_TYPE_"), rs.getBytes("CONTENT_"),
                        rs.getString("SHA256_"), ReleaseStatus.valueOf(rs.getString("STATUS_")),
                        rs.getString("ENGINE_DEPLOYMENT_ID_"), rs.getString("ENGINE_PROC_DEF_ID_"),
                        rs.getString("ENGINE_PROC_DEF_KEY_"),
                        integer(rs.getObject("ENGINE_PROC_DEF_VER_")),
                        rs.getString("ENGINE_TENANT_ID_"), rs.getString("FAILURE_DETAIL_"),
                        rs.getObject("PUBLISHED_AT_", OffsetDateTime.class),
                        rs.getString("PUBLISHED_BY_")))
                .optional().orElseThrow(() -> new NotFoundException("CaseDefinitionRelease", id));
    }

    public List<CaseDefinitionRelease> list(String key, String tenantId, ReleaseKind kind) {
        return jdbc.sql("""
                SELECT ID_ FROM CM_CASE_DEF_RELEASE
                WHERE CASE_DEF_KEY_ = :key AND KIND_ = :kind
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))
                ORDER BY PUBLISHED_AT_ DESC""")
                .param("key", key).param("kind", kind.name()).param("tenant", tenantId)
                .query(String.class).list().stream()
                .map(id -> require(id, tenantId)).toList();
    }

    public void markDeployment(String releaseId, ReleaseStatus status,
                               EngineDeploymentIdentity identity, String failureDetail) {
        ReleaseStatus.DEPLOYING.transitionTo(status);
        if (status == ReleaseStatus.ACTIVE && identity == null) {
            throw new IllegalArgumentException(
                    "An ACTIVE deployment report requires an exact engine identity");
        }
        if (status == ReleaseStatus.FAILED && identity != null) {
            throw new IllegalArgumentException(
                    "A FAILED deployment report must not carry an engine identity");
        }
        int updated = jdbc.sql("""
                UPDATE CM_CASE_DEF_RELEASE
                SET STATUS_ = :status, ENGINE_DEPLOYMENT_ID_ = :deployment,
                    ENGINE_PROC_DEF_ID_ = :processDefinitionId,
                    ENGINE_PROC_DEF_KEY_ = :processDefinitionKey,
                    ENGINE_PROC_DEF_VER_ = :processDefinitionVersion,
                    ENGINE_TENANT_ID_ = :engineTenant,
                    FAILURE_DETAIL_ = :failure,
                    ACTIVATED_AT_ = CASE WHEN :status = 'ACTIVE'
                        THEN SYSTIMESTAMP ELSE ACTIVATED_AT_ END
                WHERE ID_ = :id AND STATUS_ = 'DEPLOYING' AND KIND_ = 'ORCHESTRATION'
                  AND (:status != 'ACTIVE'
                    OR (CASE_DEF_KEY_ = :processDefinitionKey
                      AND (TENANT_ID_ = :engineTenant
                        OR (:engineTenant IS NULL AND TENANT_ID_ IS NULL))))""")
                .param("status", status.name())
                .param("deployment", identity == null ? null : identity.deploymentId())
                .param("processDefinitionId", identity == null ? null : identity.processDefinitionId())
                .param("processDefinitionKey", identity == null ? null : identity.processDefinitionKey())
                .param("processDefinitionVersion", identity == null ? null : identity.processDefinitionVersion())
                .param("engineTenant", identity == null ? null : identity.tenantId())
                .param("failure", failureDetail)
                .param("id", releaseId)
                .update();
        if (updated == 1 || alreadyReported(releaseId, status, identity)) return;
        throw new CaseConflictException("release-lifecycle-conflict",
                "Release '" + releaseId + "' was not DEPLOYING with a matching key and tenant",
                List.of());
    }

    private boolean alreadyReported(String releaseId, ReleaseStatus status,
                                    EngineDeploymentIdentity identity) {
        if (status == ReleaseStatus.FAILED) {
            return jdbc.sql("""
                    SELECT COUNT(*) FROM CM_CASE_DEF_RELEASE
                    WHERE ID_ = :id AND STATUS_ = 'FAILED'""")
                    .param("id", releaseId).query(Integer.class).single() == 1;
        }
        return jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_RELEASE
                WHERE ID_ = :id AND STATUS_ = 'ACTIVE'
                  AND ENGINE_DEPLOYMENT_ID_ = :deployment
                  AND ENGINE_PROC_DEF_ID_ = :processDefinitionId
                  AND ENGINE_PROC_DEF_KEY_ = :processDefinitionKey
                  AND ENGINE_PROC_DEF_VER_ = :processDefinitionVersion
                  AND (ENGINE_TENANT_ID_ = :engineTenant
                    OR (:engineTenant IS NULL AND ENGINE_TENANT_ID_ IS NULL))""")
                .param("id", releaseId)
                .param("deployment", identity.deploymentId())
                .param("processDefinitionId", identity.processDefinitionId())
                .param("processDefinitionKey", identity.processDefinitionKey())
                .param("processDefinitionVersion", identity.processDefinitionVersion())
                .param("engineTenant", identity.tenantId())
                .query(Integer.class).single() == 1;
    }

    public void transition(String releaseId, ReleaseStatus expected, ReleaseStatus next,
                           EngineDeploymentIdentity identity, String failureDetail) {
        expected.transitionTo(next);
        int updated = jdbc.sql("""
                UPDATE CM_CASE_DEF_RELEASE
                SET STATUS_ = :next,
                    ENGINE_DEPLOYMENT_ID_ = :deployment,
                    ENGINE_PROC_DEF_ID_ = :processDefinitionId,
                    ENGINE_PROC_DEF_KEY_ = :processDefinitionKey,
                    ENGINE_PROC_DEF_VER_ = :processDefinitionVersion,
                    ENGINE_TENANT_ID_ = :engineTenant,
                    FAILURE_DETAIL_ = :failure,
                    VALIDATED_AT_ = CASE WHEN :next = 'VALIDATED'
                        THEN SYSTIMESTAMP ELSE VALIDATED_AT_ END,
                    ACTIVATED_AT_ = CASE WHEN :next = 'ACTIVE'
                        THEN SYSTIMESTAMP ELSE ACTIVATED_AT_ END,
                    RETIRED_AT_ = CASE WHEN :next = 'RETIRED'
                        THEN SYSTIMESTAMP ELSE RETIRED_AT_ END
                WHERE ID_ = :id AND STATUS_ = :expected""")
                .param("next", next.name())
                .param("deployment", identity == null ? null : identity.deploymentId())
                .param("processDefinitionId", identity == null ? null : identity.processDefinitionId())
                .param("processDefinitionKey", identity == null ? null : identity.processDefinitionKey())
                .param("processDefinitionVersion", identity == null ? null : identity.processDefinitionVersion())
                .param("engineTenant", identity == null ? null : identity.tenantId())
                .param("failure", failureDetail)
                .param("id", releaseId)
                .param("expected", expected.name())
                .update();
        if (updated != 1) {
            throw new CaseConflictException("release-lifecycle-conflict",
                    "Release '" + releaseId + "' did not transition from " + expected
                            + " to " + next + "; its state changed concurrently",
                    List.of());
        }
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
