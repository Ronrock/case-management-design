package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * CM_WEBHOOK_SUB subscriptions and the CM_WEBHOOK_DELIVERY fan-out rows
 * {@link org.casemgmt.event.EventPublisher} enqueues per matching subscription (spec §6.1).
 */
public class WebhookRepository {

    public record Subscription(String id, String tenantId, String url, List<String> eventTypes,
                               String secretHash, int maxRetries, boolean active, long version) {}

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
}
