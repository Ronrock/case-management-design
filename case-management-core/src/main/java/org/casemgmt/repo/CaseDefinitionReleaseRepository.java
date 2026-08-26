package org.casemgmt.repo;

import org.casemgmt.error.NotFoundException;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
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
                   STATUS_, ENGINE_DEPLOYMENT_ID_, FAILURE_DETAIL_, PUBLISHED_AT_, PUBLISHED_BY_)
                VALUES
                  (:id, :key, :tenant, :kind, :media, :content, :sha,
                   :status, :deployment, :failure, :publishedAt, :publishedBy)""")
                .param("id", release.id())
                .param("key", release.definitionKey())
                .param("tenant", release.tenantId())
                .param("kind", release.kind().name())
                .param("media", release.mediaType())
                .param("content", release.content())
                .param("sha", release.sha256())
                .param("status", release.status().name())
                .param("deployment", release.engineDeploymentId())
                .param("failure", release.failureDetail())
                .param("publishedAt", release.publishedAt())
                .param("publishedBy", release.publishedBy())
                .update();
    }

    public Optional<CaseDefinitionRelease> findByDigest(
            String tenantId, String key, ReleaseKind kind, String sha256) {
        return jdbc.sql("""
                SELECT ID_, CASE_DEF_KEY_, TENANT_ID_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_,
                       STATUS_, ENGINE_DEPLOYMENT_ID_, FAILURE_DETAIL_, PUBLISHED_AT_, PUBLISHED_BY_
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
                        rs.getString("ENGINE_DEPLOYMENT_ID_"), rs.getString("FAILURE_DETAIL_"),
                        rs.getObject("PUBLISHED_AT_", OffsetDateTime.class),
                        rs.getString("PUBLISHED_BY_")))
                .optional();
    }

    public CaseDefinitionRelease require(String id, String tenantId) {
        return jdbc.sql("""
                SELECT ID_, CASE_DEF_KEY_, TENANT_ID_, KIND_, MEDIA_TYPE_, CONTENT_, SHA256_,
                       STATUS_, ENGINE_DEPLOYMENT_ID_, FAILURE_DETAIL_, PUBLISHED_AT_, PUBLISHED_BY_
                FROM CM_CASE_DEF_RELEASE WHERE ID_ = :id
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                .param("id", id).param("tenant", tenantId)
                .query((rs, rowNum) -> new CaseDefinitionRelease(
                        rs.getString("ID_"), rs.getString("CASE_DEF_KEY_"),
                        rs.getString("TENANT_ID_"), ReleaseKind.valueOf(rs.getString("KIND_")),
                        rs.getString("MEDIA_TYPE_"), rs.getBytes("CONTENT_"),
                        rs.getString("SHA256_"), ReleaseStatus.valueOf(rs.getString("STATUS_")),
                        rs.getString("ENGINE_DEPLOYMENT_ID_"), rs.getString("FAILURE_DETAIL_"),
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
                               String engineDeploymentId, String failureDetail) {
        jdbc.sql("""
                UPDATE CM_CASE_DEF_RELEASE
                SET STATUS_ = :status, ENGINE_DEPLOYMENT_ID_ = :deployment,
                    FAILURE_DETAIL_ = :failure
                WHERE ID_ = :id""")
                .param("status", status.name())
                .param("deployment", engineDeploymentId)
                .param("failure", failureDetail)
                .param("id", releaseId)
                .update();
    }
}
