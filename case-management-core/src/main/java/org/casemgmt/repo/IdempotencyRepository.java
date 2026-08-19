package org.casemgmt.repo;

import org.casemgmt.error.IdempotencyConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency-Key handling (spec §6.4). The row is inserted BEFORE the work happens,
 * so a concurrent duplicate collides on the primary key rather than doing the work twice.
 *
 * <p><b>Required transaction boundary</b> (review finding, Important — I5): {@code begin}
 * and {@code complete} must each run as their own, separately-committed unit of work, never
 * wrapped together with the caller's business operation in one enclosing
 * {@code @Transactional} (see {@code case-management-rest}'s {@code IdempotencySupport}
 * class javadoc, which owns the call site). If {@code begin}'s INSERT were deferred inside a
 * still-open outer transaction, a concurrent duplicate request would never see the
 * in-progress row until that transaction committed — the exact race this class exists to
 * close. This is why neither this class nor {@code IdempotencySupport} carries a
 * {@code @Transactional} annotation.
 *
 * <p>In-progress rows are marked by {@code RESPONSE_STATUS_ = 0} — never a real HTTP status.
 * {@code RESPONSE_JSON_} is null while the row is in progress, but the null body is not part of
 * the state test: a legitimate completed 204/no-body response also has no body and must replay
 * as completed. The status value is the marker. Deviation from an earlier draft that stored a
 * literal sentinel string ({@code "__IN_PROGRESS__"}) in {@code RESPONSE_JSON_}: that column
 * carries {@code CHECK (RESPONSE_JSON_ IS JSON)} (db-design.sql), and an unquoted bare word is
 * not valid JSON — confirmed against real Oracle as ORA-02290 (check constraint
 * CK_CM_IDEM_RESP violated) on every {@code begin()} call. {@code NULL} satisfies the same
 * CHECK (three-valued CHECK semantics: a NULL operand makes the condition UNKNOWN, which SQL
 * treats as satisfied, not violated) — already relied on elsewhere in this schema by
 * {@code CM_AUDIT_LOG.BEFORE_JSON_}/{@code AFTER_JSON_}, which are nullable CLOBs under the
 * identical constraint shape.
 *
 * <p><b>No automatic reclaim on duplicate requests.</b> Earlier versions treated an old
 * in-progress row as abandoned and let a later caller execute the same command. That recovered
 * from crashes quickly, but it also double-executed any legitimate operation that ran longer
 * than the lease. This repository now chooses side-effect safety: a duplicate request can replay
 * a completed response or receive an in-progress conflict, but it cannot take over work that may
 * still be running. Client-error failures release the claim explicitly through
 * {@link #release(String, String, String)}; server faults stay claimed until operational recovery
 * or retention cleanup.
 */
public class IdempotencyRepository {

    public record StoredResponse(int status, String body) {}
    public record Claim(String token) {}
    public record BeginResult(Optional<StoredResponse> replay, Claim claim) {
        public static BeginResult replay(StoredResponse response) {
            return new BeginResult(Optional.of(response), null);
        }

        public static BeginResult claimed(String token) {
            return new BeginResult(Optional.empty(), new Claim(token));
        }

        public boolean isReplay() {
            return replay.isPresent();
        }
    }

    /** Sentinel HTTP status for a row whose work has not completed yet. Never a real status. */
    private static final int IN_PROGRESS_STATUS = 0;

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public BeginResult begin(String key, String scope, String requestHash) {
        String claimToken = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                jdbc.sql("""
                        INSERT INTO CM_IDEMPOTENCY_KEY (KEY_, SCOPE_, REQUEST_HASH_, RESPONSE_STATUS_,
                            RESPONSE_JSON_, CLAIM_TOKEN_)
                        VALUES (:key, :scope, :hash, :inProgress, NULL, :claimToken)""")
                    .param("key", key).param("scope", scope).param("hash", requestHash)
                    .param("inProgress", IN_PROGRESS_STATUS).param("claimToken", claimToken)
                    .update();
                return BeginResult.claimed(claimToken);
            } catch (DuplicateKeyException e) {
                Optional<BeginResult> existing = replayOrConflict(key, scope, requestHash);
                if (existing.isPresent()) {
                    return existing.orElseThrow();
                }
                claimToken = UUID.randomUUID().toString();
            }
        }
        throw new IdempotencyConflictException(
                "Idempotency key " + key + " changed while being inspected; retry the request");
    }

    private Optional<BeginResult> replayOrConflict(String key, String scope, String requestHash) {
        var row = jdbc.sql("""
                SELECT REQUEST_HASH_, RESPONSE_STATUS_, RESPONSE_JSON_ FROM CM_IDEMPOTENCY_KEY
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("key", key).param("scope", scope)
            .query((rs, n) -> new Object[]{rs.getString("REQUEST_HASH_"),
                    rs.getInt("RESPONSE_STATUS_"), rs.getString("RESPONSE_JSON_")})
            .optional();

        if (row.isEmpty()) {
            return Optional.empty();
        }

        Object[] values = row.orElseThrow();
        String storedHash = (String) values[0];
        int status = (Integer) values[1];
        String body = (String) values[2];

        if (!storedHash.equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key " + key + " was already used with a different payload");
        }
        if (status != IN_PROGRESS_STATUS) {
            return Optional.of(BeginResult.replay(new StoredResponse(status, body)));
        }

        throw new IdempotencyConflictException(
                "A request with idempotency key " + key + " is still in progress; retry after "
                        + "the original request completes or after operational recovery releases it");
    }

    /**
     * Records the response for a claim this caller still owns.
     *
     * <p><b>Guarded on {@code RESPONSE_STATUS_ = 0}, and the affected-row count is returned</b>
     * (final whole-branch review, Important 4). Without the guard this was an unconditional
     * {@code UPDATE ... WHERE KEY_ = :key AND SCOPE_ = :scope}, so a duplicate or late
     * {@code complete()} — a caller whose lease had expired and been reclaimed by someone else,
     * who then finished anyway — silently overwrote a response another caller had already
     * stored, and every subsequent replay served the wrong body. Same failure shape, and now
     * the same remedy, as {@link WebhookRepository#markDelivered}'s claim-token guard: the
     * stale write matches no row and the caller is told, instead of the store quietly ending up
     * in a state neither caller decided on.
     *
     * @return {@code true} if this caller still owned the in-progress claim and the response was
     *         stored; {@code false} if the row was already finalised by someone else (nothing
     *         was written)
     */
    public boolean complete(String key, String scope, String claimToken, int status, String responseJson) {
        return jdbc.sql("""
                UPDATE CM_IDEMPOTENCY_KEY SET RESPONSE_STATUS_ = :status, RESPONSE_JSON_ = :body
                WHERE KEY_ = :key AND SCOPE_ = :scope AND RESPONSE_STATUS_ = :inProgress
                  AND CLAIM_TOKEN_ = :claimToken""")
            .param("status", status).param("body", responseJson)
            .param("key", key).param("scope", scope).param("claimToken", claimToken)
            .param("inProgress", IN_PROGRESS_STATUS)
            .update() == 1;
    }

    /**
     * Releases an in-progress claim so the same key can be retried immediately (final
     * whole-branch review, Important 4).
     *
     * <p>{@link #begin} commits an IN_PROGRESS row before the caller's operation runs — that is
     * the whole point, it is a cross-request mutex. If the operation then throws, nothing used
     * to clean up: the key stayed claimed until retention cleanup. The client
     * fixed its payload, retried with the same {@code Idempotency-Key}, and got 409 "already
     * used with a different payload"; retrying the ORIGINAL payload got 409 "still in progress".
     * That fires on ordinary validation errors — a 400, a 422 form violation,
     * a 404 — not just on crashes, so it is the common path, not the exotic one.
     *
     * <p>DELETE rather than a status flip: the row exists only to hold a claim, and a released
     * claim must be indistinguishable from a key never used, or {@link #begin}'s
     * {@code DuplicateKeyException} branch would compare the retry's hash against a claim that
     * was abandoned — turning a legitimate corrected retry into the same 409 this exists to
     * prevent. Guarded on {@code RESPONSE_STATUS_ = 0} so it can never delete a row that
     * already carries a real, replayable response: a stale releaser affects zero rows.
     *
     * @return {@code true} if an in-progress claim was actually released
     */
    public boolean release(String key, String scope, String claimToken) {
        return jdbc.sql("""
                DELETE FROM CM_IDEMPOTENCY_KEY
                WHERE KEY_ = :key AND SCOPE_ = :scope AND RESPONSE_STATUS_ = :inProgress
                  AND CLAIM_TOKEN_ = :claimToken""")
            .param("key", key).param("scope", scope).param("claimToken", claimToken)
            .param("inProgress", IN_PROGRESS_STATUS)
            .update() == 1;
    }

    /** Retention: 48h, per spec §6.4. Call from a scheduled job. */
    public int purgeOlderThanHours(int hours) {
        return jdbc.sql("""
                DELETE FROM CM_IDEMPOTENCY_KEY
                WHERE CREATED_AT_ < SYSTIMESTAMP - NUMTODSINTERVAL(:hours, 'HOUR')""")
            .param("hours", hours).update();
    }
}
