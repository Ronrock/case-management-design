package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CaseRepository {

    private static final String COLUMNS = """
            ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_,
            BUSINESS_KEY_, TITLE_, STATE_, PRIORITY_, ASSIGNEE_, QUEUE_ID_, INITIATOR_,
            SLA_STATUS_, OUTCOME_, CANCEL_REASON_, VARIABLES_JSON_, VERSION_,
            CREATED_AT_, UPDATED_AT_, CLOSED_AT_""";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CaseInstance c) {
        jdbc.sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_,
                    CASE_DEF_VER_, BUSINESS_KEY_, TITLE_, STATE_, PRIORITY_, ASSIGNEE_, QUEUE_ID_,
                    INITIATOR_, SLA_STATUS_, OUTCOME_, CANCEL_REASON_, VARIABLES_JSON_, VERSION_,
                    CREATED_AT_, UPDATED_AT_, CLOSED_AT_)
                VALUES (:id, :engineId, :tenantId, :caseDefId, :caseDefKey, :caseDefVer,
                    :businessKey, :title, :state, :priority, :assignee, :queueId, :initiator,
                    :slaStatus, :outcome, :cancelReason, :variables, :version,
                    :createdAt, :updatedAt, :closedAt)""")
            .param("id", c.id()).param("engineId", c.engineId()).param("tenantId", c.tenantId())
            .param("caseDefId", c.caseDefId()).param("caseDefKey", c.caseDefKey())
            .param("caseDefVer", c.caseDefVersion()).param("businessKey", c.businessKey())
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId()).param("initiator", c.initiator())
            .param("slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("version", c.version())
            .param("createdAt", c.createdAt()).param("updatedAt", c.updatedAt())
            .param("closedAt", c.closedAt())
            .update();
    }

    public Optional<CaseInstance> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_CASE WHERE ID_ = :id")
                .param("id", id)
                .query(CaseRepository::map)
                .optional();
    }

    public CaseInstance require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Case", id));
    }

    /**
     * Optimistic update. Zero rows affected means someone else wrote first —
     * never retried here, always surfaced as 412 by the REST layer.
     */
    public CaseInstance update(CaseInstance c, long expectedVersion) {
        int rows = jdbc.sql("""
                UPDATE CM_CASE SET
                    TITLE_ = :title, STATE_ = :state, PRIORITY_ = :priority,
                    ASSIGNEE_ = :assignee, QUEUE_ID_ = :queueId, SLA_STATUS_ = :slaStatus,
                    OUTCOME_ = :outcome, CANCEL_REASON_ = :cancelReason,
                    VARIABLES_JSON_ = :variables, CLOSED_AT_ = :closedAt,
                    UPDATED_AT_ = SYSTIMESTAMP, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId())
            .param("slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("closedAt", c.closedAt())
            .param("id", c.id()).param("expected", expectedVersion)
            .update();

        if (rows == 0) {
            throw new OptimisticLockException("Case", c.id(), expectedVersion);
        }
        return require(c.id());
    }

    public List<CaseInstance> query(CaseQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM CM_CASE WHERE 1 = 1");
        List<Object[]> params = new ArrayList<>();
        if (q.tenantId() != null)    { sql.append(" AND TENANT_ID_ = :tenantId");     params.add(new Object[]{"tenantId", q.tenantId()}); }
        if (q.state() != null)       { sql.append(" AND STATE_ = :state");            params.add(new Object[]{"state", q.state().name()}); }
        if (q.assignee() != null)    { sql.append(" AND ASSIGNEE_ = :assignee");      params.add(new Object[]{"assignee", q.assignee()}); }
        if (q.caseDefKey() != null)  { sql.append(" AND CASE_DEF_KEY_ = :defKey");    params.add(new Object[]{"defKey", q.caseDefKey()}); }
        if (q.businessKey() != null) { sql.append(" AND BUSINESS_KEY_ = :bk");        params.add(new Object[]{"bk", q.businessKey()}); }
        sql.append(" ORDER BY CREATED_AT_ DESC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");

        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.param("offset", q.offset())
                   .param("limit", q.limit() <= 0 ? 50 : q.limit())
                   .query(CaseRepository::map)
                   .list();
    }

    private static CaseInstance map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CaseInstance(
                rs.getString("ID_"), rs.getString("ENGINE_ID_"), rs.getString("TENANT_ID_"),
                rs.getString("CASE_DEF_ID_"), rs.getString("CASE_DEF_KEY_"), rs.getInt("CASE_DEF_VER_"),
                rs.getString("BUSINESS_KEY_"), rs.getString("TITLE_"),
                CaseState.valueOf(rs.getString("STATE_")),
                CasePriority.valueOf(rs.getString("PRIORITY_")),
                rs.getString("ASSIGNEE_"), rs.getString("QUEUE_ID_"), rs.getString("INITIATOR_"),
                rs.getString("SLA_STATUS_"), rs.getString("OUTCOME_"), rs.getString("CANCEL_REASON_"),
                JsonCodec.toMap(rs.getString("VARIABLES_JSON_")),
                rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("CLOSED_AT_", OffsetDateTime.class));
    }
}
