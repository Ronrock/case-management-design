package org.casemgmt.repo;

import org.casemgmt.observation.EngineObservation;
import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.MilestoneObservation;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Transaction-participating claim ledger for effects derived from one engine observation.
 *
 * <p>The caller owns the transaction: this repository neither opens nor commits one. A claim
 * becomes final only when the caller marks it applied or failed in that same transaction.
 */
public final class AppliedObservationRepository {

    public enum ClaimOutcome { CLAIMED, RECLAIMED, DUPLICATE }

    public enum Status { CLAIMED, APPLIED, IGNORED_STALE, FAILED }

    public static final class ObservationOrderingModeException extends RuntimeException {
        public ObservationOrderingModeException(String message) {
            super(message);
        }
    }

    public static final class LegacyObservationHistoryException extends RuntimeException {
        public LegacyObservationHistoryException(String message) {
            super(message);
        }
    }

    /** Audit-safe ordering coordinates of the newest applied fact for one engine entity. */
    public record AppliedPosition(String observationId, Long entityRevision,
                                  java.time.Instant engineOccurredAt, String eventType) { }

    /** Opaque ownership capability; callers can retain it but cannot mint one. */
    public static final class Claim {
        private final String observationId;
        private final String tenantId;
        private final String fingerprint;
        private final String ownershipToken;

        private Claim(String observationId, String tenantId, String fingerprint,
                      String ownershipToken) {
            this.observationId = requiredBounded(observationId, "observationId", OBSERVATION_ID_MAX);
            this.tenantId = nullableBounded(tenantId, "tenantId", TENANT_ID_MAX);
            this.fingerprint = validFingerprint(fingerprint);
            this.ownershipToken = validOwnershipToken(ownershipToken);
        }

        public String observationId() { return observationId; }

        public String tenantId() { return tenantId; }

        public String fingerprint() { return fingerprint; }
    }

    /** A duplicate deliberately has no {@link Claim}; it cannot finalise another caller's work. */
    public record ClaimResult(ClaimOutcome outcome, Optional<Claim> claim) {
        public static ClaimResult claimed(ClaimOutcome outcome, Claim claim) {
            return new ClaimResult(outcome, Optional.of(claim));
        }

        public static ClaimResult duplicate() {
            return new ClaimResult(ClaimOutcome.DUPLICATE, Optional.empty());
        }

        public boolean ownsClaim() {
            return claim.isPresent();
        }
    }

    private static final int OBSERVATION_ID_MAX = 128;
    private static final int TENANT_ID_MAX = 64;
    private static final int FINGERPRINT_LENGTH = 64;
    private static final int SOURCE_MAX = 128;
    private static final int ENGINE_ID_MAX = 128;
    private static final int CASE_ID_MAX = 128;
    private static final int PROCESS_INSTANCE_ID_MAX = 128;
    private static final int ENTITY_ID_MAX = 128;
    private static final int EVENT_TYPE_MAX = 64;
    private static final int FAILURE_DETAIL_MAX = 2000;
    private static final int OWNERSHIP_TOKEN_LENGTH = 43;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OWNERSHIP_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom OWNERSHIP_TOKEN_RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public AppliedObservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims an engine fact once. A failed claim is reclaimed atomically; claimed or applied
     * facts are returned as duplicates without mutation.
     */
    public ClaimResult claim(EngineObservation observation) {
        ObservationValues values = valuesOf(observation);
        Claim claim = newClaim(values);
        try {
            insert(values);
            return ClaimResult.claimed(ClaimOutcome.CLAIMED, claim);
        } catch (DuplicateKeyException duplicate) {
            if (reclaimFailed(values) == 1) {
                return ClaimResult.claimed(ClaimOutcome.RECLAIMED, claim);
            }
            return ClaimResult.duplicate();
        }
    }

    /**
     * Finds the position against which a newly-owned fact is ordered. When the incoming engine
     * supplies a stable entity revision, revision is authoritative and equal revisions are
     * already consumed. Without a revision, the latest engine occurrence time is authoritative.
     * Mixing those modes for one identity is rejected rather than guessed.
     */
    public Optional<AppliedPosition> latestAppliedPosition(EngineObservation observation) {
        ObservationValues values = valuesOf(observation);
        int legacy = jdbc.sql("""
                SELECT COUNT(*) FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE CASE_ID_ = :caseId AND PROCESS_INSTANCE_ID_ = :processInstanceId
                  AND ENTITY_ID_ = :entityId
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'APPLIED'
                  AND (OBSERVATION_KIND_ = 'LEGACY' OR ENGINE_ID_ IS NULL)""")
                .param("caseId", values.caseId())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("tenantId", values.tenantId())
                .query(Integer.class)
                .single();
        if (legacy > 0) {
            throw new LegacyObservationHistoryException("Engine observation history requires "
                    + "reconciliation for case " + values.caseId() + ", process "
                    + values.processInstanceId() + ", entity " + values.entityId());
        }
        int revisioned = observation.entityRevision() == null ? 0 : 1;
        int mixed = jdbc.sql("""
                SELECT COUNT(*) FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE ENGINE_ID_ = :engineId AND CASE_ID_ = :caseId
                  AND PROCESS_INSTANCE_ID_ = :processInstanceId AND ENTITY_ID_ = :entityId
                  AND OBSERVATION_KIND_ = :observationKind
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'APPLIED'
                  AND ((:revisioned = 1 AND ENTITY_REVISION_ IS NULL)
                    OR (:revisioned = 0 AND ENTITY_REVISION_ IS NOT NULL))""")
                .param("engineId", values.engineId())
                .param("caseId", values.caseId())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("observationKind", values.observationKind())
                .param("tenantId", values.tenantId())
                .param("revisioned", revisioned)
                .query(Integer.class)
                .single();
        if (mixed > 0) {
            throw new ObservationOrderingModeException("Engine observation uses mixed ordering modes "
                    + "for case " + values.caseId() + ", process "
                    + values.processInstanceId() + ", kind " + values.observationKind()
                    + ", entity " + values.entityId());
        }
        String ordering = observation.entityRevision() == null
                ? "ENGINE_OCCURRED_AT_ DESC, APPLIED_AT_ DESC"
                : "ENTITY_REVISION_ DESC, ENGINE_OCCURRED_AT_ DESC, APPLIED_AT_ DESC";
        return jdbc.sql("""
                SELECT OBSERVATION_ID_, ENTITY_REVISION_, ENGINE_OCCURRED_AT_, EVENT_TYPE_
                FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE ENGINE_ID_ = :engineId AND CASE_ID_ = :caseId
                  AND PROCESS_INSTANCE_ID_ = :processInstanceId AND ENTITY_ID_ = :entityId
                  AND OBSERVATION_KIND_ = :observationKind
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'APPLIED'
                ORDER BY
                """ + ordering + " FETCH FIRST 1 ROW ONLY")
                .param("engineId", values.engineId())
                .param("caseId", values.caseId())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("observationKind", values.observationKind())
                .param("tenantId", values.tenantId())
                .query((rs, rowNum) -> {
                    Number revision = (Number) rs.getObject("ENTITY_REVISION_");
                    OffsetDateTime occurred = rs.getObject(
                            "ENGINE_OCCURRED_AT_", OffsetDateTime.class);
                    return new AppliedPosition(rs.getString("OBSERVATION_ID_"),
                            revision == null ? null : revision.longValue(), occurred.toInstant(),
                            rs.getString("EVENT_TYPE_"));
                })
                .optional();
    }

    /** Marks this caller's still-claimed observation as applied, or rejects a stale owner. */
    public void markApplied(Claim claim) {
        Claim coordinates = validateClaim(claim);
        int updated = jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET STATUS_ = 'APPLIED', APPLIED_AT_ = SYSTIMESTAMP
                WHERE OBSERVATION_ID_ = :observationId AND FINGERPRINT_ = :fingerprint
                  AND CLAIM_TOKEN_ = :claimToken
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'CLAIMED'""")
                .param("observationId", coordinates.observationId())
                .param("fingerprint", coordinates.fingerprint())
                .param("claimToken", coordinates.ownershipToken)
                .param("tenantId", coordinates.tenantId())
                .update();
        requireOwnedTransition(updated, coordinates, Status.APPLIED);
    }

    /** Finalizes a stale fact without making it eligible as a business ordering watermark. */
    public void markIgnoredStale(Claim claim) {
        Claim coordinates = validateClaim(claim);
        int updated = jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET STATUS_ = 'IGNORED_STALE', IGNORED_AT_ = SYSTIMESTAMP
                WHERE OBSERVATION_ID_ = :observationId AND FINGERPRINT_ = :fingerprint
                  AND CLAIM_TOKEN_ = :claimToken
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'CLAIMED'""")
                .param("observationId", coordinates.observationId())
                .param("fingerprint", coordinates.fingerprint())
                .param("claimToken", coordinates.ownershipToken)
                .param("tenantId", coordinates.tenantId())
                .update();
        requireOwnedTransition(updated, coordinates, Status.IGNORED_STALE);
    }

    /** Marks this caller's still-claimed observation as failed, retaining a bounded diagnostic. */
    public void markFailed(Claim claim, String failureDetail) {
        Claim coordinates = validateClaim(claim);
        String detail = requiredBounded(failureDetail, "failureDetail", FAILURE_DETAIL_MAX);
        int updated = jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET STATUS_ = 'FAILED', FAILED_AT_ = SYSTIMESTAMP, FAILURE_DETAIL_ = :failureDetail
                WHERE OBSERVATION_ID_ = :observationId AND FINGERPRINT_ = :fingerprint
                  AND CLAIM_TOKEN_ = :claimToken
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'CLAIMED'""")
                .param("failureDetail", detail)
                .param("observationId", coordinates.observationId())
                .param("fingerprint", coordinates.fingerprint())
                .param("claimToken", coordinates.ownershipToken)
                .param("tenantId", coordinates.tenantId())
                .update();
        requireOwnedTransition(updated, coordinates, Status.FAILED);
    }

    private void insert(ObservationValues values) {
        jdbc.sql("""
                INSERT INTO CM_APPLIED_ENGINE_OBSERVATION
                  (OBSERVATION_ID_, TENANT_ID_, FINGERPRINT_, STATUS_, SOURCE_, ENGINE_ID_, CASE_ID_,
                   OBSERVATION_KIND_,
                   CLAIM_TOKEN_, PROCESS_INSTANCE_ID_, ENTITY_ID_, ENTITY_REVISION_, EVENT_TYPE_,
                   ENGINE_OCCURRED_AT_, CLAIMED_AT_)
                VALUES
                  (:observationId, :tenantId, :fingerprint, 'CLAIMED', :source, :engineId, :caseId,
                   :observationKind, :claimToken,
                   :processInstanceId, :entityId, :entityRevision, :eventType,
                   :engineOccurredAt, SYSTIMESTAMP)""")
                .param("observationId", values.observationId())
                .param("tenantId", values.tenantId())
                .param("fingerprint", values.fingerprint())
                .param("source", values.source())
                .param("engineId", values.engineId())
                .param("caseId", values.caseId())
                .param("observationKind", values.observationKind())
                .param("claimToken", values.ownershipToken())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("entityRevision", values.entityRevision())
                .param("eventType", values.eventType())
                .param("engineOccurredAt", asOffsetDateTime(values.engineOccurredAt()))
                .update();
    }

    private int reclaimFailed(ObservationValues values) {
        return jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET OBSERVATION_ID_ = :observationId,
                    STATUS_ = 'CLAIMED',
                    CLAIM_TOKEN_ = :claimToken,
                    SOURCE_ = :source,
                    ENGINE_ID_ = :engineId,
                    CASE_ID_ = :caseId,
                    OBSERVATION_KIND_ = :observationKind,
                    PROCESS_INSTANCE_ID_ = :processInstanceId,
                    ENTITY_ID_ = :entityId,
                    ENTITY_REVISION_ = :entityRevision,
                    EVENT_TYPE_ = :eventType,
                    ENGINE_OCCURRED_AT_ = :engineOccurredAt,
                    CLAIMED_AT_ = SYSTIMESTAMP,
                    APPLIED_AT_ = NULL
                WHERE FINGERPRINT_ = :fingerprint
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'FAILED'""")
                .param("observationId", values.observationId())
                .param("claimToken", values.ownershipToken())
                .param("source", values.source())
                .param("engineId", values.engineId())
                .param("caseId", values.caseId())
                .param("observationKind", values.observationKind())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("entityRevision", values.entityRevision())
                .param("eventType", values.eventType())
                .param("engineOccurredAt", asOffsetDateTime(values.engineOccurredAt()))
                .param("fingerprint", values.fingerprint())
                .param("tenantId", values.tenantId())
                .update();
    }

    private static ObservationValues valuesOf(EngineObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        String observationId = requiredBounded(observation.observationId(), "observationId",
                OBSERVATION_ID_MAX);
        String tenantId = nullableBounded(observation.tenantId(), "tenantId", TENANT_ID_MAX);
        String fingerprint = validFingerprint(observation.fingerprint());
        return new ObservationValues(observationId, tenantId, fingerprint,
                requiredBounded(observation.source(), "source", SOURCE_MAX),
                requiredBounded(observation.engineId(), "engineId", ENGINE_ID_MAX),
                requiredBounded(observation.caseId(), "caseId", CASE_ID_MAX),
                requiredBounded(observation.processInstanceId(), "processInstanceId", PROCESS_INSTANCE_ID_MAX),
                observationKind(observation),
                requiredBounded(observation.entityId(), "entityId", ENTITY_ID_MAX),
                observation.entityRevision(),
                requiredBounded(observation.eventType().name(), "eventType", EVENT_TYPE_MAX),
                observation.engineOccurredAt(), newOwnershipToken());
    }

    /** Oracle JDBC cannot bind {@link java.time.Instant} through {@code setObject}; bind the
     * equivalent TIMESTAMP WITH TIME ZONE value explicitly instead. */
    private static OffsetDateTime asOffsetDateTime(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private static String observationKind(EngineObservation observation) {
        if (observation instanceof ProcessObservation) return "PROCESS";
        if (observation instanceof UserTaskObservation) return "USER_TASK";
        if (observation instanceof ActivityLifecycleObservation) return "ACTIVITY";
        if (observation instanceof MilestoneObservation) return "MILESTONE";
        throw new IllegalArgumentException("Unsupported observation type "
                + observation.getClass().getName());
    }

    private static Claim validateClaim(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim must not be null");
        }
        return new Claim(claim.observationId, claim.tenantId, claim.fingerprint,
                claim.ownershipToken);
    }

    private static Claim newClaim(ObservationValues values) {
        return new Claim(values.observationId(), values.tenantId(), values.fingerprint(),
                values.ownershipToken());
    }

    private static String validFingerprint(String fingerprint) {
        String bounded = requiredBounded(fingerprint, "fingerprint", FINGERPRINT_LENGTH);
        if (!SHA_256.matcher(bounded).matches()) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 hex digest");
        }
        return bounded;
    }

    private static String newOwnershipToken() {
        byte[] bytes = new byte[32];
        OWNERSHIP_TOKEN_RANDOM.nextBytes(bytes);
        return validOwnershipToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private static String validOwnershipToken(String token) {
        String bounded = requiredBounded(token, "claimToken", OWNERSHIP_TOKEN_LENGTH);
        if (!OWNERSHIP_TOKEN.matcher(bounded).matches()) {
            throw new IllegalArgumentException("claimToken must be a 256-bit URL-safe token");
        }
        return bounded;
    }

    private static void requireOwnedTransition(int updated, Claim claim, Status target) {
        if (updated != 1) {
            throw new IllegalStateException("Observation '" + claim.observationId()
                    + "' no longer owns the " + claim.fingerprint() + " claim for " + target);
        }
    }

    private static String requiredBounded(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static String nullableBounded(String value, String field, int maxLength) {
        return value == null ? null : requiredBounded(value, field, maxLength);
    }

    private record ObservationValues(String observationId, String tenantId, String fingerprint,
                                     String source, String engineId, String caseId, String processInstanceId,
                                     String observationKind, String entityId, Long entityRevision,
                                     String eventType,
                                     java.time.Instant engineOccurredAt, String ownershipToken) {
    }
}
