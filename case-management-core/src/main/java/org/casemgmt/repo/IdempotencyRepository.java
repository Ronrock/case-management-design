package org.casemgmt.repo;

import org.casemgmt.error.IdempotencyConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * Idempotency-Key handling (spec §6.4). The row is inserted BEFORE the work happens,
 * so a concurrent duplicate collides on the primary key rather than doing the work twice.
 *
 * <p>In-progress rows are marked by {@code RESPONSE_STATUS_ = 0} — never a real HTTP status —
 * with {@code RESPONSE_JSON_} left {@code NULL}. Deviation from an earlier draft that stored a
 * literal sentinel string ({@code "__IN_PROGRESS__"}) in {@code RESPONSE_JSON_}: that column
 * carries {@code CHECK (RESPONSE_JSON_ IS JSON)} (db-design.sql), and an unquoted bare word is
 * not valid JSON — confirmed against real Oracle as ORA-02290 (check constraint
 * CK_CM_IDEM_RESP violated) on every {@code begin()} call. {@code NULL} satisfies the same
 * CHECK (three-valued CHECK semantics: a NULL operand makes the condition UNKNOWN, which SQL
 * treats as satisfied, not violated) — already relied on elsewhere in this schema by
 * {@code CM_AUDIT_LOG.BEFORE_JSON_}/{@code AFTER_JSON_}, which are nullable CLOBs under the
 * identical constraint shape.
 */
public class IdempotencyRepository {

    public record StoredResponse(int status, String body) {}

    /** Sentinel HTTP status for a row whose work has not completed yet. Never a real status. */
    private static final int IN_PROGRESS_STATUS = 0;

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<StoredResponse> begin(String key, String scope, String requestHash) {
        try {
            jdbc.sql("""
                    INSERT INTO CM_IDEMPOTENCY_KEY (KEY_, SCOPE_, REQUEST_HASH_, RESPONSE_STATUS_,
                        RESPONSE_JSON_)
                    VALUES (:key, :scope, :hash, :inProgress, NULL)""")
                .param("key", key).param("scope", scope).param("hash", requestHash)
                .param("inProgress", IN_PROGRESS_STATUS)
                .update();
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            return Optional.of(replay(key, scope, requestHash));
        }
    }

    private StoredResponse replay(String key, String scope, String requestHash) {
        var row = jdbc.sql("""
                SELECT REQUEST_HASH_, RESPONSE_STATUS_, RESPONSE_JSON_ FROM CM_IDEMPOTENCY_KEY
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("key", key).param("scope", scope)
            .query((rs, n) -> new Object[]{rs.getString("REQUEST_HASH_"),
                    rs.getInt("RESPONSE_STATUS_"), rs.getString("RESPONSE_JSON_")})
            .single();

        String storedHash = (String) row[0];
        int status = (Integer) row[1];
        String body = (String) row[2];

        if (!storedHash.equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key " + key + " was already used with a different payload");
        }
        if (status == IN_PROGRESS_STATUS) {
            throw new IdempotencyConflictException(
                    "A request with idempotency key " + key + " is still in progress — retry shortly");
        }
        return new StoredResponse(status, body);
    }

    public void complete(String key, String scope, int status, String responseJson) {
        jdbc.sql("""
                UPDATE CM_IDEMPOTENCY_KEY SET RESPONSE_STATUS_ = :status, RESPONSE_JSON_ = :body
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("status", status).param("body", responseJson)
            .param("key", key).param("scope", scope)
            .update();
    }

    /** Retention: 48h, per spec §6.4. Call from a scheduled job. */
    public int purgeOlderThanHours(int hours) {
        return jdbc.sql("""
                DELETE FROM CM_IDEMPOTENCY_KEY
                WHERE CREATED_AT_ < SYSTIMESTAMP - NUMTODSINTERVAL(:hours, 'HOUR')""")
            .param("hours", hours).update();
    }
}
