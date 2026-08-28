package org.casemgmt.repo;

import org.casemgmt.observation.EngineObservation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

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

    public enum Status { CLAIMED, APPLIED, FAILED }

    /** Opaque coordinates required to finalise a claim that this caller owns. */
    public record Claim(String observationId, String tenantId, String fingerprint) {
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
    private static final int CASE_ID_MAX = 128;
    private static final int PROCESS_INSTANCE_ID_MAX = 128;
    private static final int ENTITY_ID_MAX = 128;
    private static final int EVENT_TYPE_MAX = 64;
    private static final int FAILURE_DETAIL_MAX = 2000;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

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
        Claim claim = new Claim(values.observationId(), values.tenantId(), values.fingerprint());
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

    /** Marks this caller's still-claimed observation as applied, or rejects a stale owner. */
    public void markApplied(Claim claim) {
        Claim coordinates = validateClaim(claim);
        int updated = jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET STATUS_ = 'APPLIED', APPLIED_AT_ = SYSTIMESTAMP
                WHERE OBSERVATION_ID_ = :observationId AND FINGERPRINT_ = :fingerprint
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'CLAIMED'""")
                .param("observationId", coordinates.observationId())
                .param("fingerprint", coordinates.fingerprint())
                .param("tenantId", coordinates.tenantId())
                .update();
        requireOwnedTransition(updated, coordinates, Status.APPLIED);
    }

    /** Marks this caller's still-claimed observation as failed, retaining a bounded diagnostic. */
    public void markFailed(Claim claim, String failureDetail) {
        Claim coordinates = validateClaim(claim);
        String detail = requiredBounded(failureDetail, "failureDetail", FAILURE_DETAIL_MAX);
        int updated = jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET STATUS_ = 'FAILED', FAILED_AT_ = SYSTIMESTAMP, FAILURE_DETAIL_ = :failureDetail
                WHERE OBSERVATION_ID_ = :observationId AND FINGERPRINT_ = :fingerprint
                  AND (TENANT_ID_ = :tenantId
                    OR (:tenantId IS NULL AND TENANT_ID_ IS NULL))
                  AND STATUS_ = 'CLAIMED'""")
                .param("failureDetail", detail)
                .param("observationId", coordinates.observationId())
                .param("fingerprint", coordinates.fingerprint())
                .param("tenantId", coordinates.tenantId())
                .update();
        requireOwnedTransition(updated, coordinates, Status.FAILED);
    }

    private void insert(ObservationValues values) {
        jdbc.sql("""
                INSERT INTO CM_APPLIED_ENGINE_OBSERVATION
                  (OBSERVATION_ID_, TENANT_ID_, FINGERPRINT_, STATUS_, SOURCE_, CASE_ID_,
                   PROCESS_INSTANCE_ID_, ENTITY_ID_, ENTITY_REVISION_, EVENT_TYPE_,
                   ENGINE_OCCURRED_AT_, CLAIMED_AT_)
                VALUES
                  (:observationId, :tenantId, :fingerprint, 'CLAIMED', :source, :caseId,
                   :processInstanceId, :entityId, :entityRevision, :eventType,
                   :engineOccurredAt, SYSTIMESTAMP)""")
                .param("observationId", values.observationId())
                .param("tenantId", values.tenantId())
                .param("fingerprint", values.fingerprint())
                .param("source", values.source())
                .param("caseId", values.caseId())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("entityRevision", values.entityRevision())
                .param("eventType", values.eventType())
                .param("engineOccurredAt", values.engineOccurredAt())
                .update();
    }

    private int reclaimFailed(ObservationValues values) {
        return jdbc.sql("""
                UPDATE CM_APPLIED_ENGINE_OBSERVATION
                SET OBSERVATION_ID_ = :observationId,
                    STATUS_ = 'CLAIMED',
                    SOURCE_ = :source,
                    CASE_ID_ = :caseId,
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
                .param("source", values.source())
                .param("caseId", values.caseId())
                .param("processInstanceId", values.processInstanceId())
                .param("entityId", values.entityId())
                .param("entityRevision", values.entityRevision())
                .param("eventType", values.eventType())
                .param("engineOccurredAt", values.engineOccurredAt())
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
        String fingerprint = requiredBounded(observation.fingerprint(), "fingerprint", FINGERPRINT_LENGTH);
        if (!SHA_256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 hex digest");
        }
        return new ObservationValues(observationId, tenantId, fingerprint,
                requiredBounded(observation.source(), "source", SOURCE_MAX),
                requiredBounded(observation.caseId(), "caseId", CASE_ID_MAX),
                requiredBounded(observation.processInstanceId(), "processInstanceId", PROCESS_INSTANCE_ID_MAX),
                requiredBounded(observation.entityId(), "entityId", ENTITY_ID_MAX),
                observation.entityRevision(),
                requiredBounded(observation.eventType().name(), "eventType", EVENT_TYPE_MAX),
                observation.engineOccurredAt());
    }

    private static Claim validateClaim(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim must not be null");
        }
        String observationId = requiredBounded(claim.observationId(), "observationId", OBSERVATION_ID_MAX);
        String tenantId = nullableBounded(claim.tenantId(), "tenantId", TENANT_ID_MAX);
        String fingerprint = requiredBounded(claim.fingerprint(), "fingerprint", FINGERPRINT_LENGTH);
        if (!SHA_256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 hex digest");
        }
        return new Claim(observationId, tenantId, fingerprint);
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
                                     String source, String caseId, String processInstanceId,
                                     String entityId, Long entityRevision, String eventType,
                                     java.time.Instant engineOccurredAt) {
    }
}
