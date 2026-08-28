package org.casemgmt.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.CONFIRMED;
import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.DEFINITIVE_ABSENCE;
import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.INCONCLUSIVE;

/** Pure production policy for remote-command transitions and failure classification. */
public final class EngineCommandPolicy {

    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_SAFE_SUMMARY_LENGTH = 256;

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(25),
            Duration.ofHours(2), Duration.ofHours(10));
    private static final EnumSet<EngineCommand.Type> RESOURCE_TARGETED = EnumSet.of(
            EngineCommand.Type.CREATE_TASK,
            EngineCommand.Type.CLAIM_TASK,
            EngineCommand.Type.COMPLETE_TASK,
            EngineCommand.Type.CANCEL_PROCESS);

    private final Clock clock;

    public EngineCommandPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Decision transition(String commandId, EngineCommandStatus current,
                               EngineCommand.Type type, int attempts,
                               CommandDispatchOutcome outcome) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }

        Decision terminal = terminalNoOp(current, outcome);
        if (terminal != null) {
            return terminal;
        }
        if (current.isTerminal()) {
            throw illegal(current, outcome);
        }

        return switch (outcome.kind()) {
            case DISPATCH_REQUESTED -> require(current,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE),
                    outcome, decision(EngineCommandStatus.DISPATCHING));
            case PRE_SEND_FAILURE -> requireDispatching(current, outcome,
                    retryOrFail(commandId, attempts, "transport.pre_send",
                            "Remote request was not sent"));
            case HTTP_RESPONSE -> requireDispatching(current, outcome,
                    classifyHttp(type, outcome));
            case TIMEOUT_AFTER_SEND -> requireDispatching(current, outcome,
                    diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                            "transport.timeout", "Remote response timed out after request dispatch"));
            case READ_FAILURE_AFTER_SEND -> requireDispatching(current, outcome,
                    diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                            "transport.read_failure", "Remote response failed after request dispatch"));
            case MALFORMED_RESPONSE -> requireDispatching(current, outcome,
                    diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                            "response.malformed", "Remote response was not valid confirmation evidence"));
            case DUPLICATE_RESPONSE -> requireDispatching(current, outcome,
                    outcome.evidence() == CONFIRMED
                            ? decision(EngineCommandStatus.CONFIRMED)
                            : diagnostic(EngineCommandStatus.CONFLICT,
                                    "response.duplicate", "Remote engine reported a duplicate without matching evidence"));
            case LEASE_EXPIRED -> requireDispatching(current, outcome,
                    diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                            "dispatch.lease_expired", "Dispatch lease expired with an unknown remote outcome"));
            case OBSERVATION_CONFIRMED -> decision(EngineCommandStatus.CONFIRMED);
            case RECONCILIATION_RESULT -> require(current, reconcilable(), outcome,
                    classifyReconciliation(commandId, attempts, outcome));
            case MANUAL_REVIEW_REQUESTED -> require(current,
                    EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT), outcome,
                    decision(EngineCommandStatus.MANUAL_REVIEW));
            case RECONCILIATION_REQUESTED -> require(current, reconcilable(), outcome,
                    decision(EngineCommandStatus.AWAITING_CONFIRMATION));
            case RETRY_AFTER_REVIEWED_ABSENCE -> require(current, reconcilable(), outcome,
                    retryOrFail(commandId, attempts, "review.retry",
                            "Reviewed evidence permits another dispatch attempt"));
            case CANCEL_UNSENT -> require(current,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE),
                    outcome, decision(EngineCommandStatus.CANCELLED));
            case CANCEL_AFTER_REVIEWED_ABSENCE -> require(current,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE,
                            EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW),
                    outcome, decision(EngineCommandStatus.CANCELLED));
        };
    }

    public boolean isResourceTargeted(EngineCommand.Type type) {
        return RESOURCE_TARGETED.contains(Objects.requireNonNull(type, "type"));
    }

    private Decision classifyHttp(EngineCommand.Type type, CommandDispatchOutcome outcome) {
        int status = outcome.httpStatus();
        if (outcome.evidence() == CONFIRMED) {
            return decision(EngineCommandStatus.CONFIRMED);
        }
        if (status >= 200 && status < 300) {
            return diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                    "http." + status, "Remote success response lacked confirmation evidence");
        }
        if (status == 404 && type == EngineCommand.Type.CANCEL_PROCESS) {
            return decision(EngineCommandStatus.CONFIRMED);
        }
        if (status == 409) {
            return diagnostic(EngineCommandStatus.CONFLICT,
                    "http.409", "Remote engine reported a command conflict");
        }
        if (status >= 400 && status < 500) {
            return diagnostic(EngineCommandStatus.FAILED,
                    "http." + status, "Remote engine definitively rejected the request with HTTP " + status);
        }
        return diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                "http." + status, "Remote engine returned HTTP " + status
                        + " with an unknown business outcome");
    }

    private Decision classifyReconciliation(String commandId, int attempts,
                                            CommandDispatchOutcome outcome) {
        if (outcome.evidence() == CONFIRMED) {
            return decision(EngineCommandStatus.CONFIRMED);
        }
        if (outcome.evidence() == DEFINITIVE_ABSENCE) {
            return retryOrFail(commandId, attempts, "reconcile.absent",
                    "Reconciliation proved the remote effect is absent");
        }
        if (outcome.evidence() == INCONCLUSIVE) {
            return diagnostic(EngineCommandStatus.MANUAL_REVIEW,
                    "reconcile.inconclusive", "Reconciliation could not determine the remote outcome");
        }
        throw new IllegalArgumentException("Unsupported reconciliation evidence " + outcome.evidence());
    }

    private Decision retryOrFail(String commandId, int attempts,
                                 String errorCode, String summary) {
        if (attempts >= MAX_ATTEMPTS) {
            return diagnostic(EngineCommandStatus.FAILED,
                    "attempts.exhausted", "Remote command exhausted its dispatch attempts");
        }
        Duration base = BACKOFF.get(Math.min(attempts, BACKOFF.size() - 1));
        long basisPoints = 8_000L + Math.floorMod(
                31L * commandId.hashCode() + attempts, 4_001L);
        long delayMillis = Math.max(1L, Math.multiplyExact(base.toMillis(), basisPoints) / 10_000L);
        OffsetDateTime next = OffsetDateTime.ofInstant(
                clock.instant().plusMillis(delayMillis), ZoneOffset.UTC);
        return new Decision(EngineCommandStatus.RETRYABLE, next,
                safeCode(errorCode), safeSummary(summary));
    }

    private static Decision requireDispatching(EngineCommandStatus current,
                                               CommandDispatchOutcome outcome,
                                               Decision decision) {
        return require(current, EnumSet.of(EngineCommandStatus.DISPATCHING), outcome, decision);
    }

    private static Decision require(EngineCommandStatus current,
                                    EnumSet<EngineCommandStatus> allowed,
                                    CommandDispatchOutcome outcome,
                                    Decision decision) {
        if (!allowed.contains(current)) {
            throw illegal(current, outcome);
        }
        return decision;
    }

    private static EnumSet<EngineCommandStatus> reconcilable() {
        return EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW);
    }

    private static Decision terminalNoOp(EngineCommandStatus current,
                                         CommandDispatchOutcome outcome) {
        if (current == EngineCommandStatus.CONFIRMED
                && outcome.kind() == CommandDispatchOutcome.Kind.OBSERVATION_CONFIRMED) {
            return decision(EngineCommandStatus.CONFIRMED);
        }
        if (current == EngineCommandStatus.CANCELLED
                && (outcome.kind() == CommandDispatchOutcome.Kind.CANCEL_UNSENT
                || outcome.kind() == CommandDispatchOutcome.Kind.CANCEL_AFTER_REVIEWED_ABSENCE)) {
            return decision(EngineCommandStatus.CANCELLED);
        }
        return null;
    }

    private static IllegalStateException illegal(EngineCommandStatus current,
                                                  CommandDispatchOutcome outcome) {
        return new IllegalStateException("Outcome " + outcome.kind()
                + " is not legal from command status " + current);
    }

    private static Decision decision(EngineCommandStatus status) {
        return new Decision(status, null, null, null);
    }

    private static Decision diagnostic(EngineCommandStatus status,
                                       String errorCode, String summary) {
        return new Decision(status, null, safeCode(errorCode), safeSummary(summary));
    }

    private static String safeCode(String value) {
        if (value == null || !value.matches("[a-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Unsafe diagnostic code");
        }
        return value;
    }

    private static String safeSummary(String value) {
        Objects.requireNonNull(value, "safe summary");
        return value.length() <= MAX_SAFE_SUMMARY_LENGTH
                ? value : value.substring(0, MAX_SAFE_SUMMARY_LENGTH);
    }

    public record Decision(EngineCommandStatus status, OffsetDateTime nextAttemptAt,
                           String errorCode, String safeSummary) {
        public Decision {
            Objects.requireNonNull(status, "status");
            if (status == EngineCommandStatus.RETRYABLE && nextAttemptAt == null) {
                throw new IllegalArgumentException("Retryable decisions require a next attempt time");
            }
            if (safeSummary != null && safeSummary.length() > MAX_SAFE_SUMMARY_LENGTH) {
                throw new IllegalArgumentException("Safe summary exceeds its storage bound");
            }
        }
    }
}
