package org.casemgmt.projection;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

public final class ActiveBpmnCaseRepository {

    public record ActiveCase(String caseId, String rootProcessInstanceId) { }

    private final JdbcClient jdbc;

    public ActiveBpmnCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ActiveCase> findAll() {
        return jdbc.sql("""
                SELECT ID_, ROOT_PROC_INST_ID_ FROM CM_CASE
                WHERE STATE_ = 'ACTIVE' AND ROOT_PROC_INST_ID_ IS NOT NULL""")
                .query((rs, n) -> new ActiveCase(rs.getString("ID_"),
                        rs.getString("ROOT_PROC_INST_ID_")))
                .list();
    }
}
