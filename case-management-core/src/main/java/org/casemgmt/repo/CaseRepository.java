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
     *
     * <p>Deliberately does NOT re-read the row after the UPDATE to build its return value.
     * This module runs with no transaction boundary (no {@code @Transactional}, a plain
     * pooled {@code DataSource}), so the UPDATE and a follow-up SELECT would be two
     * independently auto-committed statements with nothing tying them together. If another
     * writer's UPDATE landed and committed in the gap between this call's UPDATE and its
     * SELECT, the SELECT would silently return THAT writer's state and version — this
     * caller would get no exception and would reasonably (but wrongly) believe the returned
     * object, including its version/ETag, confirmed its own write. Since the WHERE clause
     * already proves this call's UPDATE matched exactly one row at {@code expectedVersion},
     * the post-state is fully known without asking the database again: same row, same
     * columns this call set, version incremented by exactly one. Constructing it locally
     * is both correct (no window for another writer's commit to be misattributed) and one
     * round trip cheaper than the read-back this replaced.
     *
     * <p>UPDATED_AT_ is set from a single Java-side {@code OffsetDateTime.now()} captured
     * before the UPDATE and bound explicitly as a parameter (not left to SQL's
     * {@code SYSTIMESTAMP}), so the timestamp written to the row and the timestamp on the
     * returned object are the exact same value — no second read needed to learn what the
     * server actually stored.
     *
     * <p><b>{@code SLA_STATUS_} is deliberately absent from the SET list (fix round 2, review
     * finding "SLA_STATUS_ is now silently stompable"):</b> this method's own Javadoc above
     * explains why it never re-reads before returning — the caller's in-memory {@code
     * CaseInstance} is trusted as current for every column THIS method owns. {@code SLA_STATUS_}
     * is not one of them; {@code SlaSweeper} owns it exclusively via {@link
     * #updateSlaStatusMonotonic}. Before this fix, a full-row {@code update} call built from a
     * case read BEFORE a concurrent sweep committed a breach would carry that stale value
     * (typically {@code NONE} or {@code WARNING}) right back over the sweeper's write — the
     * optimistic {@code VERSION_} check does not protect this column at all, since the sweeper's
     * write deliberately does not bump {@code VERSION_} (see {@link #updateSlaStatusMonotonic}),
     * so the user's stale-read UPDATE still matches and silently overwrites {@code BREACHED} with
     * whatever the user last saw — permanently, since a record already {@code BREACHED} is never
     * re-selected by {@link org.casemgmt.repo.SlaRepository#dueRecords} and nothing else
     * re-derives the column. Dropping the column from this SET list closes that window
     * completely: nothing this method writes can ever race the sweeper's column again. The
     * returned {@link CaseInstance}'s {@code slaStatus()} still reflects the caller's
     * (possibly now-stale) view rather than a fresh read — consistent with this method's
     * no-re-read contract for every other column — but that staleness can no longer reach the
     * database.
     */
    public CaseInstance update(CaseInstance c, long expectedVersion) {
        OffsetDateTime updatedAt = OffsetDateTime.now();
        String slaStatus = c.slaStatus() == null ? "NONE" : c.slaStatus();

        int rows = jdbc.sql("""
                UPDATE CM_CASE SET
                    TITLE_ = :title, STATE_ = :state, PRIORITY_ = :priority,
                    ASSIGNEE_ = :assignee, QUEUE_ID_ = :queueId,
                    OUTCOME_ = :outcome, CANCEL_REASON_ = :cancelReason,
                    VARIABLES_JSON_ = :variables, CLOSED_AT_ = :closedAt,
                    UPDATED_AT_ = :updatedAt, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("closedAt", c.closedAt())
            .param("updatedAt", updatedAt)
            .param("id", c.id()).param("expected", expectedVersion)
            .update();

        if (rows == 0) {
            throw new OptimisticLockException("Case", c.id(), expectedVersion);
        }
        return new CaseInstance(c.id(), c.engineId(), c.tenantId(), c.caseDefId(), c.caseDefKey(),
                c.caseDefVersion(), c.businessKey(), c.title(), c.state(), c.priority(),
                c.assignee(), c.queueId(), c.initiator(), slaStatus, c.outcome(), c.cancelReason(),
                c.variables(), expectedVersion + 1, c.createdAt(), updatedAt, c.closedAt());
    }

    /**
     * Targeted, versionless write of the denormalised {@code SLA_STATUS_} column only — added
     * for {@code SlaSweeper} (Task 21 fix round 1, review finding I2/I3).
     *
     * <p>Deliberately NOT the full-row {@link #update} above, and deliberately does not bump
     * {@code VERSION_}: {@code SlaSweeper} runs on a schedule (Task 26: every 60s) against every
     * case with a due SLA clock, and {@code update}'s optimistic {@code VERSION_} check makes it
     * collide with ANY ordinary user editing ANY unrelated field on the SAME case at the SAME
     * time — not a rare race, a routine one, on a live user-facing table. A plain user edit
     * should never fail with 412 just because the sweeper happened to run a moment earlier, and
     * the sweeper should never have to retry (or abort a whole batch, see {@code SlaSweeper}'s
     * Javadoc) just because a user saved the case's title. {@code SLA_STATUS_} is explicitly
     * documented as denormalized in {@code db-design.sql} for exactly this reason: it is owned by
     * the sweeper, not by the user's optimistic version. Since fix round 2, {@link #update} above
     * no longer writes this column at all (see its Javadoc) — this method is now the ONLY writer
     * of {@code SLA_STATUS_} after row creation, not merely the recommended one.
     *
     * <p>Monotonic against downgrade from {@code BREACHED}: {@link
     * org.casemgmt.repo.SlaRepository#dueRecords} has no stable ordering, so a case with one
     * target already breached and another only warning could otherwise have its status flip
     * depending on which record the sweeper happens to process last in a batch, or which of two
     * separate sweeps runs later — a warning must never mask a breach. The {@code WHERE} clause
     * below is the single, race-free place that rule is enforced: it reads and compares the
     * current value in the same statement as the write, so there is no read-then-write gap for a
     * concurrent sweep to land in between.
     *
     * @return true if a row was actually changed (false for an unknown case id, or a same-status
     *         no-op, or a rejected downgrade — none of which the sweeper needs to react to)
     */
    public boolean updateSlaStatusMonotonic(String caseId, String status) {
        int rows = jdbc.sql("""
                UPDATE CM_CASE SET SLA_STATUS_ = :status
                WHERE ID_ = :id AND SLA_STATUS_ <> :status
                  AND NOT (SLA_STATUS_ = 'BREACHED' AND :status = 'WARNING')""")
            .param("status", status).param("id", caseId).update();
        return rows > 0;
    }

    public List<CaseInstance> query(CaseQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM CM_CASE WHERE 1 = 1");
        List<Object[]> params = new ArrayList<>();
        if (q.tenantId() != null)    { sql.append(" AND TENANT_ID_ = :tenantId");     params.add(new Object[]{"tenantId", q.tenantId()}); }
        if (!q.states().isEmpty())   { sql.append(" AND STATE_ IN (:states)");
                                       params.add(new Object[]{"states",
                                               q.states().stream().map(CaseState::name).toList()}); }
        if (q.assignee() != null)    { sql.append(" AND ASSIGNEE_ = :assignee");      params.add(new Object[]{"assignee", q.assignee()}); }
        if (q.caseDefKey() != null)  { sql.append(" AND CASE_DEF_KEY_ = :defKey");    params.add(new Object[]{"defKey", q.caseDefKey()}); }
        if (q.businessKey() != null) { sql.append(" AND BUSINESS_KEY_ = :bk");        params.add(new Object[]{"bk", q.businessKey()}); }
        // CREATED_AT_ alone is not a stable sort key: rows created in the same instant (or
        // truncated to the same stored precision) would otherwise have undefined relative
        // order between paginated calls, which can skip or duplicate rows across pages in a
        // worklist. ID_ is unique, so it makes the ordering — and therefore the pagination —
        // deterministic.
        sql.append(" ORDER BY CREATED_AT_ DESC, ID_ ASC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");

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
