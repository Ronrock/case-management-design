package org.casemgmt.repo;

import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Optional;

public class CaseDefinitionVersionBindingRepository {

    private final JdbcClient jdbc;

    public CaseDefinitionVersionBindingRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void insert(CaseDefinitionVersionBinding binding) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF_BINDING
                  (CASE_DEF_ID_, ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                   CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                   BOUND_AT_, BOUND_BY_)
                VALUES
                  (:definition, :orchestration, :orchestrationSha, :contract, :contractSha,
                   :presentation, :presentationSha, :status, :boundAt, :boundBy)""")
                .param("definition", binding.caseDefinitionId())
                .param("orchestration", binding.orchestrationReleaseId())
                .param("orchestrationSha", binding.orchestrationSha256())
                .param("contract", binding.contractReleaseId())
                .param("contractSha", binding.contractSha256())
                .param("presentation", binding.presentationReleaseId())
                .param("presentationSha", binding.presentationSha256())
                .param("status", binding.deploymentStatus().name())
                .param("boundAt", binding.boundAt())
                .param("boundBy", binding.boundBy())
                .update();
    }

    public Optional<CaseDefinitionVersionBinding> find(String caseDefinitionId) {
        return jdbc.sql("""
                SELECT CASE_DEF_ID_, ORCH_RELEASE_ID_, ORCH_SHA256_, CONTRACT_RELEASE_ID_,
                       CONTRACT_SHA256_, PRESENT_RELEASE_ID_, PRESENT_SHA256_, DEPLOY_STATUS_,
                       BOUND_AT_, BOUND_BY_
                FROM CM_CASE_DEF_BINDING WHERE CASE_DEF_ID_ = :id""")
                .param("id", caseDefinitionId)
                .query((rs, rowNum) -> new CaseDefinitionVersionBinding(
                        rs.getString("CASE_DEF_ID_"), rs.getString("ORCH_RELEASE_ID_"),
                        rs.getString("ORCH_SHA256_"), rs.getString("CONTRACT_RELEASE_ID_"),
                        rs.getString("CONTRACT_SHA256_"), rs.getString("PRESENT_RELEASE_ID_"),
                        rs.getString("PRESENT_SHA256_"),
                        ReleaseStatus.valueOf(rs.getString("DEPLOY_STATUS_")),
                        rs.getObject("BOUND_AT_", OffsetDateTime.class),
                        rs.getString("BOUND_BY_")))
                .optional();
    }

    public void markDeploymentByRelease(String orchestrationReleaseId, ReleaseStatus status) {
        jdbc.sql("""
                UPDATE CM_CASE_DEF_BINDING SET DEPLOY_STATUS_ = :status
                WHERE ORCH_RELEASE_ID_ = :release""")
                .param("status", status.name())
                .param("release", orchestrationReleaseId)
                .update();
    }
}
