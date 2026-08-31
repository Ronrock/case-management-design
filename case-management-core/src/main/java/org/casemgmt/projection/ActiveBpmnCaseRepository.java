package org.casemgmt.projection;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.List;

public final class ActiveBpmnCaseRepository {

    public record ActiveCase(String caseId, String rootProcessInstanceId, String tenantId,
                             String engineId) { }

    /** A retained engine-process identity whose complete history can rebuild an active case. */
    public record ReconciliationProcess(String caseId, String tenantId, String engineId,
                                        String processInstanceId, String processDefinitionId,
                                        boolean root) { }

    private final JdbcClient jdbc;

    public ActiveBpmnCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ActiveCase> findAll() {
        return jdbc.sql("""
                SELECT ID_, ROOT_PROC_INST_ID_, TENANT_ID_, ENGINE_ID_ FROM CM_CASE
                WHERE STATE_ = 'ACTIVE' AND ROOT_PROC_INST_ID_ IS NOT NULL""")
                .query((rs, n) -> new ActiveCase(rs.getString("ID_"),
                        rs.getString("ROOT_PROC_INST_ID_"), rs.getString("TENANT_ID_"),
                        rs.getString("ENGINE_ID_")))
                .list();
    }

    /**
     * Returns each active case root and every retained linked-process identity.  Linked rows are
     * intentionally not filtered by their projected lifecycle state: remote history is the
     * evidence that repairs a stale projection.
     */
    public List<ReconciliationProcess> findAllProcessesForActiveCases() {
        List<ReconciliationProcess> rows = jdbc.sql("""
                SELECT c.ID_ AS CASE_ID_, c.TENANT_ID_, c.ENGINE_ID_,
                       c.ROOT_PROC_INST_ID_ AS PROC_INST_ID_, root_link.PROC_DEF_ID_, 1 AS IS_ROOT_
                FROM CM_CASE c
                LEFT JOIN CM_LINKED_PROCESS root_link
                  ON root_link.CASE_ID_ = c.ID_
                 AND root_link.PROC_INST_ID_ = c.ROOT_PROC_INST_ID_
                WHERE c.STATE_ = 'ACTIVE' AND c.ROOT_PROC_INST_ID_ IS NOT NULL
                UNION ALL
                SELECT c.ID_ AS CASE_ID_, c.TENANT_ID_, c.ENGINE_ID_,
                       linked.PROC_INST_ID_, linked.PROC_DEF_ID_, 0 AS IS_ROOT_
                FROM CM_CASE c
                JOIN CM_LINKED_PROCESS linked ON linked.CASE_ID_ = c.ID_
                WHERE c.STATE_ = 'ACTIVE' AND linked.PROC_INST_ID_ IS NOT NULL
                """)
                .query((rs, n) -> new ReconciliationProcess(rs.getString("CASE_ID_"),
                        rs.getString("TENANT_ID_"), rs.getString("ENGINE_ID_"),
                        rs.getString("PROC_INST_ID_"), rs.getString("PROC_DEF_ID_"),
                        rs.getBoolean("IS_ROOT_")))
                .list();
        var processes = new LinkedHashMap<String, ReconciliationProcess>();
        for (ReconciliationProcess row : rows) processes.putIfAbsent(row.processInstanceId(), row);
        return List.copyOf(processes.values());
    }
}
