package org.casemgmt.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CM_WEBHOOK_SUB subscriptions and the CM_WEBHOOK_DELIVERY fan-out rows
 * {@link org.casemgmt.event.EventPublisher} enqueues per matching subscription (spec §6.1).
 */
public class WebhookRepository {

    public record Subscription(String id, String tenantId, String url, List<String> eventTypes,
                               String secretHash, int maxRetries, boolean active, long version) {}

    /**
     * A claimed (or dead-lettered) delivery row. {@code claimToken} is the token the claiming
     * UPDATE stamped on the row; every {@code mark*} call must present it back so a claimer whose
     * lease already expired cannot overwrite the row a reclaimer now owns (see
     * {@link #markDelivered}). It is {@code null} for rows read outside a claim — {@link
     * #deadLetters} in particular, since {@code mark*} clears the token as it finalises the row.
     */
    public record Delivery(String id, String webhookId, long eventSeq, int attempts,
                           String claimToken) {}

    /**
     * Largest batch {@link #claimDueDeliveries} will hand out in one call. Load-bearing for
     * {@link #CLAIM_LEASE}, not a tuning knob picked in isolation — see that constant. Larger
     * values are rejected rather than silently capped.
     */
    public static final int MAX_CLAIM_BATCH = 20;

    /**
     * Worst-case wall-clock one single delivery attempt can consume: {@code
     * WebhookDispatcher.CONNECT_TIMEOUT} (5s) plus {@code WebhookDispatcher.RESPONSE_TIMEOUT}
     * (10s). {@code WebhookDispatcher}'s constructor re-derives this from its own timeout fields
     * and refuses to build a dispatcher whose worst-case batch would outrun {@link #CLAIM_LEASE},
     * so the two cannot drift apart unnoticed.
     */
    public static final Duration MAX_TIME_PER_DELIVERY = Duration.ofSeconds(15);

    /**
     * How long a claim is honoured before another {@link #claimDueDeliveries} call may treat the
     * row as abandoned and reclaim it. Covers a dispatcher that claimed a batch and then died
     * (crash, OOM-kill, rolling restart) before calling {@link #markDelivered}/{@link #markRetry}/
     * {@link #markDead}, so the row does not stay {@code CLAIMED} forever.
     *
     * <p><b>Derived, not copied from {@code EngineCommandRepository.CLAIM_LEASE}</b> (review round
     * 1). That constant's five minutes is justified against a SINGLE engine call; this outbox
     * hands a whole BATCH to one dispatcher pass, and every row in it is bounded only by
     * {@link #MAX_TIME_PER_DELIVERY}, so the number that has to fit inside the lease is
     * {@code MAX_CLAIM_BATCH * MAX_TIME_PER_DELIVERY}. At the original 50 x 10s = 500s against a
     * 300s lease, a subscriber that accepts connections and then hangs made mid-batch lease
     * expiry — and therefore duplicate delivery of the batch's tail — the EXPECTED path, not a
     * crash-only one. Here: 20 x 15s = 300s worst case, doubled for margin (GC pauses, a slow
     * {@code CM_EVENT} read, a loaded box), giving 600s. The lease can no longer expire while the
     * claiming pass is still working through its own batch.
     *
     * <p>Belt and braces, because a lease is a timeout and timeouts are guesses: every
     * {@code mark*} statement additionally carries an {@code AND CLAIM_TOKEN_ = :token} guard, so
     * if the lease is somehow lost anyway the late mark updates zero rows and is reported to the
     * caller instead of silently clobbering the reclaimer's outcome.
     */
    public static final Duration CLAIM_LEASE =
            MAX_TIME_PER_DELIVERY.multipliedBy(MAX_CLAIM_BATCH).multipliedBy(2);

    private final JdbcClient jdbc;

    public WebhookRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String tenantId, String url, List<String> eventTypes,
                       String secretHash, int maxRetries) {
        jdbc.sql("""
                INSERT INTO CM_WEBHOOK_SUB (ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, ACTIVE_,
                    SECRET_HASH_, MAX_RETRIES_, VERSION_)
                VALUES (:id, :tenant, :url, :types, 1, :hash, :retries, 0)""")
            .param("id", id).param("tenant", tenantId).param("url", url)
            .param("types", JsonCodec.toJson(eventTypes)).param("hash", secretHash)
            .param("retries", maxRetries)
            .update();
    }

    private static final String SUBSCRIPTION_COLUMNS = """
            SELECT ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, SECRET_HASH_, MAX_RETRIES_,
                   ACTIVE_, VERSION_
            FROM CM_WEBHOOK_SUB""";

    private static final RowMapper<Subscription> SUBSCRIPTION_MAPPER = (rs, n) ->
            new Subscription(rs.getString("ID_"), rs.getString("TENANT_ID_"),
                    rs.getString("URL_"), JsonCodec.toList(rs.getString("EVENT_TYPES_JSON_")),
                    rs.getString("SECRET_HASH_"), rs.getInt("MAX_RETRIES_"),
                    rs.getInt("ACTIVE_") == 1, rs.getLong("VERSION_"));

    public List<Subscription> active(String tenantId) {
        return jdbc.sql(SUBSCRIPTION_COLUMNS + """

                WHERE ACTIVE_ = 1 AND (TENANT_ID_ IS NULL OR TENANT_ID_ = :tenant)""")
            .param("tenant", tenantId)
            .query(SUBSCRIPTION_MAPPER)
            .list();
    }

    public List<Subscription> all() {
        return jdbc.sql(SUBSCRIPTION_COLUMNS + " ORDER BY CREATED_AT_")
            .query(SUBSCRIPTION_MAPPER)
            .list();
    }

    /**
     * Every subscription owned by one tenant, active or not.
     *
     * <p>Distinct from {@link #active}, which is the dispatcher's fan-out query and therefore
     * also returns untenanted ({@code TENANT_ID_ IS NULL}) subscriptions that receive every
     * tenant's events. This one is the administrative listing behind
     * {@code GET /case-api/v2/webhooks} and is strictly scoped: a tenant administrator must not
     * see another tenant's endpoints, and must not see a global subscription they cannot own.
     * Added by Task 24 fix round 1 (review finding, Critical) — {@link #all} was reachable over
     * HTTP and listed every tenant's subscriptions.
     */
    public List<Subscription> allForTenant(String tenantId) {
        return jdbc.sql(SUBSCRIPTION_COLUMNS + """

                WHERE TENANT_ID_ = :tenant ORDER BY CREATED_AT_""")
            .param("tenant", tenantId)
            .query(SUBSCRIPTION_MAPPER)
            .list();
    }

    public void enqueueDelivery(String id, String webhookId, long eventSeq) {
        jdbc.sql("""
                INSERT INTO CM_WEBHOOK_DELIVERY (ID_, WEBHOOK_ID_, EVENT_SEQ_, STATUS_, ATTEMPTS_,
                    NEXT_ATTEMPT_AT_)
                VALUES (:id, :webhookId, :seq, 'PENDING', 0, SYSTIMESTAMP)""")
            .param("id", id).param("webhookId", webhookId).param("seq", eventSeq)
            .update();
    }

    /**
     * Claims due deliveries by UPDATE, not by {@code SELECT ... FOR UPDATE SKIP LOCKED} — the
     * same fix, for the same reason, as {@code EngineCommandRepository.claimDue} (Task 13 review
     * round 2). A plain {@code SELECT ... FOR UPDATE SKIP LOCKED} that never mutates any row
     * releases its lock the instant the SELECT statement completes on this codebase's
     * autocommit-pooled connections — long before the outbound HTTP call the caller is about to
     * make, let alone before {@link #markDelivered}/{@link #markRetry}/{@link #markDead} runs —
     * so two dispatchers (or one dispatcher called twice with no mark* call between) would claim
     * and deliver the SAME row. Holding the lock open across the HTTP call instead is rejected
     * for the same reason it was rejected in Task 13: a DB row lock spanning an outbound request
     * is its own failure mode, worse given a hung endpoint (see {@link
     * org.casemgmt.event.WebhookDispatcher}'s Javadoc on its HTTP timeouts).
     *
     * <p>One UPDATE atomically flips a bounded, age-ordered (oldest {@code EVENT_SEQ_} first)
     * batch of due-or-stale-claimed rows to {@code CLAIMED} under a token unique to this call;
     * a follow-up SELECT reads back exactly those rows by token. No {@code FOR UPDATE} is
     * involved, so ORA-02014 (which blocks {@code ORDER BY ... FETCH FIRST ... FOR UPDATE} and
     * its {@code ROWNUM} equivalent) never applies — the inner subquery that decides which ids
     * this call targets carries no {@code FOR UPDATE} at all. Oracle's own DML-restart semantics
     * do the safety work: the UPDATE's WHERE clause repeats the STATUS_/timestamp predicate
     * directly, so a row already flipped to CLAIMED by a concurrent caller fails that re-check
     * and is silently excluded, never double-claimed.
     *
     * @param limit at most {@link #MAX_CLAIM_BATCH}; a larger batch could outlast
     *              {@link #CLAIM_LEASE}, so it is rejected rather than silently capped — a caller
     *              that asked for 50 and quietly got 20 would go on believing it drains 50 a pass
     * @throws IllegalArgumentException if {@code limit} is outside {@code 1..MAX_CLAIM_BATCH}
     */
    public List<Delivery> claimDueDeliveries(int limit) {
        if (limit < 1 || limit > MAX_CLAIM_BATCH) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_CLAIM_BATCH
                    + " so one claimed batch cannot outlast CLAIM_LEASE (" + CLAIM_LEASE
                    + "); got " + limit);
        }
        String token = UUID.randomUUID().toString();
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(CLAIM_LEASE);

        int claimed = jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY
                SET STATUS_ = 'CLAIMED', CLAIM_TOKEN_ = :token, CLAIMED_AT_ = SYSTIMESTAMP
                WHERE ((STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP)
                       OR (STATUS_ = 'CLAIMED' AND CLAIMED_AT_ <= :staleBefore))
                  AND ID_ IN (
                      SELECT ID_ FROM (
                          SELECT ID_ FROM CM_WEBHOOK_DELIVERY
                          WHERE (STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP)
                             OR (STATUS_ = 'CLAIMED' AND CLAIMED_AT_ <= :staleBefore)
                          ORDER BY EVENT_SEQ_
                      )
                      WHERE ROWNUM <= :limit
                  )""")
            .param("token", token).param("staleBefore", staleBefore).param("limit", limit)
            .update();

        if (claimed == 0) {
            return List.of();
        }

        return jdbc.sql("""
                SELECT ID_, WEBHOOK_ID_, EVENT_SEQ_, ATTEMPTS_, CLAIM_TOKEN_
                FROM CM_WEBHOOK_DELIVERY WHERE CLAIM_TOKEN_ = :token
                ORDER BY EVENT_SEQ_""")
            .param("token", token)
            .query(DELIVERY_MAPPER)
            .list();
    }

    /**
     * Finalises a delivery the caller claimed, and only if it still holds the claim.
     *
     * <p>The {@code AND CLAIM_TOKEN_ = :claimToken} guard is the reason this returns a boolean
     * (review round 1). Without it, an {@code UPDATE ... WHERE ID_ = :id} from a claimer whose
     * lease had expired would silently overwrite whatever a reclaimer had already written —
     * resetting a just-dead-lettered row to {@code RETRYING}, or the reverse. With it, the stale
     * mark matches no row and the caller is told, rather than the outbox quietly ending up in a
     * state neither dispatcher decided on. {@link #CLAIM_LEASE} is sized so this should not
     * happen at all; this guard is what makes "should not" observable instead of assumed.
     *
     * @return {@code true} if this caller still owned the claim and the row was updated;
     *         {@code false} if the claim had been lost to a reclaimer (nothing was written)
     */
    public boolean markDelivered(String deliveryId, String claimToken, int statusCode) {
        return jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'DELIVERED', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, DELIVERED_AT_ = SYSTIMESTAMP,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id AND CLAIM_TOKEN_ = :claimToken""")
            .param("code", statusCode).param("id", deliveryId).param("claimToken", claimToken)
            .update() == 1;
    }

    /**
     * Schedules the next attempt, if this caller still holds the claim — see
     * {@link #markDelivered} for the guard and the boolean.
     *
     * <p>{@code statusCode} is deliberately a nullable {@code Integer}: a transport-level failure
     * (connect refused, {@code HttpTimeoutException} from the per-request response timeout) never
     * produced an HTTP status at all, and {@code LAST_STATUS_CODE_} must be SQL NULL for those
     * rather than a made-up 0. The explicit {@link java.sql.Types#INTEGER} type is what makes a
     * {@code null} bind legal on Oracle without a "parameter type unknown" failure.
     */
    public boolean markRetry(String deliveryId, String claimToken, Integer statusCode, String error,
                             OffsetDateTime nextAttempt) {
        return jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'RETRYING', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, LAST_ERROR_ = :error, NEXT_ATTEMPT_AT_ = :next,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id AND CLAIM_TOKEN_ = :claimToken""")
            .param("code", statusCode, Types.INTEGER).param("error", truncate(error))
            .param("next", nextAttempt)
            .param("id", deliveryId).param("claimToken", claimToken)
            .update() == 1;
    }

    /**
     * Dead-letters a delivery, if this caller still holds the claim — see {@link #markDelivered}
     * for the guard and the boolean, and {@link #markRetry} for the nullable {@code statusCode}.
     */
    public boolean markDead(String deliveryId, String claimToken, Integer statusCode, String error) {
        return jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'DEAD', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, LAST_ERROR_ = :error,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id AND CLAIM_TOKEN_ = :claimToken""")
            .param("code", statusCode, Types.INTEGER).param("error", truncate(error))
            .param("id", deliveryId).param("claimToken", claimToken)
            .update() == 1;
    }

    /** Rows in DEAD state ARE the dead-letter queue (db-design.md §3.6). */
    public List<Delivery> deadLetters(String webhookId) {
        return jdbc.sql("""
                SELECT ID_, WEBHOOK_ID_, EVENT_SEQ_, ATTEMPTS_, CLAIM_TOKEN_
                FROM CM_WEBHOOK_DELIVERY
                WHERE WEBHOOK_ID_ = :id AND STATUS_ = 'DEAD' ORDER BY EVENT_SEQ_""")
            .param("id", webhookId)
            .query(DELIVERY_MAPPER)
            .list();
    }

    /**
     * One subscription by primary key, for the dispatcher's per-delivery lookup.
     *
     * <p>A targeted {@code WHERE ID_ = :id} rather than {@code all()} filtered in Java (review
     * round 1): the dispatcher calls this once per claimed row, and the previous form full-scanned
     * {@code CM_WEBHOOK_SUB} and JSON-parsed every subscription in it, per row, per batch.
     *
     * <p><b>Deliberately ignores {@code ACTIVE_}, unlike {@link #active(String)}.</b> The
     * asymmetry is the intended semantics, not an oversight: {@code ACTIVE_} governs FAN-OUT —
     * {@link org.casemgmt.event.EventPublisher#publish} enqueues deliveries only for active
     * subscriptions — while this method serves rows ALREADY committed to the outbox. Deactivating
     * a subscription stops future deliveries being enqueued; it does not retract work the outbox
     * already accepted, which would otherwise strand those rows in {@code CLAIMED}/{@code
     * RETRYING} forever with nothing able to resolve them. Do not "fix" this by adding
     * {@code AND ACTIVE_ = 1}.
     */
    public Subscription require(String id) {
        return jdbc.sql(SUBSCRIPTION_COLUMNS + " WHERE ID_ = :id")
            .param("id", id)
            .query(SUBSCRIPTION_MAPPER)
            .optional()
            .orElseThrow(() -> new org.casemgmt.error.NotFoundException("Webhook", id));
    }

    private static final RowMapper<Delivery> DELIVERY_MAPPER = (rs, n) ->
            new Delivery(rs.getString("ID_"), rs.getString("WEBHOOK_ID_"),
                    rs.getLong("EVENT_SEQ_"), rs.getInt("ATTEMPTS_"), rs.getString("CLAIM_TOKEN_"));

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1990 ? s.substring(0, 1990) : s;
    }
}
