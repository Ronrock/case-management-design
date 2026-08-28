package org.casemgmt.engine;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportPhase.PROVEN_ZERO_BYTES_SENT;

/** Pure production policy for remote-command transitions and failure classification. */
public final class EngineCommandPolicy {

    public static final int MAX_AUTOMATIC_RETRIES = 5;
    public static final int MAX_AUTOMATIC_ATTEMPTS = MAX_AUTOMATIC_RETRIES + 1;
    public static final int MAX_SAFE_SUMMARY_LENGTH = 256;
    public static final Duration MAX_RETRY_AFTER = Duration.ofDays(30);
    public static final OffsetDateTime MAX_PERSISTABLE_TIMESTAMP = OffsetDateTime.parse(
            "9999-12-31T23:59:59.999999Z");

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(25),
            Duration.ofHours(2), Duration.ofHours(10));
    private static final EnumSet<EngineCommand.Type> RESOURCE_TARGETED = EnumSet.of(
            EngineCommand.Type.CREATE_TASK,
            EngineCommand.Type.CLAIM_TASK,
            EngineCommand.Type.COMPLETE_TASK,
            EngineCommand.Type.CANCEL_PROCESS);
    private static final EnumSet<EngineCommand.Type> EXISTING_REMOTE_TARGET = EnumSet.of(
            EngineCommand.Type.CLAIM_TASK,
            EngineCommand.Type.COMPLETE_TASK,
            EngineCommand.Type.CANCEL_PROCESS);

    private final Clock clock;

    public EngineCommandPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Applies one fact to a command. {@code totalDispatchAttempts} counts requests whose dispatch
     * has started, including the currently classified request; it is not a retry counter.
     */
    public Decision transition(CommandContext command, EngineCommandStatus current,
                               int totalDispatchAttempts, CommandDispatchOutcome outcome) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(outcome, "outcome");
        if (totalDispatchAttempts < 0) {
            throw new IllegalArgumentException("totalDispatchAttempts must not be negative");
        }

        validateEvidence(command, outcome);

        if (current == EngineCommandStatus.CONFIRMED
                && outcome.confirmationEvidence() != null) {
            return confirmed(totalDispatchAttempts, outcome.confirmationEvidence());
        }
        if (current == EngineCommandStatus.CANCELLED && isCancellation(outcome)) {
            return operatorDecision(EngineCommandStatus.CANCELLED, totalDispatchAttempts,
                    null, null, outcome, false);
        }
        if (current.isTerminal()) {
            throw illegal(current, outcome);
        }

        return switch (outcome.kind()) {
            case DISPATCH_REQUESTED -> startDispatch(
                    current, totalDispatchAttempts, outcome);
            case TRANSPORT_FAILURE -> requireDispatching(current, totalDispatchAttempts, outcome,
                    outcome.transportFailure().phase() == PROVEN_ZERO_BYTES_SENT
                            ? automaticRetry(command, totalDispatchAttempts, null,
                                    "transport.not_sent", "Remote request sent zero bytes")
                            : diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                                    totalDispatchAttempts, "transport.possibly_sent",
                                    "Remote request may have been sent"));
            case HTTP_RESPONSE -> requireDispatching(current, totalDispatchAttempts, outcome,
                    classifyHttp(command, totalDispatchAttempts, outcome));
            case MALFORMED_RESPONSE -> requireDispatching(current, totalDispatchAttempts, outcome,
                    diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                            totalDispatchAttempts, "response.malformed",
                            "Remote response was not valid confirmation evidence"));
            case DUPLICATE_RESPONSE -> requireDispatching(current, totalDispatchAttempts, outcome,
                    outcome.confirmationEvidence() == null
                            ? diagnostic(EngineCommandStatus.CONFLICT, totalDispatchAttempts,
                                    "response.duplicate",
                                    "Duplicate response lacked matching confirmation evidence")
                            : confirmed(totalDispatchAttempts, outcome.confirmationEvidence()));
            case LEASE_EXPIRED -> requireDispatching(current, totalDispatchAttempts, outcome,
                    diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                            totalDispatchAttempts, "dispatch.lease_expired",
                            "Dispatch lease expired with an unknown remote outcome"));
            case OBSERVATION_CONFIRMED -> require(current, confirmable(), outcome,
                    confirmed(totalDispatchAttempts, outcome.confirmationEvidence()));
            case RECONCILIATION_CONFIRMED -> require(current, reconcilable(), outcome,
                    confirmed(totalDispatchAttempts, outcome.confirmationEvidence()));
            case RECONCILIATION_RESULT -> require(current, reconcilable(), outcome,
                    classifyReconciliation(command, totalDispatchAttempts, outcome));
            case MANUAL_REVIEW_REQUESTED -> require(current,
                    EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW),
                    outcome, operatorDecision(EngineCommandStatus.MANUAL_REVIEW,
                            totalDispatchAttempts, "review.requested",
                            "Operator requested manual review", outcome, false));
            case RECONCILIATION_REQUESTED -> require(current, reconcilable(), outcome,
                    operatorDecision(EngineCommandStatus.AWAITING_CONFIRMATION,
                            totalDispatchAttempts, "reconcile.requested",
                            "Operator requested reconciliation", outcome, false));
            case RETRY_AFTER_REVIEWED_ABSENCE -> require(current,
                    EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW,
                            EngineCommandStatus.RETRYABLE), outcome,
                    reviewedOperatorRetry(current, totalDispatchAttempts, outcome));
            case CANCEL_UNSENT -> require(current,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE),
                    outcome, operatorDecision(EngineCommandStatus.CANCELLED,
                            totalDispatchAttempts, null, null, outcome, false));
            case CANCEL_AFTER_REVIEWED_ABSENCE -> require(current,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE,
                            EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW),
                    outcome, operatorDecision(EngineCommandStatus.CANCELLED,
                            totalDispatchAttempts, null, null, outcome, false));
        };
    }

    public boolean isResourceTargeted(EngineCommand.Type type) {
        return RESOURCE_TARGETED.contains(Objects.requireNonNull(type, "type"));
    }

    public Set<CommandDispatchOutcome.RemoteState> expectedTerminalStates(
            EngineCommand.Type type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case CREATE_TASK -> EnumSet.of(CommandDispatchOutcome.RemoteState.TASK_CREATED);
            case CLAIM_TASK -> EnumSet.of(CommandDispatchOutcome.RemoteState.TASK_CLAIMED);
            case COMPLETE_TASK -> EnumSet.of(CommandDispatchOutcome.RemoteState.TASK_COMPLETED);
            case START_PROCESS -> EnumSet.of(CommandDispatchOutcome.RemoteState.PROCESS_STARTED);
            case CANCEL_PROCESS -> EnumSet.of(
                    CommandDispatchOutcome.RemoteState.PROCESS_CANCELLED,
                    CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED);
            case DEPLOY_ORCHESTRATION -> EnumSet.of(
                    CommandDispatchOutcome.RemoteState.ORCHESTRATION_DEPLOYED);
            case CORRELATE_MESSAGE -> EnumSet.of(
                    CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED);
        };
    }

    private Decision startDispatch(EngineCommandStatus current, int totalDispatchAttempts,
                                   CommandDispatchOutcome outcome) {
        if (totalDispatchAttempts >= MAX_AUTOMATIC_ATTEMPTS) {
            throw new IllegalStateException("Automatic dispatch attempt budget is exhausted");
        }
        return require(current,
                EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE),
                outcome, plain(EngineCommandStatus.DISPATCHING, totalDispatchAttempts + 1));
    }

    private Decision classifyHttp(CommandContext command, int totalDispatchAttempts,
                                  CommandDispatchOutcome outcome) {
        if (outcome.confirmationEvidence() != null) {
            return confirmed(totalDispatchAttempts, outcome.confirmationEvidence());
        }
        CommandDispatchOutcome.HttpResult http = outcome.httpResult();
        if (http.acceptance() == ACCEPTED) {
            return diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                    totalDispatchAttempts, "response.unconfirmed",
                    "Accepted response lacked matching confirmation evidence");
        }
        if (http.acceptance() == POSSIBLY_ACCEPTED) {
            return diagnostic(EngineCommandStatus.AWAITING_CONFIRMATION,
                    totalDispatchAttempts, "response.ambiguous",
                    "Remote request may have been accepted");
        }

        int status = http.status();
        if (status == 409) {
            return diagnostic(EngineCommandStatus.CONFLICT, totalDispatchAttempts,
                    "http.409.conflict", "Remote engine reported a command conflict");
        }
        if (status == 404 && command.commandType() == EngineCommand.Type.CANCEL_PROCESS) {
            return diagnostic(EngineCommandStatus.CONFLICT, totalDispatchAttempts,
                    "target.not_found", "Cancellation target was not found without terminal proof");
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return automaticRetry(command, totalDispatchAttempts, http.retryAfter(),
                    "http." + status + ".not_accepted",
                    "Remote endpoint proved the request was not accepted");
        }
        return diagnostic(EngineCommandStatus.FAILED, totalDispatchAttempts,
                "http." + status + ".rejected",
                "Remote endpoint definitively rejected the request");
    }

    private Decision classifyReconciliation(CommandContext command, int totalDispatchAttempts,
                                            CommandDispatchOutcome outcome) {
        if (outcome.reviewEvidence().finding() == DEFINITIVE_ABSENCE) {
            return automaticRetry(command, totalDispatchAttempts, null,
                    "reconcile.absent", "Reconciliation proved the remote effect is absent",
                    outcome.reviewEvidence());
        }
        if (outcome.reviewEvidence().finding() == INCONCLUSIVE) {
            return diagnostic(EngineCommandStatus.MANUAL_REVIEW, totalDispatchAttempts,
                    "reconcile.inconclusive",
                    "Reconciliation could not determine the remote outcome",
                    outcome.reviewEvidence());
        }
        throw new IllegalArgumentException(
                "Unsupported reconciliation finding " + outcome.reviewEvidence().finding());
    }

    private Decision reviewedOperatorRetry(EngineCommandStatus current,
                                           int totalDispatchAttempts,
                                           CommandDispatchOutcome outcome) {
        CommandDispatchOutcome.OperatorAction action = outcome.operatorAction();
        boolean exhausted = totalDispatchAttempts >= MAX_AUTOMATIC_ATTEMPTS;
        boolean replayOfOverride = current == EngineCommandStatus.RETRYABLE
                && totalDispatchAttempts == 0 && action.overrideAutomaticAttemptCap();
        if (replayOfOverride) {
            return operatorRetryDecision(0, true, outcome);
        }
        if (exhausted != action.overrideAutomaticAttemptCap()) {
            throw new IllegalStateException(exhausted
                    ? "Retry beyond the automatic attempt cap requires an audited override"
                    : "Attempt-cap override is not valid before the automatic budget is exhausted");
        }
        int attemptsAfterDecision = exhausted ? 0 : totalDispatchAttempts;
        return operatorRetryDecision(attemptsAfterDecision, exhausted, outcome);
    }

    private Decision operatorRetryDecision(int attemptsAfterDecision, boolean resetAttempts,
                                           CommandDispatchOutcome outcome) {
        CommandDispatchOutcome.OperatorAction action = outcome.operatorAction();
        OffsetDateTime retryAt = clamp(action.performedAt());
        return new Decision(EngineCommandStatus.RETRYABLE, retryAt,
                "review.retry", "Reviewed evidence permits another dispatch attempt",
                attemptsAfterDecision, resetAttempts,
                outcome.reviewEvidence().evidenceReference(),
                outcome.reviewEvidence().source().name(),
                action.actionId(), action.auditReference());
    }

    private Decision automaticRetry(CommandContext command, int totalDispatchAttempts,
                                    Duration retryAfter, String errorCode, String summary) {
        return automaticRetry(command, totalDispatchAttempts, retryAfter,
                errorCode, summary, null);
    }

    private Decision automaticRetry(CommandContext command, int totalDispatchAttempts,
                                    Duration retryAfter, String errorCode, String summary,
                                    CommandDispatchOutcome.ReviewEvidence evidence) {
        if (totalDispatchAttempts >= MAX_AUTOMATIC_ATTEMPTS) {
            return diagnostic(EngineCommandStatus.FAILED, totalDispatchAttempts,
                    "attempts.exhausted", "Remote command exhausted automatic dispatch attempts",
                    evidence);
        }
        Duration base = BACKOFF.get(Math.min(
                Math.max(totalDispatchAttempts - 1, 0), BACKOFF.size() - 1));
        long basisPoints = 8_000L + Math.floorMod(
                31L * command.commandId().hashCode() + totalDispatchAttempts, 4_001L);
        long jitterMillis = Math.max(1L,
                Math.multiplyExact(base.toMillis(), basisPoints) / 10_000L);
        Duration delay = Duration.ofMillis(jitterMillis);
        if (retryAfter != null) {
            Duration boundedRetryAfter = retryAfter.compareTo(MAX_RETRY_AFTER) > 0
                    ? MAX_RETRY_AFTER : retryAfter;
            if (boundedRetryAfter.compareTo(delay) > 0) {
                delay = boundedRetryAfter;
            }
        }
        OffsetDateTime next = saturatingAdd(clock.instant(), delay);
        return new Decision(EngineCommandStatus.RETRYABLE, next,
                safeCode(errorCode), safeSummary(summary), totalDispatchAttempts,
                false,
                evidence == null ? null : evidence.evidenceReference(),
                evidence == null ? null : evidence.source().name(), null, null);
    }

    private void validateEvidence(CommandContext command, CommandDispatchOutcome outcome) {
        if (outcome.confirmationEvidence() != null) {
            validateConfirmation(command, outcome);
        }
        if (outcome.reviewEvidence() != null) {
            validateReview(command, outcome);
        }
        if (outcome.operatorAction() != null) {
            validateOperator(command, outcome.operatorAction());
        }
    }

    private void validateConfirmation(CommandContext command, CommandDispatchOutcome outcome) {
        CommandDispatchOutcome.ConfirmationEvidence evidence = outcome.confirmationEvidence();
        same(command.tenantId(), evidence.tenantId(), "tenant");
        same(command.operationId(), evidence.operationId(), "operation");
        same(command.commandId(), evidence.commandId(), "command");
        if (command.commandType() != evidence.commandType()) {
            throw new IllegalArgumentException("Confirmation command type mismatch");
        }
        same(command.expectedTargetIdentity(), evidence.expectedTargetIdentity(), "target");
        if (!expectedTerminalStates(command.commandType()).contains(evidence.remoteState())) {
            throw new IllegalArgumentException("Confirmation remote state mismatch");
        }
        if (EXISTING_REMOTE_TARGET.contains(command.commandType())
                && !command.expectedTargetIdentity().equals(evidence.remoteIdentity())) {
            throw new IllegalArgumentException("Confirmation remote identity mismatch");
        }
        CommandDispatchOutcome.ConfirmationSource requiredSource = switch (outcome.kind()) {
            case HTTP_RESPONSE -> CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE;
            case DUPLICATE_RESPONSE -> CommandDispatchOutcome.ConfirmationSource.DUPLICATE_RESPONSE;
            case OBSERVATION_CONFIRMED -> CommandDispatchOutcome.ConfirmationSource.OBSERVATION;
            case RECONCILIATION_CONFIRMED ->
                    CommandDispatchOutcome.ConfirmationSource.RECONCILIATION;
            default -> throw new IllegalArgumentException(
                    "Outcome kind cannot carry confirmation evidence: " + outcome.kind());
        };
        if (evidence.source() != requiredSource) {
            throw new IllegalArgumentException("Confirmation source mismatch");
        }
    }

    private void validateReview(CommandContext command, CommandDispatchOutcome outcome) {
        CommandDispatchOutcome.ReviewEvidence evidence = outcome.reviewEvidence();
        same(command.tenantId(), evidence.tenantId(), "tenant");
        same(command.operationId(), evidence.operationId(), "operation");
        same(command.commandId(), evidence.commandId(), "command");
        if (command.commandType() != evidence.commandType()) {
            throw new IllegalArgumentException("Review command type mismatch");
        }
        same(command.expectedTargetIdentity(), evidence.expectedTargetIdentity(), "target");
        CommandDispatchOutcome.ReviewSource expectedSource =
                outcome.kind() == CommandDispatchOutcome.Kind.RECONCILIATION_RESULT
                        ? CommandDispatchOutcome.ReviewSource.RECONCILIATION
                        : CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
        if (evidence.source() != expectedSource) {
            throw new IllegalArgumentException("Review source mismatch");
        }
        if ((outcome.kind() == CommandDispatchOutcome.Kind.RETRY_AFTER_REVIEWED_ABSENCE
                || outcome.kind() == CommandDispatchOutcome.Kind.CANCEL_AFTER_REVIEWED_ABSENCE)
                && evidence.finding() != DEFINITIVE_ABSENCE) {
            throw new IllegalArgumentException("Operator action requires definitive absence evidence");
        }
    }

    private static void validateOperator(
            CommandContext command, CommandDispatchOutcome.OperatorAction action) {
        same(command.tenantId(), action.tenantId(), "tenant");
        same(command.operationId(), action.operationId(), "operation");
        same(command.commandId(), action.commandId(), "command");
        if (command.commandType() != action.commandType()) {
            throw new IllegalArgumentException("Operator action command type mismatch");
        }
        same(command.expectedTargetIdentity(), action.expectedTargetIdentity(), "target");
    }

    private static void same(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Evidence " + field + " mismatch");
        }
    }

    private static Decision requireDispatching(
            EngineCommandStatus current, int totalDispatchAttempts,
            CommandDispatchOutcome outcome, Decision decision) {
        if (totalDispatchAttempts == 0) {
            throw new IllegalArgumentException(
                    "totalDispatchAttempts must include the current dispatch");
        }
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

    private static EnumSet<EngineCommandStatus> confirmable() {
        return EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.DISPATCHING,
                EngineCommandStatus.RETRYABLE, EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW);
    }

    private static EnumSet<EngineCommandStatus> reconcilable() {
        return EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW);
    }

    private static boolean isCancellation(CommandDispatchOutcome outcome) {
        return outcome.kind() == CommandDispatchOutcome.Kind.CANCEL_UNSENT
                || outcome.kind() == CommandDispatchOutcome.Kind.CANCEL_AFTER_REVIEWED_ABSENCE;
    }

    private static IllegalStateException illegal(EngineCommandStatus current,
                                                  CommandDispatchOutcome outcome) {
        return new IllegalStateException("Outcome " + outcome.kind()
                + " is not legal from command status " + current);
    }

    private static Decision plain(EngineCommandStatus status, int totalDispatchAttempts) {
        return new Decision(status, null, null, null, totalDispatchAttempts,
                false, null, null, null, null);
    }

    private static Decision confirmed(
            int totalDispatchAttempts,
            CommandDispatchOutcome.ConfirmationEvidence evidence) {
        return new Decision(EngineCommandStatus.CONFIRMED, null, null, null,
                totalDispatchAttempts, false, evidence.evidenceReference(),
                evidence.source().name(), null, null);
    }

    private static Decision diagnostic(EngineCommandStatus status,
                                       int totalDispatchAttempts,
                                       String errorCode, String summary) {
        return new Decision(status, null, safeCode(errorCode), safeSummary(summary),
                totalDispatchAttempts, false, null, null, null, null);
    }

    private static Decision diagnostic(EngineCommandStatus status,
                                       int totalDispatchAttempts,
                                       String errorCode, String summary,
                                       CommandDispatchOutcome.ReviewEvidence evidence) {
        return new Decision(status, null, safeCode(errorCode), safeSummary(summary),
                totalDispatchAttempts, false,
                evidence == null ? null : evidence.evidenceReference(),
                evidence == null ? null : evidence.source().name(), null, null);
    }

    private static Decision operatorDecision(
            EngineCommandStatus status, int totalDispatchAttempts,
            String errorCode, String summary,
            CommandDispatchOutcome outcome, boolean resetAttempts) {
        CommandDispatchOutcome.OperatorAction action = outcome.operatorAction();
        CommandDispatchOutcome.ReviewEvidence evidence = outcome.reviewEvidence();
        return new Decision(status, null,
                errorCode == null ? null : safeCode(errorCode),
                summary == null ? null : safeSummary(summary),
                totalDispatchAttempts, resetAttempts,
                evidence == null ? null : evidence.evidenceReference(),
                evidence == null ? null : evidence.source().name(),
                action.actionId(), action.auditReference());
    }

    private static OffsetDateTime saturatingAdd(Instant start, Duration delay) {
        Instant maximum = MAX_PERSISTABLE_TIMESTAMP.toInstant();
        if (start.compareTo(maximum) >= 0) {
            return MAX_PERSISTABLE_TIMESTAMP;
        }
        try {
            Instant result = start.plus(delay);
            return result.compareTo(maximum) >= 0
                    ? MAX_PERSISTABLE_TIMESTAMP
                    : OffsetDateTime.ofInstant(result, ZoneOffset.UTC);
        } catch (ArithmeticException | DateTimeException ex) {
            return MAX_PERSISTABLE_TIMESTAMP;
        }
    }

    private static OffsetDateTime clamp(OffsetDateTime value) {
        return value.toInstant().compareTo(MAX_PERSISTABLE_TIMESTAMP.toInstant()) >= 0
                ? MAX_PERSISTABLE_TIMESTAMP : value.withOffsetSameInstant(ZoneOffset.UTC);
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

    public record CommandContext(
            String tenantId,
            String operationId,
            String commandId,
            EngineCommand.Type commandType,
            String expectedTargetIdentity) {
        public CommandContext {
            tenantId = identifier(tenantId, "tenantId");
            operationId = identifier(operationId, "operationId");
            commandId = identifier(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            expectedTargetIdentity = identifier(expectedTargetIdentity, "expectedTargetIdentity");
        }

        private static String identifier(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 255) {
                throw new IllegalArgumentException(
                        field + " must be 1-255 non-blank characters");
            }
            return value;
        }
    }

    public record Decision(
            EngineCommandStatus status,
            OffsetDateTime nextAttemptAt,
            String errorCode,
            String safeSummary,
            int totalDispatchAttempts,
            boolean resetAutomaticAttempts,
            String evidenceReference,
            String evidenceSource,
            String operatorActionId,
            String auditReference) {
        public Decision {
            Objects.requireNonNull(status, "status");
            if (totalDispatchAttempts < 0) {
                throw new IllegalArgumentException("totalDispatchAttempts must not be negative");
            }
            if (status == EngineCommandStatus.RETRYABLE && nextAttemptAt == null) {
                throw new IllegalArgumentException("Retryable decisions require a next attempt time");
            }
            if (status != EngineCommandStatus.RETRYABLE && nextAttemptAt != null) {
                throw new IllegalArgumentException("Only retryable decisions may schedule an attempt");
            }
            if (resetAutomaticAttempts && status != EngineCommandStatus.RETRYABLE) {
                throw new IllegalArgumentException("Only retry decisions may reset attempts");
            }
            if ((operatorActionId == null) != (auditReference == null)) {
                throw new IllegalArgumentException(
                        "Operator action and audit reference must be retained together");
            }
            if (safeSummary != null && safeSummary.length() > MAX_SAFE_SUMMARY_LENGTH) {
                throw new IllegalArgumentException("Safe summary exceeds its storage bound");
            }
        }
    }
}
