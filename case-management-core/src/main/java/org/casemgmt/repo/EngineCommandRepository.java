package org.casemgmt.repo;

import org.casemgmt.engine.EngineCommand;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code CM_ENGINE_COMMAND} (spec §3.5) — the remote-mode engine command
 * outbox. {@link org.casemgmt.engine.OutboxEngineGateway} enqueues rows in the caller's
 * transaction; {@link org.casemgmt.engine.EngineCommandDispatcher} claims and delivers them.
 */
public class EngineCommandRepository {

    /**
     * How long a claim is honoured before another {@link #claimDue} call is allowed to treat
     * the row as abandoned and reclaim it. Covers a dispatcher that claimed a batch and then
     * died (crash, OOM-kill, rolling restart) before calling {@link #markDone}/{@link #markRetry}/
     * {@link #markDead} — without this, that row would stay {@code CLAIMED} forever, since
     * nothing else ever un-claims it. Five minutes is generous relative to a single engine call
     * (Task 25's {@code RestClient} is expected to carry a connect/read timeout well under a
     * minute — see {@code EngineCommandDispatcher}'s Javadoc), so a false reclaim of a dispatcher
     * that is merely slow rather than dead should be rare. It is not impossible: if the original
     * dispatcher wakes up and completes after its lease already expired and a second dispatcher
     * reclaimed and re-executed the same command, that is the same at-least-once duplicate this
     * whole outbox already tolerates elsewhere (see {@code CaseTaskRepository.markSync}'s
     * first-writer-wins guard) — not a new failure mode introduced by leasing.
     */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

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
     * Claims due commands by UPDATE, not by {@code SELECT ... FOR UPDATE SKIP LOCKED}.
     *
     * <p>The original design used a plain SELECT with {@code FOR UPDATE SKIP LOCKED} and never
     * mutated any row. Under this codebase's autocommit-pooled connections that lock is released
     * the instant the SELECT statement completes — before the engine is even called, let alone
     * before {@link #markDone}/{@link #markRetry}/{@link #markDead} runs — so calling this method
     * twice with no mark* call in between returned the SAME rows both times, deterministically,
     * with no concurrency needed to reproduce it. Holding the lock open across the engine's HTTP
     * call instead was rejected on purpose: a DB row lock spanning an outbound request is its own
     * failure mode, worse while Task 25 still owes the {@code RestClient} a timeout (see
     * {@code EngineCommandDispatcher}'s Javadoc).
     *
     * <p>Fixed with claim-by-UPDATE: one UPDATE atomically flips a bounded, age-ordered batch of
     * due (or stale-claimed, see {@link #CLAIM_LEASE}) rows to {@code CLAIMED} under a token
     * unique to this call, then a follow-up SELECT reads back exactly those rows. This needs no
     * {@code FOR UPDATE} at all — and so never risks ORA-02014 — because Oracle's own DML-restart
     * semantics do the safety work: the UPDATE's WHERE clause repeats the STATUS_/timestamp
     * predicate directly (not only inside the id-selecting subquery), so before actually applying
     * the change to any row Oracle re-evaluates that predicate against the row's CURRENT
     * (post-commit) value. A concurrent claim that already flipped a row to CLAIMED makes this
     * UPDATE's re-check fail for that row, and it is silently excluded — never double-claimed.
     * Proven directly: {@code EngineCommandClaimSafetyTest.concurrentClaimsNeverAssignTheSameCommandToBothCallers}
     * runs two independent {@code EngineCommandRepository} instances against the same due rows
     * from two threads and asserts the returned id sets are disjoint.
     *
     * <p>Ordering is preserved despite the ORA-02014 restriction that blocked the naive
     * {@code ORDER BY ... FETCH FIRST :limit ROWS ONLY FOR UPDATE} (and its {@code ROWNUM}
     * subquery variant — see git history for both failing forms): the inner subquery below sorts
     * by {@code CREATED_AT_} and caps with {@code ROWNUM} to decide WHICH ids this call targets,
     * with no {@code FOR UPDATE} anywhere near it, so the view restriction never applies. Oldest
     * due-or-stale-claimed commands are claimed first, same as the original intent.
     */
    public List<EngineCommand> claimDue(int limit) {
        String token = UUID.randomUUID().toString();
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(CLAIM_LEASE);

        int claimed = jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND
                SET STATUS_ = 'CLAIMED', CLAIM_TOKEN_ = :token, CLAIMED_AT_ = SYSTIMESTAMP
                WHERE ((STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP)
                       OR (STATUS_ = 'CLAIMED' AND CLAIMED_AT_ <= :staleBefore))
                  AND ID_ IN (
                      SELECT ID_ FROM (
                          SELECT ID_ FROM CM_ENGINE_COMMAND
                          WHERE (STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP)
                             OR (STATUS_ = 'CLAIMED' AND CLAIMED_AT_ <= :staleBefore)
                          ORDER BY CREATED_AT_
                      )
                      WHERE ROWNUM <= :limit
                  )""")
            .param("token", token).param("staleBefore", staleBefore).param("limit", limit)
            .update();

        if (claimed == 0) {
            return List.of();
        }

        return jdbc.sql("""
                SELECT ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_, NEXT_ATTEMPT_AT_, LAST_ERROR_
                FROM CM_ENGINE_COMMAND WHERE CLAIM_TOKEN_ = :token
                ORDER BY CREATED_AT_""")
            .param("token", token)
            .query((rs, n) -> new EngineCommand(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    EngineCommand.Type.valueOf(rs.getString("TYPE_")),
                    JsonCodec.toMap(rs.getString("PAYLOAD_JSON_")),
                    rs.getString("STATUS_"), rs.getInt("ATTEMPTS_"),
                    rs.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                    rs.getString("LAST_ERROR_")))
            .list();
    }

    public void markDone(String id) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'DONE', CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id""")
            .param("id", id).update();
    }

    public void markRetry(String id, String error, OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'RETRYING', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_ERROR_ = :error, NEXT_ATTEMPT_AT_ = :next, CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id""")
            .param("error", truncate(error)).param("next", nextAttempt).param("id", id).update();
    }

    public void markDead(String id, String error) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'DEAD', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_ERROR_ = :error, CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id""")
            .param("error", truncate(error)).param("id", id).update();
    }

    public List<EngineCommand> findDead(int limit) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_,
                       NEXT_ATTEMPT_AT_, LAST_ERROR_
                FROM CM_ENGINE_COMMAND WHERE STATUS_ = 'DEAD'
                ORDER BY CREATED_AT_ FETCH FIRST :limit ROWS ONLY""")
                .param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> new EngineCommand(rs.getString("ID_"), rs.getString("CASE_ID_"),
                        EngineCommand.Type.valueOf(rs.getString("TYPE_")),
                        JsonCodec.toMap(rs.getString("PAYLOAD_JSON_")), rs.getString("STATUS_"),
                        rs.getInt("ATTEMPTS_"),
                        rs.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                        rs.getString("LAST_ERROR_")))
                .list();
    }

    /** Administrative retry is explicit and only valid for a command already parked DEAD. */
    public boolean retryDead(String id) {
        return jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'PENDING', ATTEMPTS_ = 0,
                    NEXT_ATTEMPT_AT_ = SYSTIMESTAMP, LAST_ERROR_ = NULL,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id AND STATUS_ = 'DEAD'""")
                .param("id", id).update() == 1;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1990 ? s.substring(0, 1990) : s;
    }
}
