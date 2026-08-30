package org.casemgmt.repo;

import org.casemgmt.observation.ObservationEnvelope;
import org.casemgmt.observation.ObservationStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transaction-participating durable inbox. Duplicate remote windows are harmless. */
public final class ObservationInboxRepository {
    private final JdbcClient jdbc;
    public ObservationInboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean enqueue(String tenantId, ObservationStream stream, ObservationEnvelope envelope) {
        try {
            return jdbc.sql("""
                    INSERT INTO CM_REMOTE_OBS_INBOX
                    (FINGERPRINT_, TENANT_ID_, STREAM_, PAYLOAD_, STATUS_, ATTEMPTS_, CREATED_AT_)
                    VALUES (:fingerprint, :tenant, :stream, :payload, 'PENDING', 0, SYSTIMESTAMP)""")
                    .param("fingerprint", envelope.observation().fingerprint())
                    .param("tenant", tenantId == null || tenantId.isBlank() ? "__default__" : tenantId)
                    .param("stream", stream.name()).param("payload", envelope.payload()).update() == 1;
        } catch (DuplicateKeyException duplicate) { return false; }
    }

    /**
     * Claims a bounded batch with a lease.  This is intentionally a database operation rather
     * than an in-memory queue: a worker crash leaves the row recoverable after the lease expires.
     * Callers must invoke this in a short transaction so {@code FOR UPDATE SKIP LOCKED} protects
     * concurrent schedulers.
     */
    public List<Claim> claimDue(int limit, OffsetDateTime now, OffsetDateTime leaseExpiresAt) {
        String token = UUID.randomUUID().toString();
        List<Claim> candidates = jdbc.sql("""
                SELECT FINGERPRINT_, PAYLOAD_, ATTEMPTS_ FROM (
                    SELECT FINGERPRINT_, PAYLOAD_, ATTEMPTS_ FROM CM_REMOTE_OBS_INBOX
                    WHERE STATUS_ = 'PENDING'
                       OR (STATUS_ = 'PROCESSING' AND LEASED_AT_ < :now)
                    ORDER BY CREATED_AT_, FINGERPRINT_
                ) WHERE ROWNUM <= :limit FOR UPDATE SKIP LOCKED""")
                .param("now", now).param("limit", limit)
                .query((rs, row) -> new Claim(rs.getString("FINGERPRINT_"),
                        rs.getString("PAYLOAD_"), rs.getInt("ATTEMPTS_") + 1, token))
                .list();
        for (Claim claim : candidates) {
            jdbc.sql("""
                    UPDATE CM_REMOTE_OBS_INBOX SET STATUS_ = 'PROCESSING', ATTEMPTS_ = :attempts,
                        LEASE_TOKEN_ = :token, LEASED_AT_ = :leasedAt, FAILURE_DETAIL_ = NULL
                    WHERE FINGERPRINT_ = :fingerprint""")
                    .param("attempts", claim.attempts()).param("token", token)
                    .param("leasedAt", leaseExpiresAt).param("fingerprint", claim.fingerprint()).update();
        }
        return candidates;
    }

    public void markApplied(Claim claim) {
        jdbc.sql("""
                UPDATE CM_REMOTE_OBS_INBOX SET STATUS_ = 'APPLIED', APPLIED_AT_ = SYSTIMESTAMP,
                    LEASE_TOKEN_ = NULL, LEASED_AT_ = NULL
                WHERE FINGERPRINT_ = :fingerprint AND STATUS_ = 'PROCESSING'
                  AND LEASE_TOKEN_ = :token""")
                .param("fingerprint", claim.fingerprint()).param("token", claim.leaseToken()).update();
    }

    public void markFailed(Claim claim, String detail, int poisonAfter) {
        String status = claim.attempts() >= poisonAfter ? "POISON" : "PENDING";
        jdbc.sql("""
                UPDATE CM_REMOTE_OBS_INBOX SET STATUS_ = :status, FAILURE_DETAIL_ = :detail,
                    LEASE_TOKEN_ = NULL, LEASED_AT_ = NULL
                WHERE FINGERPRINT_ = :fingerprint AND STATUS_ = 'PROCESSING'
                  AND LEASE_TOKEN_ = :token""")
                .param("status", status).param("detail", truncate(detail))
                .param("fingerprint", claim.fingerprint()).param("token", claim.leaseToken()).update();
    }

    /** An explicit operator action; poisoned evidence is never silently discarded. */
    public boolean replayPoison(String fingerprint) {
        return jdbc.sql("""
                UPDATE CM_REMOTE_OBS_INBOX SET STATUS_ = 'PENDING', ATTEMPTS_ = 0,
                    FAILURE_DETAIL_ = NULL, LEASE_TOKEN_ = NULL, LEASED_AT_ = NULL
                WHERE FINGERPRINT_ = :fingerprint AND STATUS_ = 'POISON'""")
                .param("fingerprint", fingerprint).update() == 1;
    }

    private static String truncate(String detail) {
        if (detail == null) return "unknown inbox application failure";
        return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
    }

    public record Claim(String fingerprint, String payload, int attempts, String leaseToken) { }
}
