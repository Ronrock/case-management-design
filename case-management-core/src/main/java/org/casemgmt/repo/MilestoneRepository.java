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

    /**
     * Idempotent by SQL: the {@code ACHIEVED_ = 0} predicate means only the FIRST caller to reach
     * this UPDATE for a given milestone ever actually flips the row, no matter how many callers
     * race here concurrently. Returns the number of rows the UPDATE actually matched (0 or 1) so
     * a caller — {@link org.casemgmt.service.MilestoneService#achieve}, in particular — can tell
     * "I just achieved it" (1) apart from "someone else already had" (0) using the SAME statement
     * that performed the write, rather than a separate SELECT taken before the UPDATE. A
     * pre-UPDATE read cannot safely answer that question under concurrency: two callers can both
     * read {@code achieved = false} before either has written, race to this UPDATE, and — if the
     * decision were based on that earlier read instead of this return value — both would believe
     * they were the one who achieved it and both would publish a {@code milestone.achieved}
     * event. Oracle serialises the two UPDATEs against the same row (the second blocks on the
     * first's row lock, then re-evaluates {@code ACHIEVED_ = 0} against the post-commit value once
     * unblocked), so exactly one of them ever sees {@code rows == 1}.
     */
    public int achieve(String milestoneId, String actor) {
        return jdbc.sql("""
                UPDATE CM_MILESTONE SET ACHIEVED_ = 1, ACHIEVED_AT_ = SYSTIMESTAMP, ACHIEVED_BY_ = :actor
                WHERE ID_ = :id AND ACHIEVED_ = 0""")
            .param("actor", actor).param("id", milestoneId).update();
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
