package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class MilestoneRepository {

    public record MilestoneRow(String id, String caseId, String planItemId, String name,
                               boolean achieved, OffsetDateTime achievedAt, String achievedBy) {}

    private final JdbcClient jdbc;

    public MilestoneRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String caseId, String planItemId, String name) {
        jdbc.sql("""
                INSERT INTO CM_MILESTONE (ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_)
                VALUES (:id, :caseId, :planItemId, :name, 0)""")
            .param("id", id).param("caseId", caseId).param("planItemId", planItemId)
            .param("name", name).update();
    }

    public List<MilestoneRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_, ACHIEVED_AT_, ACHIEVED_BY_
                FROM CM_MILESTONE WHERE CASE_ID_ = :caseId""")
            .param("caseId", caseId)
            .query(MilestoneRepository::map)
            .list();
    }

    public Optional<MilestoneRow> findByPlanItem(String planItemId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_, ACHIEVED_AT_, ACHIEVED_BY_
                FROM CM_MILESTONE WHERE PLAN_ITEM_ID_ = :id""")
            .param("id", planItemId)
            .query(MilestoneRepository::map)
            .optional();
    }

    private static MilestoneRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new MilestoneRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                rs.getString("PLAN_ITEM_ID_"), rs.getString("NAME_"),
                rs.getInt("ACHIEVED_") == 1,
                rs.getObject("ACHIEVED_AT_", OffsetDateTime.class),
                rs.getString("ACHIEVED_BY_"));
    }
}
