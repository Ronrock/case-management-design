package org.casemgmt.repo;

import org.casemgmt.engine.EngineCommand;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Persistence for {@code CM_ENGINE_COMMAND} (spec §3.5) — the remote-mode engine command
 * outbox. {@link org.casemgmt.engine.OutboxEngineGateway} enqueues rows in the caller's
 * transaction; {@link org.casemgmt.engine.EngineCommandDispatcher} claims and delivers them.
 */
public class EngineCommandRepository {

    private final JdbcClient jdbc;

    public EngineCommandRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void enqueue(EngineCommand c) {
        jdbc.sql("""
                INSERT INTO CM_ENGINE_COMMAND (ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_,
                    ATTEMPTS_, NEXT_ATTEMPT_AT_)
                VALUES (:id, :caseId, :type, :payload, 'PENDING', 0, SYSTIMESTAMP)""")
            .param("id", c.id()).param("caseId", c.caseId()).param("type", c.type().name())
            .param("payload", JsonCodec.toJson(c.payload()))
            .update();
    }

    /**
     * Claims due commands. {@code FOR UPDATE SKIP LOCKED} is meant to keep multiple app
     * instances from double-sending, but that guarantee only holds as long as this SELECT and
     * the later {@link #markDone}/{@link #markRetry}/{@link #markDead} call for the same rows
     * run on ONE connection inside a single transaction that stays open across the engine call in
     * between — {@link org.casemgmt.engine.EngineCommandDispatcher#drainOnce} does not do that
     * (each JdbcClient call here runs on a connection borrowed and returned to the pool in
     * autocommit mode, so the row locks are released as soon as this query returns, well before
     * the engine is actually called). For this PoC — one dispatcher instance per JVM, proven only
     * against a single-instance test — that gap is latent rather than active. A genuinely
     * multi-instance deployment would need this claim to lease the rows (e.g. flip them to an
     * in-flight status inside the same short transaction as the SELECT) rather than rely on the
     * lock surviving until the follow-up UPDATE.
     *
     * <p>Confirmed directly against Oracle 23ai that the obvious {@code ORDER BY ... FETCH FIRST
     * :limit ROWS ONLY FOR UPDATE} fails with ORA-02014 ("cannot select FOR UPDATE from view with
     * DISTINCT, GROUP BY, etc."): the row-limiting clause is implemented as an implicit view and
     * {@code FOR UPDATE} cannot be pushed through it. The seemingly safer rewrite — sorting in an
     * inner subquery and filtering the outer query on {@code ROWNUM} — hits the SAME ORA-02014,
     * because the inner {@code ORDER BY} still makes the subquery an unupdatable view. There is
     * no ORDER BY anywhere in the query actually holding {@code FOR UPDATE} below as a result:
     * {@code ROWNUM <= :limit} caps the batch size against the raw (unordered) table scan, and
     * "oldest due command first" is not preserved. That is an acceptable trade for this PoC —
     * every command in a claimed batch is due NOW regardless of order, backoff correctness does
     * not depend on FIFO delivery, and {@code EngineCommand} does not even carry CREATED_AT_ to
     * order by in the first place — but a design that needed strict ordering would have to
     * abandon {@code FOR UPDATE SKIP LOCKED} claiming for a lease/checkout pattern instead.
     */
    public List<EngineCommand> claimDue(int limit) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_, NEXT_ATTEMPT_AT_, LAST_ERROR_
                FROM CM_ENGINE_COMMAND
                WHERE STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP
                  AND ROWNUM <= :limit
                FOR UPDATE SKIP LOCKED""")
            .param("limit", limit)
            .query((rs, n) -> new EngineCommand(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    EngineCommand.Type.valueOf(rs.getString("TYPE_")),
                    JsonCodec.toMap(rs.getString("PAYLOAD_JSON_")),
                    rs.getString("STATUS_"), rs.getInt("ATTEMPTS_"),
                    rs.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                    rs.getString("LAST_ERROR_")))
            .list();
    }

    public void markDone(String id) {
        jdbc.sql("UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'DONE' WHERE ID_ = :id")
            .param("id", id).update();
    }

    public void markRetry(String id, String error, OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'RETRYING', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_ERROR_ = :error, NEXT_ATTEMPT_AT_ = :next
                WHERE ID_ = :id""")
            .param("error", truncate(error)).param("next", nextAttempt).param("id", id).update();
    }

    public void markDead(String id, String error) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'DEAD', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_ERROR_ = :error
                WHERE ID_ = :id""")
            .param("error", truncate(error)).param("id", id).update();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1990 ? s.substring(0, 1990) : s;
    }
}
