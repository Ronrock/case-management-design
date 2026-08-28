package org.casemgmt.engine;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * Typed, persistence-ready facts presented to {@link EngineCommandPolicy}. Raw response bodies,
 * exception messages, credentials, and business payloads are deliberately not representable.
 */
public record CommandDispatchOutcome(
        Kind kind,
        TransportFailure transportFailure,
        HttpResult httpResult,
        ConfirmationEvidence confirmationEvidence,
        ReviewEvidence reviewEvidence,
        OperatorAction operatorAction) {

    public enum Kind {
        DISPATCH_REQUESTED,
        TRANSPORT_FAILURE,
        HTTP_RESPONSE,
        MALFORMED_RESPONSE,
        DUPLICATE_RESPONSE,
        LEASE_EXPIRED,
        OBSERVATION_CONFIRMED,
        RECONCILIATION_CONFIRMED,
        RECONCILIATION_RESULT,
        MANUAL_REVIEW_REQUESTED,
        RECONCILIATION_REQUESTED,
        RETRY_AFTER_REVIEWED_ABSENCE,
        CANCEL_UNSENT,
        CANCEL_AFTER_REVIEWED_ABSENCE
    }

    public enum Acceptance {
        PROVEN_NOT_ACCEPTED,
        POSSIBLY_ACCEPTED,
        ACCEPTED
    }

    public enum TransportPhase {
        PROVEN_ZERO_BYTES_SENT,
        POSSIBLY_SENT
    }

    public enum TransportFailure {
        PRE_CONNECT_FAILURE(TransportPhase.PROVEN_ZERO_BYTES_SENT),
        PRE_SEND_ZERO_BYTES(TransportPhase.PROVEN_ZERO_BYTES_SENT),
        MID_WRITE_FAILURE(TransportPhase.POSSIBLY_SENT),
        TIMEOUT(TransportPhase.POSSIBLY_SENT),
        READ_FAILURE(TransportPhase.POSSIBLY_SENT),
        UNKNOWN(TransportPhase.POSSIBLY_SENT);

        private final TransportPhase phase;

        TransportFailure(TransportPhase phase) {
            this.phase = phase;
        }

        public TransportPhase phase() {
            return phase;
        }
    }

    public enum ConfirmationSource {
        HTTP_RESPONSE,
        DUPLICATE_RESPONSE,
        OBSERVATION,
        RECONCILIATION,
        LEGACY_MIGRATION
    }

    public enum ReviewSource {
        RECONCILIATION,
        OPERATOR_REVIEW
    }

    public enum ReviewFinding {
        DEFINITIVE_ABSENCE,
        INCONCLUSIVE
    }

    public enum ActionType {
        MANUAL_REVIEW,
        RECONCILE,
        RETRY_OVERRIDE,
        CANCEL
    }

    public enum RemoteState {
        TASK_CREATED,
        TASK_CLAIMED,
        TASK_COMPLETED,
        PROCESS_STARTED,
        PROCESS_CANCELLED,
        PROCESS_TERMINATED,
        ORCHESTRATION_DEPLOYED,
        MESSAGE_CORRELATED
    }

    public record HttpResult(int status, Acceptance acceptance, Duration retryAfter) {
        public HttpResult {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("HTTP status must be between 100 and 599");
            }
            Objects.requireNonNull(acceptance, "acceptance");
            if (status >= 200 && status < 300 && acceptance != Acceptance.ACCEPTED) {
                throw new IllegalArgumentException("2xx responses must be classified as accepted");
            }
            if (status >= 400 && acceptance == Acceptance.ACCEPTED) {
                throw new IllegalArgumentException("Error responses cannot be classified as accepted");
            }
            if (retryAfter != null && retryAfter.isNegative()) {
                throw new IllegalArgumentException("Retry-After must not be negative");
            }
        }
    }

    public record ConfirmationEvidence(
            String tenantId,
            String operationId,
            String commandId,
            EngineCommand.Type commandType,
            String expectedTargetIdentity,
            String remoteIdentity,
            RemoteState remoteState,
            ConfirmationSource source,
            String evidenceReference) {
        public ConfirmationEvidence {
            tenantId = identifier(tenantId, "tenantId");
            operationId = identifier(operationId, "operationId");
            commandId = identifier(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            expectedTargetIdentity = identifier(expectedTargetIdentity, "expectedTargetIdentity");
            remoteIdentity = identifier(remoteIdentity, "remoteIdentity");
            Objects.requireNonNull(remoteState, "remoteState");
            Objects.requireNonNull(source, "source");
            if (source == ConfirmationSource.LEGACY_MIGRATION) {
                throw new IllegalArgumentException(
                        "Live confirmation evidence cannot claim legacy migration provenance");
            }
            evidenceReference = safeReference(evidenceReference, "evidenceReference");
        }
    }

    public record ReviewEvidence(
            String tenantId,
            String operationId,
            String commandId,
            EngineCommand.Type commandType,
            String expectedTargetIdentity,
            ReviewFinding finding,
            ReviewSource source,
            String evidenceReference) {
        public ReviewEvidence {
            tenantId = identifier(tenantId, "tenantId");
            operationId = identifier(operationId, "operationId");
            commandId = identifier(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            expectedTargetIdentity = identifier(expectedTargetIdentity, "expectedTargetIdentity");
            Objects.requireNonNull(finding, "finding");
            Objects.requireNonNull(source, "source");
            evidenceReference = safeReference(evidenceReference, "evidenceReference");
        }
    }

    public record OperatorAction(
            String tenantId,
            String operationId,
            String commandId,
            EngineCommand.Type commandType,
            String expectedTargetIdentity,
            ActionType actionType,
            String actionId,
            String auditReference,
            OffsetDateTime performedAt,
            boolean overrideAutomaticAttemptCap) {
        public OperatorAction {
            tenantId = identifier(tenantId, "tenantId");
            operationId = identifier(operationId, "operationId");
            commandId = identifier(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            expectedTargetIdentity = identifier(expectedTargetIdentity, "expectedTargetIdentity");
            Objects.requireNonNull(actionType, "actionType");
            actionId = safeReference(actionId, "actionId");
            auditReference = safeReference(auditReference, "auditReference");
            performedAt = EngineCommandPolicy.canonicalPersistedTimestamp(
                    performedAt, "performedAt");
            if (overrideAutomaticAttemptCap && actionType != ActionType.RETRY_OVERRIDE) {
                throw new IllegalArgumentException(
                        "Only a retry override action may reset the automatic attempt budget");
            }
        }
    }

    public CommandDispatchOutcome {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.HTTP_RESPONSE && confirmationEvidence != null
                && (httpResult == null || httpResult.status() < 200
                || httpResult.status() >= 300)) {
            throw new IllegalArgumentException(
                    "Only a 2xx HTTP response may carry confirmation evidence");
        }
        if (operatorAction != null) {
            ActionType required = switch (kind) {
                case MANUAL_REVIEW_REQUESTED -> ActionType.MANUAL_REVIEW;
                case RECONCILIATION_REQUESTED -> ActionType.RECONCILE;
                case RETRY_AFTER_REVIEWED_ABSENCE -> ActionType.RETRY_OVERRIDE;
                case CANCEL_UNSENT, CANCEL_AFTER_REVIEWED_ABSENCE -> ActionType.CANCEL;
                default -> throw new IllegalArgumentException(
                        "Outcome " + kind + " cannot carry an operator action");
            };
            if (operatorAction.actionType() != required) {
                throw new IllegalArgumentException("Operator action type "
                        + operatorAction.actionType() + " is invalid for outcome " + kind);
            }
        }
        switch (kind) {
            case DISPATCH_REQUESTED, MALFORMED_RESPONSE, LEASE_EXPIRED ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, false, false, false, false);
            case TRANSPORT_FAILURE ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, true, false, false, false, false);
            case HTTP_RESPONSE ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, true,
                            confirmationEvidence != null, false, false);
            case DUPLICATE_RESPONSE ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, false,
                            confirmationEvidence != null, false, false);
            case OBSERVATION_CONFIRMED, RECONCILIATION_CONFIRMED ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, false, true, false, false);
            case RECONCILIATION_RESULT ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, false, false, true, false);
            case MANUAL_REVIEW_REQUESTED, RECONCILIATION_REQUESTED, CANCEL_UNSENT ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, false, false, false, true);
            case RETRY_AFTER_REVIEWED_ABSENCE, CANCEL_AFTER_REVIEWED_ABSENCE ->
                    requireOnly(kind, transportFailure, httpResult, confirmationEvidence,
                            reviewEvidence, operatorAction, false, false, false, true, true);
        }
    }

    public static CommandDispatchOutcome dispatchRequested() {
        return simple(Kind.DISPATCH_REQUESTED);
    }

    public static CommandDispatchOutcome transportFailure(TransportFailure failure) {
        return new CommandDispatchOutcome(Kind.TRANSPORT_FAILURE,
                Objects.requireNonNull(failure, "failure"), null, null, null, null);
    }

    public static CommandDispatchOutcome http(
            int status, Acceptance acceptance, Duration retryAfter,
            ConfirmationEvidence confirmation) {
        return new CommandDispatchOutcome(Kind.HTTP_RESPONSE, null,
                new HttpResult(status, acceptance, retryAfter), confirmation, null, null);
    }

    public static CommandDispatchOutcome malformedResponse() {
        return simple(Kind.MALFORMED_RESPONSE);
    }

    public static CommandDispatchOutcome duplicateResponse(ConfirmationEvidence confirmation) {
        return new CommandDispatchOutcome(
                Kind.DUPLICATE_RESPONSE, null, null, confirmation, null, null);
    }

    public static CommandDispatchOutcome leaseExpired() {
        return simple(Kind.LEASE_EXPIRED);
    }

    public static CommandDispatchOutcome observation(ConfirmationEvidence confirmation) {
        return new CommandDispatchOutcome(
                Kind.OBSERVATION_CONFIRMED, null, null,
                Objects.requireNonNull(confirmation, "confirmation"), null, null);
    }

    public static CommandDispatchOutcome reconciliationConfirmed(
            ConfirmationEvidence confirmation) {
        return new CommandDispatchOutcome(
                Kind.RECONCILIATION_CONFIRMED, null, null,
                Objects.requireNonNull(confirmation, "confirmation"), null, null);
    }

    public static CommandDispatchOutcome reconciliation(ReviewEvidence evidence) {
        return new CommandDispatchOutcome(
                Kind.RECONCILIATION_RESULT, null, null, null,
                Objects.requireNonNull(evidence, "evidence"), null);
    }

    public static CommandDispatchOutcome manualReviewRequested(OperatorAction action) {
        return manual(Kind.MANUAL_REVIEW_REQUESTED, action);
    }

    public static CommandDispatchOutcome reconciliationRequested(OperatorAction action) {
        return manual(Kind.RECONCILIATION_REQUESTED, action);
    }

    public static CommandDispatchOutcome retryAfterReviewedAbsence(
            ReviewEvidence evidence, OperatorAction action) {
        return reviewed(Kind.RETRY_AFTER_REVIEWED_ABSENCE, evidence, action);
    }

    public static CommandDispatchOutcome cancelUnsent(OperatorAction action) {
        return manual(Kind.CANCEL_UNSENT, action);
    }

    public static CommandDispatchOutcome cancelAfterReviewedAbsence(
            ReviewEvidence evidence, OperatorAction action) {
        return reviewed(Kind.CANCEL_AFTER_REVIEWED_ABSENCE, evidence, action);
    }

    private static CommandDispatchOutcome simple(Kind kind) {
        return new CommandDispatchOutcome(kind, null, null, null, null, null);
    }

    private static CommandDispatchOutcome manual(Kind kind, OperatorAction action) {
        return new CommandDispatchOutcome(kind, null, null, null, null,
                Objects.requireNonNull(action, "action"));
    }

    private static CommandDispatchOutcome reviewed(
            Kind kind, ReviewEvidence evidence, OperatorAction action) {
        return new CommandDispatchOutcome(kind, null, null, null,
                Objects.requireNonNull(evidence, "evidence"),
                Objects.requireNonNull(action, "action"));
    }

    private static void requireOnly(
            Kind kind,
            TransportFailure transportFailure,
            HttpResult httpResult,
            ConfirmationEvidence confirmationEvidence,
            ReviewEvidence reviewEvidence,
            OperatorAction operatorAction,
            boolean requireTransport,
            boolean requireHttp,
            boolean requireConfirmation,
            boolean requireReview,
            boolean requireOperator) {
        requirePresence(kind, "transport failure", transportFailure, requireTransport);
        requirePresence(kind, "HTTP result", httpResult, requireHttp);
        requirePresence(kind, "confirmation evidence", confirmationEvidence, requireConfirmation);
        requirePresence(kind, "review evidence", reviewEvidence, requireReview);
        requirePresence(kind, "operator action", operatorAction, requireOperator);
    }

    private static void requirePresence(
            Kind kind, String field, Object value, boolean required) {
        if (required != (value != null)) {
            throw new IllegalArgumentException("Outcome " + kind + " has invalid " + field);
        }
    }

    private static String identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException(field + " must be 1-255 non-blank characters");
        }
        return value;
    }

    private static String safeReference(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw new IllegalArgumentException(field + " is not a safe opaque reference");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization") || lower.contains("bearer")
                || lower.contains("password") || lower.contains("secret")
                || lower.contains("token")) {
            throw new IllegalArgumentException(field + " must not contain credential material");
        }
        return value;
    }
}
