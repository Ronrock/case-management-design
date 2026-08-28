package org.casemgmt.engine;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    public static final OffsetDateTime MIN_PERSISTABLE_TIMESTAMP = OffsetDateTime.parse(
            "0001-01-01T00:00:00Z");

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

    /** Applies one fact to the complete, already committed state of a command. */
    public Decision transition(CommandState state, CommandDispatchOutcome outcome) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(outcome, "outcome");
        CommandContext command = state.command();
        Decision committed = state.committedDecision();
        validateCommittedState(command, committed);
        validateEvidence(command, outcome);

        Decision replay = replayIfCommitted(committed, outcome);
        if (replay != null) {
            return replay;
        }
        if (committed.status().isTerminal()) {
            throw illegal(committed.status(), outcome);
        }

        return switch (outcome.kind()) {
            case DISPATCH_REQUESTED -> startDispatch(committed, outcome);
            case TRANSPORT_FAILURE -> requireDispatching(committed, outcome,
                    outcome.transportFailure().phase() == PROVEN_ZERO_BYTES_SENT
                            ? automaticRetry(command, committed, null,
                                    "transport.not_sent", "Remote request sent zero bytes", null)
                            : diagnostic(committed, EngineCommandStatus.AWAITING_CONFIRMATION,
                                    "transport.possibly_sent",
                                    "Remote request may have been sent", null));
            case HTTP_RESPONSE -> requireDispatching(committed, outcome,
                    classifyHttp(command, committed, outcome));
            case MALFORMED_RESPONSE -> requireDispatching(committed, outcome,
                    diagnostic(committed, EngineCommandStatus.AWAITING_CONFIRMATION,
                            "response.malformed",
                            "Remote response was not valid confirmation evidence", null));
            case DUPLICATE_RESPONSE -> requireDispatching(committed, outcome,
                    outcome.confirmationEvidence() == null
                            ? diagnostic(committed, EngineCommandStatus.CONFLICT,
                                    "response.duplicate",
                                    "Duplicate response lacked matching confirmation evidence",
                                    null)
                            : confirmed(committed, outcome.confirmationEvidence()));
            case LEASE_EXPIRED -> requireDispatching(committed, outcome,
                    diagnostic(committed, EngineCommandStatus.AWAITING_CONFIRMATION,
                            "dispatch.lease_expired",
                            "Dispatch lease expired with an unknown remote outcome", null));
            case OBSERVATION_CONFIRMED -> require(committed, confirmable(), outcome,
                    confirmed(committed, outcome.confirmationEvidence()));
            case RECONCILIATION_CONFIRMED -> require(committed, reconcilable(), outcome,
                    confirmed(committed, outcome.confirmationEvidence()));
            case RECONCILIATION_RESULT -> require(committed, reconcilable(), outcome,
                    classifyReconciliation(command, committed, outcome));
            case MANUAL_REVIEW_REQUESTED -> require(committed,
                    EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW),
                    outcome, operatorDecision(committed, EngineCommandStatus.MANUAL_REVIEW,
                            "review.requested", "Operator requested manual review", outcome,
                            false, committed.automaticAttemptsInBudget(),
                            committed.budgetEpoch()));
            case RECONCILIATION_REQUESTED -> require(committed, reconcilable(), outcome,
                    operatorDecision(committed, EngineCommandStatus.AWAITING_CONFIRMATION,
                            "reconcile.requested", "Operator requested reconciliation", outcome,
                            false, committed.automaticAttemptsInBudget(),
                            committed.budgetEpoch()));
            case RETRY_AFTER_REVIEWED_ABSENCE -> require(committed,
                    EnumSet.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW,
                            EngineCommandStatus.RETRYABLE), outcome,
                    reviewedOperatorRetry(committed, outcome));
            case CANCEL_UNSENT -> require(committed,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE),
                    outcome, operatorDecision(committed, EngineCommandStatus.CANCELLED,
                            null, null, outcome, false,
                            committed.automaticAttemptsInBudget(), committed.budgetEpoch()));
            case CANCEL_AFTER_REVIEWED_ABSENCE -> require(committed,
                    EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE,
                            EngineCommandStatus.AWAITING_CONFIRMATION,
                            EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW),
                    outcome, operatorDecision(committed, EngineCommandStatus.CANCELLED,
                            null, null, outcome, false,
                            committed.automaticAttemptsInBudget(), committed.budgetEpoch()));
        };
    }

    public boolean isResourceTargeted(EngineCommand.Type type) {
        return RESOURCE_TARGETED.contains(Objects.requireNonNull(type, "type"));
    }

    public Set<CommandDispatchOutcome.RemoteState> expectedTerminalStates(
            EngineCommand.Type type) {
        return terminalStates(type);
    }

    /** Explicit migration-only construction for historical PoC rows whose status was DONE. */
    public static Decision migrateLegacyDone(
            CommandContext command,
            String legacyRowId,
            String migrationReference,
            OffsetDateTime migratedAt,
            int legacyDispatchAttempts) {
        Objects.requireNonNull(command, "command");
        if (legacyDispatchAttempts < 0
                || legacyDispatchAttempts > MAX_AUTOMATIC_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "Legacy DONE attempts are outside the production automatic budget");
        }
        LegacyConfirmationEvidence evidence = new LegacyConfirmationEvidence(
                command, legacyRowId, migrationReference);
        return new Decision(EngineCommandStatus.CONFIRMED, migratedAt, null, null, null,
                legacyDispatchAttempts, legacyDispatchAttempts, 0, false,
                null, evidence, null, null, List.of());
    }

    private static Set<CommandDispatchOutcome.RemoteState> terminalStates(
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

    private Decision replayIfCommitted(Decision committed, CommandDispatchOutcome outcome) {
        if (committed.status() == EngineCommandStatus.CONFIRMED
                && outcome.confirmationEvidence() != null) {
            if (committed.terminalConfirmation() != null) {
                requireEquivalentConfirmation(
                        committed.terminalConfirmation(), outcome.confirmationEvidence());
            } else {
                requireCompatibleLegacyConfirmation(
                        committed.legacyConfirmation(), outcome.confirmationEvidence());
            }
            return committed;
        }
        CommandDispatchOutcome.OperatorAction incoming = outcome.operatorAction();
        if (incoming != null) {
            for (ProcessedAction processed : committed.processedActions()) {
                if (incoming.actionId().equals(processed.action().actionId())) {
                    if (!incoming.equals(processed.action())
                            || !Objects.equals(
                                    outcome.reviewEvidence(), processed.reviewEvidence())) {
                        throw new IllegalArgumentException(
                                "Operator action identity was repackaged with different provenance");
                    }
                    return committed;
                }
            }
        }
        return null;
    }

    private static List<ProcessedAction> appendProcessedAction(
            Decision committed, CommandDispatchOutcome outcome) {
        List<ProcessedAction> appended = new ArrayList<>(committed.processedActions());
        long nextSequence = appended.isEmpty()
                ? 1L : Math.incrementExact(appended.get(appended.size() - 1).sequence());
        appended.add(new ProcessedAction(
                nextSequence, outcome.operatorAction(), outcome.reviewEvidence()));
        return List.copyOf(appended);
    }

    private static void requireEquivalentConfirmation(
            CommandDispatchOutcome.ConfirmationEvidence committed,
            CommandDispatchOutcome.ConfirmationEvidence incoming) {
        if (!committed.tenantId().equals(incoming.tenantId())
                || !committed.operationId().equals(incoming.operationId())
                || !committed.commandId().equals(incoming.commandId())
                || committed.commandType() != incoming.commandType()
                || !committed.expectedTargetIdentity().equals(incoming.expectedTargetIdentity())) {
            throw new IllegalArgumentException("Confirmation binding differs from committed evidence");
        }
        if (!committed.remoteIdentity().equals(incoming.remoteIdentity())) {
            throw new IllegalArgumentException(
                    "Confirmation remote identity differs from committed evidence");
        }
        if (committed.remoteState() != incoming.remoteState()) {
            throw new IllegalArgumentException(
                    "Confirmation remote state differs from committed evidence");
        }
    }

    private static void requireCompatibleLegacyConfirmation(
            LegacyConfirmationEvidence legacy,
            CommandDispatchOutcome.ConfirmationEvidence incoming) {
        if (!EXISTING_REMOTE_TARGET.contains(incoming.commandType())
                || !legacy.expectedTargetIdentity().equals(incoming.remoteIdentity())) {
            throw new IllegalArgumentException(
                    "Live result identity cannot be proven equivalent to legacy DONE evidence");
        }
    }

    private Decision startDispatch(Decision committed, CommandDispatchOutcome outcome) {
        if (committed.automaticAttemptsInBudget() >= MAX_AUTOMATIC_ATTEMPTS) {
            throw new IllegalStateException("Automatic dispatch attempt budget is exhausted");
        }
        if (committed.totalDispatchAttempts() == Long.MAX_VALUE) {
            throw new IllegalStateException("Dispatch attempt lifetime counter is exhausted");
        }
        return require(committed,
                EnumSet.of(EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE),
                outcome, new Decision(EngineCommandStatus.DISPATCHING, now(), null,
                        null, null, committed.totalDispatchAttempts() + 1,
                        committed.automaticAttemptsInBudget() + 1,
                        committed.budgetEpoch(), false, null, null, null,
                        committed.processedActions()));
    }

    private Decision classifyHttp(CommandContext command, Decision committed,
                                  CommandDispatchOutcome outcome) {
        if (outcome.confirmationEvidence() != null) {
            return confirmed(committed, outcome.confirmationEvidence());
        }
        CommandDispatchOutcome.HttpResult http = outcome.httpResult();
        if (http.acceptance() == ACCEPTED) {
            return diagnostic(committed, EngineCommandStatus.AWAITING_CONFIRMATION,
                    "response.unconfirmed",
                    "Accepted response lacked matching confirmation evidence", null);
        }
        if (http.acceptance() == POSSIBLY_ACCEPTED) {
            return diagnostic(committed, EngineCommandStatus.AWAITING_CONFIRMATION,
                    "response.ambiguous", "Remote request may have been accepted", null);
        }

        int status = http.status();
        if (status == 409) {
            return diagnostic(committed, EngineCommandStatus.CONFLICT,
                    "http.409.conflict", "Remote engine reported a command conflict", null);
        }
        if (status == 404 && command.commandType() == EngineCommand.Type.CANCEL_PROCESS) {
            return diagnostic(committed, EngineCommandStatus.CONFLICT,
                    "target.not_found",
                    "Cancellation target was not found without terminal proof", null);
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return automaticRetry(command, committed, http.retryAfter(),
                    "http." + status + ".not_accepted",
                    "Remote endpoint proved the request was not accepted", null);
        }
        return diagnostic(committed, EngineCommandStatus.FAILED,
                "http." + status + ".rejected",
                "Remote endpoint definitively rejected the request", null);
    }

    private Decision classifyReconciliation(CommandContext command, Decision committed,
                                            CommandDispatchOutcome outcome) {
        if (outcome.reviewEvidence().finding() == DEFINITIVE_ABSENCE) {
            return automaticRetry(command, committed, null,
                    "reconcile.absent", "Reconciliation proved the remote effect is absent",
                    outcome.reviewEvidence());
        }
        if (outcome.reviewEvidence().finding() == INCONCLUSIVE) {
            return diagnostic(committed, EngineCommandStatus.MANUAL_REVIEW,
                    "reconcile.inconclusive",
                    "Reconciliation could not determine the remote outcome",
                    outcome.reviewEvidence());
        }
        throw new IllegalArgumentException(
                "Unsupported reconciliation finding " + outcome.reviewEvidence().finding());
    }

    private Decision reviewedOperatorRetry(Decision committed,
                                           CommandDispatchOutcome outcome) {
        CommandDispatchOutcome.OperatorAction action = outcome.operatorAction();
        boolean exhausted = committed.automaticAttemptsInBudget() >= MAX_AUTOMATIC_ATTEMPTS;
        if (exhausted != action.overrideAutomaticAttemptCap()) {
            throw new IllegalStateException(exhausted
                    ? "Retry beyond the automatic attempt cap requires an audited override"
                    : "Attempt-cap override is not valid before the automatic budget is exhausted");
        }
        int budgetAttempts = committed.automaticAttemptsInBudget();
        long epoch = committed.budgetEpoch();
        if (exhausted) {
            if (epoch == Long.MAX_VALUE) {
                throw new IllegalStateException("Automatic attempt budget epoch is exhausted");
            }
            budgetAttempts = 0;
            epoch++;
        }
        return new Decision(EngineCommandStatus.RETRYABLE, now(), clamp(action.performedAt()),
                "review.retry", "Reviewed evidence permits another dispatch attempt",
                committed.totalDispatchAttempts(), budgetAttempts, epoch, exhausted,
                null, outcome.reviewEvidence(), action,
                appendProcessedAction(committed, outcome));
    }

    private Decision automaticRetry(CommandContext command, Decision committed,
                                    Duration retryAfter, String errorCode, String summary,
                                    CommandDispatchOutcome.ReviewEvidence evidence) {
        if (committed.automaticAttemptsInBudget() >= MAX_AUTOMATIC_ATTEMPTS) {
            return diagnostic(committed, EngineCommandStatus.FAILED,
                    "attempts.exhausted",
                    "Remote command exhausted automatic dispatch attempts", evidence);
        }
        Duration base = BACKOFF.get(Math.min(
                Math.max(committed.automaticAttemptsInBudget() - 1, 0), BACKOFF.size() - 1));
        long basisPoints = 8_000L + Math.floorMod(
                31L * command.commandId().hashCode()
                        + committed.automaticAttemptsInBudget(), 4_001L);
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
        return new Decision(EngineCommandStatus.RETRYABLE, now(),
                saturatingAdd(clock.instant(), delay), safeCode(errorCode), safeSummary(summary),
                committed.totalDispatchAttempts(), committed.automaticAttemptsInBudget(),
                committed.budgetEpoch(), false, null, evidence, null,
                committed.processedActions());
    }

    private static void validateCommittedState(CommandContext command, Decision committed) {
        if (committed.terminalConfirmation() != null) {
            validateConfirmationFields(command, committed.terminalConfirmation());
        }
        if (committed.legacyConfirmation() != null) {
            validateLegacyConfirmation(command, committed.legacyConfirmation());
        }
        if (committed.decisionEvidence() != null) {
            validateReviewFields(command, committed.decisionEvidence());
        }
        if (committed.appliedOperatorAction() != null) {
            validateOperator(command, committed.appliedOperatorAction());
        }
        for (ProcessedAction processed : committed.processedActions()) {
            validateOperator(command, processed.action());
            if (processed.reviewEvidence() != null) {
                validateReviewFields(command, processed.reviewEvidence());
            }
        }
    }

    private static void validateLegacyConfirmation(
            CommandContext command, LegacyConfirmationEvidence evidence) {
        same(command.tenantId(), evidence.tenantId(), "tenant");
        same(command.operationId(), evidence.operationId(), "operation");
        same(command.commandId(), evidence.commandId(), "command");
        if (command.commandType() != evidence.commandType()) {
            throw new IllegalArgumentException("Legacy confirmation command type mismatch");
        }
        same(command.expectedTargetIdentity(), evidence.expectedTargetIdentity(), "target");
        if (evidence.oldStatus() != LegacyCommandStatus.DONE) {
            throw new IllegalArgumentException("Legacy confirmation must retain DONE status");
        }
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
        validateConfirmationFields(command, evidence);
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

    private static void validateConfirmationFields(
            CommandContext command, CommandDispatchOutcome.ConfirmationEvidence evidence) {
        same(command.tenantId(), evidence.tenantId(), "tenant");
        same(command.operationId(), evidence.operationId(), "operation");
        same(command.commandId(), evidence.commandId(), "command");
        if (command.commandType() != evidence.commandType()) {
            throw new IllegalArgumentException("Confirmation command type mismatch");
        }
        same(command.expectedTargetIdentity(), evidence.expectedTargetIdentity(), "target");
        if (!terminalStates(command.commandType()).contains(evidence.remoteState())) {
            throw new IllegalArgumentException("Confirmation remote state mismatch");
        }
        if (EXISTING_REMOTE_TARGET.contains(command.commandType())
                && !command.expectedTargetIdentity().equals(evidence.remoteIdentity())) {
            throw new IllegalArgumentException("Confirmation remote identity mismatch");
        }
    }

    private void validateReview(CommandContext command, CommandDispatchOutcome outcome) {
        validateReviewFields(command, outcome.reviewEvidence());
        CommandDispatchOutcome.ReviewSource expectedSource =
                outcome.kind() == CommandDispatchOutcome.Kind.RECONCILIATION_RESULT
                        ? CommandDispatchOutcome.ReviewSource.RECONCILIATION
                        : CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
        if (outcome.reviewEvidence().source() != expectedSource) {
            throw new IllegalArgumentException("Review source mismatch");
        }
        if ((outcome.kind() == CommandDispatchOutcome.Kind.RETRY_AFTER_REVIEWED_ABSENCE
                || outcome.kind() == CommandDispatchOutcome.Kind.CANCEL_AFTER_REVIEWED_ABSENCE)
                && outcome.reviewEvidence().finding() != DEFINITIVE_ABSENCE) {
            throw new IllegalArgumentException(
                    "Operator action requires definitive absence evidence");
        }
    }

    private static void validateReviewFields(
            CommandContext command, CommandDispatchOutcome.ReviewEvidence evidence) {
        same(command.tenantId(), evidence.tenantId(), "tenant");
        same(command.operationId(), evidence.operationId(), "operation");
        same(command.commandId(), evidence.commandId(), "command");
        if (command.commandType() != evidence.commandType()) {
            throw new IllegalArgumentException("Review command type mismatch");
        }
        same(command.expectedTargetIdentity(), evidence.expectedTargetIdentity(), "target");
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
            Decision committed, CommandDispatchOutcome outcome, Decision decision) {
        if (committed.totalDispatchAttempts() == 0
                || committed.automaticAttemptsInBudget() == 0) {
            throw new IllegalArgumentException(
                    "Dispatching state must include the current dispatch attempt");
        }
        return require(committed, EnumSet.of(EngineCommandStatus.DISPATCHING), outcome, decision);
    }

    private static Decision require(Decision committed,
                                    EnumSet<EngineCommandStatus> allowed,
                                    CommandDispatchOutcome outcome,
                                    Decision decision) {
        if (!allowed.contains(committed.status())) {
            throw illegal(committed.status(), outcome);
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

    private static IllegalStateException illegal(EngineCommandStatus current,
                                                  CommandDispatchOutcome outcome) {
        return new IllegalStateException("Outcome " + outcome.kind()
                + " is not legal from command status " + current);
    }

    private Decision confirmed(
            Decision committed, CommandDispatchOutcome.ConfirmationEvidence evidence) {
        return new Decision(EngineCommandStatus.CONFIRMED, now(), null, null, null,
                committed.totalDispatchAttempts(), committed.automaticAttemptsInBudget(),
                committed.budgetEpoch(), false, evidence, null, null,
                committed.processedActions());
    }

    private Decision diagnostic(Decision committed, EngineCommandStatus status,
                                String errorCode, String summary,
                                CommandDispatchOutcome.ReviewEvidence evidence) {
        return new Decision(status, now(), null, safeCode(errorCode), safeSummary(summary),
                committed.totalDispatchAttempts(), committed.automaticAttemptsInBudget(),
                committed.budgetEpoch(), false, null, evidence, null,
                committed.processedActions());
    }

    private Decision operatorDecision(
            Decision committed, EngineCommandStatus status, String errorCode, String summary,
            CommandDispatchOutcome outcome, boolean resetBudget, int budgetAttempts,
            long budgetEpoch) {
        return new Decision(status, now(), null,
                errorCode == null ? null : safeCode(errorCode),
                summary == null ? null : safeSummary(summary),
                committed.totalDispatchAttempts(), budgetAttempts, budgetEpoch, resetBudget,
                null, outcome.reviewEvidence(), outcome.operatorAction(),
                appendProcessedAction(committed, outcome));
    }

    private OffsetDateTime now() {
        return clamp(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
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
        if (value.toInstant().compareTo(MAX_PERSISTABLE_TIMESTAMP.toInstant()) >= 0) {
            return MAX_PERSISTABLE_TIMESTAMP;
        }
        if (value.toInstant().compareTo(MIN_PERSISTABLE_TIMESTAMP.toInstant()) <= 0) {
            return MIN_PERSISTABLE_TIMESTAMP;
        }
        return OffsetDateTime.ofInstant(
                value.toInstant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    static OffsetDateTime canonicalPersistedTimestamp(OffsetDateTime value, String field) {
        Objects.requireNonNull(value, field);
        Instant instant = value.toInstant();
        if (instant.isBefore(MIN_PERSISTABLE_TIMESTAMP.toInstant())
                || instant.isAfter(MAX_PERSISTABLE_TIMESTAMP.toInstant())) {
            throw new IllegalArgumentException(field + " is outside the Oracle timestamp range");
        }
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
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

    public record CommandState(CommandContext command, Decision committedDecision) {
        public CommandState {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(committedDecision, "committedDecision");
            validateCommittedState(command, committedDecision);
        }
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

    /**
     * One normalized, persistence-ready operator action ledger row. Entries are retained for the
     * lifetime of the command and are never replaced or evicted by the policy.
     */
    public record ProcessedAction(
            long sequence,
            CommandDispatchOutcome.OperatorAction action,
            CommandDispatchOutcome.ReviewEvidence reviewEvidence) {
        public ProcessedAction {
            if (sequence < 1) {
                throw new IllegalArgumentException(
                        "Processed operator action sequence must be positive");
            }
            Objects.requireNonNull(action, "action");
            if (reviewEvidence != null) {
                sameProcessedBinding(action.tenantId(), reviewEvidence.tenantId(), "tenant");
                sameProcessedBinding(
                        action.operationId(), reviewEvidence.operationId(), "operation");
                sameProcessedBinding(action.commandId(), reviewEvidence.commandId(), "command");
                if (action.commandType() != reviewEvidence.commandType()) {
                    throw new IllegalArgumentException(
                            "Processed action review command type mismatch");
                }
                sameProcessedBinding(action.expectedTargetIdentity(),
                        reviewEvidence.expectedTargetIdentity(), "target");
            }
            switch (action.actionType()) {
                case MANUAL_REVIEW, RECONCILE -> {
                    if (reviewEvidence != null) {
                        throw new IllegalArgumentException(
                                "Manual review and reconcile history must not carry review evidence");
                    }
                }
                case RETRY_OVERRIDE -> requireDefinitiveOperatorAbsence(reviewEvidence);
                case CANCEL -> {
                    if (reviewEvidence != null) {
                        requireDefinitiveOperatorAbsence(reviewEvidence);
                    }
                }
            }
        }
    }

    public enum LegacyCommandStatus {
        DONE
    }

    /** Truthful provenance for a historical PoC row migrated from DONE to CONFIRMED. */
    public static final class LegacyConfirmationEvidence {
        private final String tenantId;
        private final String operationId;
        private final String commandId;
        private final EngineCommand.Type commandType;
        private final String expectedTargetIdentity;
        private final String legacyRowId;
        private final LegacyCommandStatus oldStatus;
        private final String migrationReference;

        private LegacyConfirmationEvidence(
                CommandContext command, String legacyRowId, String migrationReference) {
            this.tenantId = command.tenantId();
            this.operationId = command.operationId();
            this.commandId = command.commandId();
            this.commandType = command.commandType();
            this.expectedTargetIdentity = command.expectedTargetIdentity();
            this.legacyRowId = safeLegacyReference(legacyRowId, "legacyRowId");
            this.oldStatus = LegacyCommandStatus.DONE;
            this.migrationReference = safeLegacyReference(
                    migrationReference, "migrationReference");
        }

        public String tenantId() { return tenantId; }
        public String operationId() { return operationId; }
        public String commandId() { return commandId; }
        public EngineCommand.Type commandType() { return commandType; }
        public String expectedTargetIdentity() { return expectedTargetIdentity; }
        public String legacyRowId() { return legacyRowId; }
        public LegacyCommandStatus oldStatus() { return oldStatus; }
        public String migrationReference() { return migrationReference; }
        public CommandDispatchOutcome.ConfirmationSource source() {
            return CommandDispatchOutcome.ConfirmationSource.LEGACY_MIGRATION;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LegacyConfirmationEvidence that)) {
                return false;
            }
            return tenantId.equals(that.tenantId)
                    && operationId.equals(that.operationId)
                    && commandId.equals(that.commandId)
                    && commandType == that.commandType
                    && expectedTargetIdentity.equals(that.expectedTargetIdentity)
                    && legacyRowId.equals(that.legacyRowId)
                    && oldStatus == that.oldStatus
                    && migrationReference.equals(that.migrationReference);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, operationId, commandId, commandType,
                    expectedTargetIdentity, legacyRowId, oldStatus, migrationReference);
        }

        @Override
        public String toString() {
            return "LegacyConfirmationEvidence[tenantId=" + tenantId
                    + ", operationId=" + operationId
                    + ", commandId=" + commandId
                    + ", commandType=" + commandType
                    + ", expectedTargetIdentity=" + expectedTargetIdentity
                    + ", legacyRowId=" + legacyRowId
                    + ", oldStatus=" + oldStatus
                    + ", migrationReference=" + migrationReference + "]";
        }
    }

    private static String safeLegacyReference(String value, String field) {
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

    /** Closed vocabulary for diagnostics that may be persisted or exposed by operation APIs. */
    public enum DiagnosticCode {
        TRANSPORT_NOT_SENT("transport.not_sent", "Remote request sent zero bytes"),
        TRANSPORT_POSSIBLY_SENT("transport.possibly_sent", "Remote request may have been sent"),
        RESPONSE_MALFORMED("response.malformed",
                "Remote response was not valid confirmation evidence"),
        RESPONSE_DUPLICATE("response.duplicate",
                "Duplicate response lacked matching confirmation evidence"),
        DISPATCH_LEASE_EXPIRED("dispatch.lease_expired",
                "Dispatch lease expired with an unknown remote outcome"),
        RESPONSE_UNCONFIRMED("response.unconfirmed",
                "Accepted response lacked matching confirmation evidence"),
        RESPONSE_AMBIGUOUS("response.ambiguous", "Remote request may have been accepted"),
        HTTP_CONFLICT("http.409.conflict", "Remote engine reported a command conflict"),
        TARGET_NOT_FOUND("target.not_found",
                "Cancellation target was not found without terminal proof"),
        HTTP_NOT_ACCEPTED(null, "Remote endpoint proved the request was not accepted"),
        HTTP_REJECTED(null, "Remote endpoint definitively rejected the request"),
        RECONCILIATION_ABSENT("reconcile.absent",
                "Reconciliation proved the remote effect is absent"),
        RECONCILIATION_INCONCLUSIVE("reconcile.inconclusive",
                "Reconciliation could not determine the remote outcome"),
        ATTEMPTS_EXHAUSTED("attempts.exhausted",
                "Remote command exhausted automatic dispatch attempts"),
        REVIEW_REQUESTED("review.requested", "Operator requested manual review"),
        RECONCILIATION_REQUESTED("reconcile.requested",
                "Operator requested reconciliation"),
        REVIEW_RETRY("review.retry", "Reviewed evidence permits another dispatch attempt");

        private final String fixedCode;
        private final String summary;

        DiagnosticCode(String fixedCode, String summary) {
            this.fixedCode = fixedCode;
            this.summary = summary;
        }

        private boolean matches(String code) {
            if (fixedCode != null) {
                return fixedCode.equals(code);
            }
            if (this == HTTP_NOT_ACCEPTED) {
                return code.matches("http\\.(408|425|429|5[0-9]{2})\\.not_accepted");
            }
            if (!code.matches("http\\.[1-4][0-9]{2}\\.rejected")) {
                return false;
            }
            int status = Integer.parseInt(code.substring(5, 8));
            return status < 200 || status >= 300
                    && status != 408 && status != 409 && status != 425 && status != 429;
        }

        private static void requireExact(String code, String summary) {
            if (code == null || summary == null) {
                if (code != null || summary != null) {
                    throw new IllegalArgumentException(
                            "Persisted diagnostic code and summary must both be present");
                }
                return;
            }
            for (DiagnosticCode candidate : values()) {
                if (candidate.matches(code) && candidate.summary.equals(summary)) {
                    return;
                }
            }
            throw new IllegalArgumentException(
                    "Persisted diagnostic is not an exact member of the safe vocabulary");
        }
    }

    public record Decision(
            EngineCommandStatus status,
            OffsetDateTime decidedAt,
            OffsetDateTime nextAttemptAt,
            String errorCode,
            String safeSummary,
            long totalDispatchAttempts,
            int automaticAttemptsInBudget,
            long budgetEpoch,
            boolean automaticBudgetReset,
            CommandDispatchOutcome.ConfirmationEvidence terminalConfirmation,
            LegacyConfirmationEvidence legacyConfirmation,
            CommandDispatchOutcome.ReviewEvidence decisionEvidence,
            CommandDispatchOutcome.OperatorAction appliedOperatorAction,
            List<ProcessedAction> processedActions) {
        public Decision {
            Objects.requireNonNull(status, "status");
            decidedAt = canonicalPersistedTimestamp(decidedAt, "decidedAt");
            if (nextAttemptAt != null) {
                nextAttemptAt = canonicalPersistedTimestamp(nextAttemptAt, "nextAttemptAt");
            }
            processedActions = List.copyOf(
                    Objects.requireNonNull(processedActions, "processedActions"));
            if (totalDispatchAttempts < 0) {
                throw new IllegalArgumentException("totalDispatchAttempts must not be negative");
            }
            if (automaticAttemptsInBudget < 0
                    || automaticAttemptsInBudget > MAX_AUTOMATIC_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "automaticAttemptsInBudget is outside the automatic budget");
            }
            if (automaticAttemptsInBudget > totalDispatchAttempts) {
                throw new IllegalArgumentException(
                        "automaticAttemptsInBudget cannot exceed lifetime attempts");
            }
            if (status == EngineCommandStatus.DISPATCHING
                    && (totalDispatchAttempts == 0 || automaticAttemptsInBudget == 0)) {
                throw new IllegalArgumentException(
                        "Dispatching decisions must include their current dispatch attempt");
            }
            if (budgetEpoch < 0) {
                throw new IllegalArgumentException("budgetEpoch must not be negative");
            }
            if (status == EngineCommandStatus.RETRYABLE && nextAttemptAt == null) {
                throw new IllegalArgumentException(
                        "Retryable decisions require a next attempt time");
            }
            if (status != EngineCommandStatus.RETRYABLE && nextAttemptAt != null) {
                throw new IllegalArgumentException(
                        "Only retryable decisions may schedule an attempt");
            }
            if (automaticBudgetReset && status != EngineCommandStatus.RETRYABLE) {
                throw new IllegalArgumentException(
                        "Only retry decisions may reset the automatic budget");
            }
            if (terminalConfirmation != null && legacyConfirmation != null) {
                throw new IllegalArgumentException(
                        "Confirmed decisions cannot mix live and legacy provenance");
            }
            if ((status == EngineCommandStatus.CONFIRMED)
                    != (terminalConfirmation != null || legacyConfirmation != null)) {
                throw new IllegalArgumentException(
                        "Confirmed decisions must retain exactly one terminal provenance");
            }
            HashSet<String> actionIds = new HashSet<>();
            long expectedSequence = 1L;
            for (ProcessedAction processed : processedActions) {
                if (!actionIds.add(processed.action().actionId())) {
                    throw new IllegalArgumentException(
                            "Processed operator action history contains a duplicate action ID");
                }
                if (processed.sequence() != expectedSequence) {
                    throw new IllegalArgumentException(
                            "Processed operator action history sequence must be contiguous");
                }
                expectedSequence = Math.incrementExact(expectedSequence);
            }
            if (appliedOperatorAction != null) {
                if (processedActions.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Applied operator action must be retained in processed history");
                }
                ProcessedAction applied = new ProcessedAction(
                        processedActions.get(processedActions.size() - 1).sequence(),
                        appliedOperatorAction, decisionEvidence);
                if (!processedActions.get(processedActions.size() - 1).equals(applied)) {
                    throw new IllegalArgumentException(
                            "Applied operator action must be the last exact history entry");
                }
                validateAppliedAction(status, automaticBudgetReset,
                        automaticAttemptsInBudget, budgetEpoch, applied);
            } else if (automaticBudgetReset) {
                throw new IllegalArgumentException(
                        "An automatic budget reset requires its applied retry override action");
            }
            long cancellationCount = processedActions.stream()
                    .filter(processed -> processed.action().actionType()
                            == CommandDispatchOutcome.ActionType.CANCEL)
                    .count();
            if (cancellationCount > 1) {
                throw new IllegalArgumentException(
                        "Processed history may contain only one cancellation action");
            }
            if (cancellationCount == 1 && status != EngineCommandStatus.CANCELLED) {
                throw new IllegalArgumentException(
                        "A processed cancellation action requires terminal CANCELLED status");
            }
            if (status == EngineCommandStatus.CANCELLED
                    && (appliedOperatorAction == null
                    || appliedOperatorAction.actionType()
                    != CommandDispatchOutcome.ActionType.CANCEL)) {
                throw new IllegalArgumentException(
                        "Cancelled decisions must retain their applied CANCEL action");
            }
            long auditedBudgetResets = processedActions.stream()
                    .map(ProcessedAction::action)
                    .filter(action -> action.actionType()
                            == CommandDispatchOutcome.ActionType.RETRY_OVERRIDE)
                    .filter(CommandDispatchOutcome.OperatorAction::overrideAutomaticAttemptCap)
                    .count();
            if (budgetEpoch != auditedBudgetResets) {
                throw new IllegalArgumentException(
                        "Budget epoch must equal the audited retry override history count");
            }
            long expectedLifetimeAttempts;
            try {
                expectedLifetimeAttempts = Math.addExact(
                        Math.multiplyExact(budgetEpoch, (long) MAX_AUTOMATIC_ATTEMPTS),
                        automaticAttemptsInBudget);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Audited attempt history exceeds the lifetime counter range", ex);
            }
            if (totalDispatchAttempts != expectedLifetimeAttempts) {
                throw new IllegalArgumentException(
                        "Lifetime attempts must equal exhausted budgets plus current attempts");
            }
            validateDecisionEvidence(status, decisionEvidence, appliedOperatorAction);
            if (status == EngineCommandStatus.CONFIRMED
                    && (decisionEvidence != null || appliedOperatorAction != null)) {
                throw new IllegalArgumentException(
                        "Confirmed decisions may only carry terminal confirmation provenance");
            }
            if ((status == EngineCommandStatus.CONFIRMED
                    || status == EngineCommandStatus.CANCELLED)
                    && (errorCode != null || safeSummary != null)) {
                throw new IllegalArgumentException(
                        "Terminal confirmed or cancelled decisions cannot carry diagnostics");
            }
            if (status == EngineCommandStatus.MANUAL_REVIEW
                    && decisionEvidence == null && appliedOperatorAction == null) {
                throw new IllegalArgumentException(
                        "Manual review decisions require review or operator provenance");
            }
            DiagnosticCode.requireExact(errorCode, safeSummary);
        }

        /** Compatibility constructor for callers without historical actions yet. */
        public Decision(
                EngineCommandStatus status,
                OffsetDateTime decidedAt,
                OffsetDateTime nextAttemptAt,
                String errorCode,
                String safeSummary,
                long totalDispatchAttempts,
                int automaticAttemptsInBudget,
                long budgetEpoch,
                boolean automaticBudgetReset,
                CommandDispatchOutcome.ConfirmationEvidence terminalConfirmation,
                CommandDispatchOutcome.ReviewEvidence decisionEvidence,
                CommandDispatchOutcome.OperatorAction appliedOperatorAction,
                List<ProcessedAction> processedActions) {
            this(status, decidedAt, nextAttemptAt, errorCode, safeSummary,
                    totalDispatchAttempts, automaticAttemptsInBudget, budgetEpoch,
                    automaticBudgetReset, terminalConfirmation, null, decisionEvidence,
                    appliedOperatorAction, processedActions);
        }

        /** Compatibility constructor for callers without historical actions yet. */
        public Decision(
                EngineCommandStatus status,
                OffsetDateTime decidedAt,
                OffsetDateTime nextAttemptAt,
                String errorCode,
                String safeSummary,
                long totalDispatchAttempts,
                int automaticAttemptsInBudget,
                long budgetEpoch,
                boolean automaticBudgetReset,
                CommandDispatchOutcome.ConfirmationEvidence terminalConfirmation,
                CommandDispatchOutcome.ReviewEvidence decisionEvidence,
                CommandDispatchOutcome.OperatorAction appliedOperatorAction) {
            this(status, decidedAt, nextAttemptAt, errorCode, safeSummary,
                    totalDispatchAttempts, automaticAttemptsInBudget, budgetEpoch,
                    automaticBudgetReset, terminalConfirmation, null, decisionEvidence,
                    appliedOperatorAction, appliedOperatorAction == null
                            ? List.of()
                            : List.of(new ProcessedAction(
                                    1, appliedOperatorAction, decisionEvidence)));
        }
    }

    private static void validateAppliedAction(
            EngineCommandStatus status, boolean automaticBudgetReset,
            int automaticAttemptsInBudget, long budgetEpoch, ProcessedAction applied) {
        CommandDispatchOutcome.OperatorAction action = applied.action();
        EngineCommandStatus requiredStatus = switch (action.actionType()) {
            case MANUAL_REVIEW -> EngineCommandStatus.MANUAL_REVIEW;
            case RECONCILE -> EngineCommandStatus.AWAITING_CONFIRMATION;
            case RETRY_OVERRIDE -> EngineCommandStatus.RETRYABLE;
            case CANCEL -> EngineCommandStatus.CANCELLED;
        };
        if (status != requiredStatus) {
            throw new IllegalArgumentException(
                    "Decision status does not match its applied operator action type");
        }
        if (action.actionType() == CommandDispatchOutcome.ActionType.RETRY_OVERRIDE) {
            if (automaticBudgetReset != action.overrideAutomaticAttemptCap()) {
                throw new IllegalArgumentException(
                        "Retry override flag must match the automatic budget reset marker");
            }
            if (automaticBudgetReset && (automaticAttemptsInBudget != 0 || budgetEpoch == 0)) {
                throw new IllegalArgumentException(
                        "Budget reset requires zero automatic attempts and a nonzero epoch");
            }
        } else if (automaticBudgetReset || action.overrideAutomaticAttemptCap()) {
            throw new IllegalArgumentException(
                    "Only an applied retry override may reset the automatic budget");
        }
    }

    private static void validateDecisionEvidence(
            EngineCommandStatus status,
            CommandDispatchOutcome.ReviewEvidence evidence,
            CommandDispatchOutcome.OperatorAction action) {
        if (evidence == null) {
            return;
        }
        if (action != null) {
            if (action.actionType() != CommandDispatchOutcome.ActionType.RETRY_OVERRIDE
                    && action.actionType() != CommandDispatchOutcome.ActionType.CANCEL) {
                throw new IllegalArgumentException(
                        "Applied action type cannot carry decision review evidence");
            }
            requireDefinitiveOperatorAbsence(evidence);
            return;
        }
        if (evidence.source() != CommandDispatchOutcome.ReviewSource.RECONCILIATION) {
            throw new IllegalArgumentException(
                    "Unapplied decision evidence must come from reconciliation");
        }
        boolean allowed = evidence.finding() == DEFINITIVE_ABSENCE
                ? status == EngineCommandStatus.RETRYABLE || status == EngineCommandStatus.FAILED
                : evidence.finding() == INCONCLUSIVE
                && status == EngineCommandStatus.MANUAL_REVIEW;
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Decision status does not match its reconciliation evidence");
        }
    }

    private static void requireDefinitiveOperatorAbsence(
            CommandDispatchOutcome.ReviewEvidence evidence) {
        if (evidence == null || evidence.finding() != DEFINITIVE_ABSENCE
                || evidence.source() != CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW) {
            throw new IllegalArgumentException(
                    "Action history requires definitive operator-reviewed absence evidence");
        }
    }

    private static void sameProcessedBinding(
            String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Processed action review " + field + " mismatch");
        }
    }
}
