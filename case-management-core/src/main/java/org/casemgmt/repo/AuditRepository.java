package org.casemgmt.repo;

import org.casemgmt.domain.CaseIds;
import org.springframework.jdbc.core.simple.JdbcClient;

public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Append-only compliance record. Separate from CM_EVENT: different retention, different audience. */
    public void record(String caseId, String tenantId, String actor, String action,
                       String resourceType, String resourceId, Object before, Object after) {
        jdbc.sql("""
                INSERT INTO CM_AUDIT_LOG (ID_, CASE_ID_, TENANT_ID_, ACTOR_, ACTION_,
                    RESOURCE_TYPE_, RESOURCE_ID_, BEFORE_JSON_, AFTER_JSON_)
                VALUES (:id, :caseId, :tenant, :actor, :action, :type, :resourceId, :before, :after)""")
            .param("id", CaseIds.newId()).param("caseId", caseId).param("tenant", tenantId)
            .param("actor", actor).param("action", action).param("type", resourceType)
            .param("resourceId", resourceId)
            .param("before", JsonCodec.toJson(before)).param("after", JsonCodec.toJson(after))
            .update();
    }
}
