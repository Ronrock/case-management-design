package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

public class LinkedProcessRepository {

    public record LinkedProcessRow(String id, String caseId, String planItemId,
                                   String processInstanceId, String processDefinitionKey, String state) {}

    private final JdbcClient jdbc;

    public LinkedProcessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String caseId, String planItemId, String procInstId, String procDefKey) {
        jdbc.sql("""
                INSERT INTO CM_LINKED_PROCESS (ID_, CASE_ID_, PLAN_ITEM_ID_, PROC_INST_ID_,
                    PROC_DEF_KEY_, STATE_)
                VALUES (:id, :caseId, :planItemId, :procInstId, :procDefKey, 'ACTIVE')""")
            .param("id", id).param("caseId", caseId).param("planItemId", planItemId)
            .param("procInstId", procInstId).param("procDefKey", procDefKey).update();
    }

    public void markState(String procInstId, String state) {
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :state,
                    ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN SYSTIMESTAMP ELSE ENDED_AT_ END
                WHERE PROC_INST_ID_ = :procInstId""")
            .param("state", state).param("procInstId", procInstId).update();
    }

    public List<LinkedProcessRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, PROC_INST_ID_, PROC_DEF_KEY_, STATE_
                FROM CM_LINKED_PROCESS WHERE CASE_ID_ = :caseId ORDER BY STARTED_AT_""")
            .param("caseId", caseId)
            .query((rs, n) -> new LinkedProcessRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    rs.getString("PLAN_ITEM_ID_"), rs.getString("PROC_INST_ID_"),
                    rs.getString("PROC_DEF_KEY_"), rs.getString("STATE_")))
            .list();
    }
}
