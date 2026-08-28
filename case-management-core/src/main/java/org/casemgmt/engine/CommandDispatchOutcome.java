package org.casemgmt.engine;

import java.util.Objects;

/**
 * Evidence available to the pure command policy after a dispatcher, reconciler, observation, or
 * authorised operator action. Raw remote bodies and exception messages are deliberately excluded:
 * later persistence can safely expose policy diagnostics without first trying to redact payloads,
 * credentials, or engine internals.
 */
public record CommandDispatchOutcome(Kind kind, Evidence evidence, int httpStatus) {

    public enum Kind {
        DISPATCH_REQUESTED,
        PRE_SEND_FAILURE,
        HTTP_RESPONSE,
        TIMEOUT_AFTER_SEND,
        READ_FAILURE_AFTER_SEND,
        MALFORMED_RESPONSE,
        DUPLICATE_RESPONSE,
        LEASE_EXPIRED,
        OBSERVATION_CONFIRMED,
        RECONCILIATION_RESULT,
        MANUAL_REVIEW_REQUESTED,
        RECONCILIATION_REQUESTED,
        RETRY_AFTER_REVIEWED_ABSENCE,
        CANCEL_UNSENT,
        CANCEL_AFTER_REVIEWED_ABSENCE
    }

    public enum Evidence {
        NONE,
        CONFIRMED,
        DEFINITIVE_ABSENCE,
        INCONCLUSIVE
    }

    public CommandDispatchOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(evidence, "evidence");
        if (kind == Kind.HTTP_RESPONSE) {
            if (httpStatus < 100 || httpStatus > 599) {
                throw new IllegalArgumentException("HTTP status must be between 100 and 599");
            }
            if (evidence != Evidence.NONE && evidence != Evidence.CONFIRMED) {
                throw new IllegalArgumentException(
                        "HTTP responses may carry only no evidence or confirmation evidence");
            }
            if (evidence == Evidence.CONFIRMED && (httpStatus < 200 || httpStatus >= 300)) {
                throw new IllegalArgumentException(
                        "Only a successful HTTP response may carry confirmation evidence");
            }
        } else if (httpStatus != 0) {
            throw new IllegalArgumentException("Only HTTP outcomes may carry an HTTP status");
        }
        switch (kind) {
            case HTTP_RESPONSE -> { }
            case DUPLICATE_RESPONSE -> requireEvidence(kind, evidence,
                    Evidence.NONE, Evidence.CONFIRMED);
            case OBSERVATION_CONFIRMED -> requireEvidence(kind, evidence, Evidence.CONFIRMED);
            case RECONCILIATION_RESULT -> requireEvidence(kind, evidence,
                    Evidence.CONFIRMED, Evidence.DEFINITIVE_ABSENCE, Evidence.INCONCLUSIVE);
            case RETRY_AFTER_REVIEWED_ABSENCE, CANCEL_AFTER_REVIEWED_ABSENCE ->
                    requireEvidence(kind, evidence, Evidence.DEFINITIVE_ABSENCE);
            default -> requireEvidence(kind, evidence, Evidence.NONE);
        }
    }

    public static CommandDispatchOutcome dispatchRequested() {
        return simple(Kind.DISPATCH_REQUESTED);
    }

    public static CommandDispatchOutcome preSendFailure() {
        return simple(Kind.PRE_SEND_FAILURE);
    }

    public static CommandDispatchOutcome http(int status, Evidence evidence) {
        return new CommandDispatchOutcome(Kind.HTTP_RESPONSE, evidence, status);
    }

    public static CommandDispatchOutcome timeoutAfterSend() {
        return simple(Kind.TIMEOUT_AFTER_SEND);
    }

    public static CommandDispatchOutcome readFailureAfterSend() {
        return simple(Kind.READ_FAILURE_AFTER_SEND);
    }

    public static CommandDispatchOutcome malformedResponse() {
        return simple(Kind.MALFORMED_RESPONSE);
    }

    public static CommandDispatchOutcome duplicateResponse(Evidence evidence) {
        return new CommandDispatchOutcome(Kind.DUPLICATE_RESPONSE, evidence, 0);
    }

    public static CommandDispatchOutcome leaseExpired() {
        return simple(Kind.LEASE_EXPIRED);
    }

    public static CommandDispatchOutcome observationConfirmed() {
        return new CommandDispatchOutcome(Kind.OBSERVATION_CONFIRMED, Evidence.CONFIRMED, 0);
    }

    public static CommandDispatchOutcome reconciliation(Evidence evidence) {
        return new CommandDispatchOutcome(Kind.RECONCILIATION_RESULT, evidence, 0);
    }

    public static CommandDispatchOutcome manualReviewRequested() {
        return simple(Kind.MANUAL_REVIEW_REQUESTED);
    }

    public static CommandDispatchOutcome reconciliationRequested() {
        return simple(Kind.RECONCILIATION_REQUESTED);
    }

    public static CommandDispatchOutcome retryAfterReviewedAbsence() {
        return new CommandDispatchOutcome(
                Kind.RETRY_AFTER_REVIEWED_ABSENCE, Evidence.DEFINITIVE_ABSENCE, 0);
    }

    public static CommandDispatchOutcome cancelUnsent() {
        return simple(Kind.CANCEL_UNSENT);
    }

    public static CommandDispatchOutcome cancelAfterReviewedAbsence() {
        return new CommandDispatchOutcome(
                Kind.CANCEL_AFTER_REVIEWED_ABSENCE, Evidence.DEFINITIVE_ABSENCE, 0);
    }

    private static CommandDispatchOutcome simple(Kind kind) {
        return new CommandDispatchOutcome(kind, Evidence.NONE, 0);
    }

    private static void requireEvidence(Kind kind, Evidence actual, Evidence... allowed) {
        for (Evidence candidate : allowed) {
            if (actual == candidate) {
                return;
            }
        }
        throw new IllegalArgumentException("Outcome " + kind + " cannot carry evidence " + actual);
    }
}
