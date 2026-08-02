package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

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

    public record Delivery(String id, String webhookId, long eventSeq, int attempts) {}

    /**
     * Same lease as {@code EngineCommandRepository.CLAIM_LEASE} and for the same reason: covers
     * a dispatcher that claimed a batch and then died (crash, OOM-kill, rolling restart) before
     * calling {@link #markDelivered}/{@link #markRetry}/{@link #markDead}, so the row does not
     * stay {@code CLAIMED} forever.
     */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

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

    public List<Subscription> active(String tenantId) {
        return jdbc.sql("""
                SELECT ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, SECRET_HASH_, MAX_RETRIES_,
                       ACTIVE_, VERSION_
                FROM CM_WEBHOOK_SUB
                WHERE ACTIVE_ = 1 AND (TENANT_ID_ IS NULL OR TENANT_ID_ = :tenant)""")
            .param("tenant", tenantId)
            .query((rs, n) -> new Subscription(rs.getString("ID_"), rs.getString("TENANT_ID_"),
                    rs.getString("URL_"), JsonCodec.toList(rs.getString("EVENT_TYPES_JSON_")),
                    rs.getString("SECRET_HASH_"), rs.getInt("MAX_RETRIES_"),
                    rs.getInt("ACTIVE_") == 1, rs.getLong("VERSION_")))
            .list();
    }

    public List<Subscription> all() {
        return jdbc.sql("""
                SELECT ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, SECRET_HASH_, MAX_RETRIES_,
                       ACTIVE_, VERSION_
                FROM CM_WEBHOOK_SUB ORDER BY CREATED_AT_""")
            .query((rs, n) -> new Subscription(rs.getString("ID_"), rs.getString("TENANT_ID_"),
                    rs.getString("URL_"), JsonCodec.toList(rs.getString("EVENT_TYPES_JSON_")),
                    rs.getString("SECRET_HASH_"), rs.getInt("MAX_RETRIES_"),
                    rs.getInt("ACTIVE_") == 1, rs.getLong("VERSION_")))
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
     */
    public List<Delivery> claimDueDeliveries(int limit) {
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
                SELECT ID_, WEBHOOK_ID_, EVENT_SEQ_, ATTEMPTS_
                FROM CM_WEBHOOK_DELIVERY WHERE CLAIM_TOKEN_ = :token
                ORDER BY EVENT_SEQ_""")
            .param("token", token)
            .query((rs, n) -> new Delivery(rs.getString("ID_"), rs.getString("WEBHOOK_ID_"),
                    rs.getLong("EVENT_SEQ_"), rs.getInt("ATTEMPTS_")))
            .list();
    }

    public void markDelivered(String deliveryId, int statusCode) {
        jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'DELIVERED', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, DELIVERED_AT_ = SYSTIMESTAMP,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id""")
            .param("code", statusCode).param("id", deliveryId).update();
    }

    public void markRetry(String deliveryId, Integer statusCode, String error,
                          OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'RETRYING', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, LAST_ERROR_ = :error, NEXT_ATTEMPT_AT_ = :next,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id""")
            .param("code", statusCode).param("error", truncate(error)).param("next", nextAttempt)
            .param("id", deliveryId).update();
    }

    public void markDead(String deliveryId, Integer statusCode, String error) {
        jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'DEAD', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, LAST_ERROR_ = :error,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL
                WHERE ID_ = :id""")
            .param("code", statusCode).param("error", truncate(error)).param("id", deliveryId).update();
    }

    /** Rows in DEAD state ARE the dead-letter queue (db-design.md §3.6). */
    public List<Delivery> deadLetters(String webhookId) {
        return jdbc.sql("""
                SELECT ID_, WEBHOOK_ID_, EVENT_SEQ_, ATTEMPTS_ FROM CM_WEBHOOK_DELIVERY
                WHERE WEBHOOK_ID_ = :id AND STATUS_ = 'DEAD' ORDER BY EVENT_SEQ_""")
            .param("id", webhookId)
            .query((rs, n) -> new Delivery(rs.getString("ID_"), rs.getString("WEBHOOK_ID_"),
                    rs.getLong("EVENT_SEQ_"), rs.getInt("ATTEMPTS_")))
            .list();
    }

    public Subscription require(String id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.casemgmt.error.NotFoundException("Webhook", id));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1990 ? s.substring(0, 1990) : s;
    }
}
