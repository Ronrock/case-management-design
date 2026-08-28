package org.casemgmt.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.CONFIRMED;
import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.DEFINITIVE_ABSENCE;
import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.INCONCLUSIVE;
import static org.casemgmt.engine.CommandDispatchOutcome.Evidence.NONE;

class EngineCommandPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final EngineCommandPolicy POLICY = new EngineCommandPolicy(CLOCK);
    private static final String COMMAND_ID = "operation-42";

    @ParameterizedTest(name = "{0} {1} dispatch -> {2}")
    @MethodSource("dispatchStartMatrix")
    void dispatchStartsOnlyFromDueStates(EngineCommandStatus current, EngineCommand.Type type,
                                         EngineCommandStatus expected) {
        assertTransitionOrRejection(current, type, 0,
                CommandDispatchOutcome.dispatchRequested(), expected);
    }

    static Stream<Arguments> dispatchStartMatrix() {
        return statusTypeMatrix((status, type) -> switch (status) {
            case PENDING, RETRYABLE -> EngineCommandStatus.DISPATCHING;
            default -> null;
        });
    }

    @ParameterizedTest(name = "pre-send {0}, attempt {1} -> {2}")
    @MethodSource("preSendFailureMatrix")
    void definitivePreSendFailureRetriesEveryCommandOnlyWithinAttemptBudget(
            EngineCommand.Type type, int attempts, EngineCommandStatus expected) {
        EngineCommandPolicy.Decision decision = POLICY.transition(COMMAND_ID,
                EngineCommandStatus.DISPATCHING, type, attempts,
                CommandDispatchOutcome.preSendFailure());

        assertThat(decision.status()).isEqualTo(expected);
        if (expected == EngineCommandStatus.RETRYABLE) {
            assertThat(decision.nextAttemptAt()).isAfter(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        } else {
            assertThat(decision.nextAttemptAt()).isNull();
        }
    }

    static Stream<Arguments> preSendFailureMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type -> Stream.of(
                Arguments.of(type, 0, EngineCommandStatus.RETRYABLE),
                Arguments.of(type, EngineCommandPolicy.MAX_ATTEMPTS - 1,
                        EngineCommandStatus.RETRYABLE),
                Arguments.of(type, EngineCommandPolicy.MAX_ATTEMPTS,
                        EngineCommandStatus.FAILED)));
    }

    @ParameterizedTest(name = "{0} HTTP {1}/{2} -> {3}")
    @MethodSource("httpMatrix")
    void httpResponsesRequireDefinitiveEvidenceBeforeConfirmation(
            EngineCommand.Type type, int statusCode,
            CommandDispatchOutcome.Evidence evidence, EngineCommandStatus expected) {
        assertThat(POLICY.transition(COMMAND_ID, EngineCommandStatus.DISPATCHING,
                type, 1, CommandDispatchOutcome.http(statusCode, evidence)).status())
                .isEqualTo(expected);
    }

    static Stream<Arguments> httpMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type -> Stream.of(
                Arguments.of(type, 200, CONFIRMED, EngineCommandStatus.CONFIRMED),
                Arguments.of(type, 200, NONE, EngineCommandStatus.AWAITING_CONFIRMATION),
                Arguments.of(type, 204, NONE, EngineCommandStatus.AWAITING_CONFIRMATION),
                Arguments.of(type, 400, NONE, EngineCommandStatus.FAILED),
                Arguments.of(type, 404, NONE, type == EngineCommand.Type.CANCEL_PROCESS
                        ? EngineCommandStatus.CONFIRMED : EngineCommandStatus.FAILED),
                Arguments.of(type, 409, NONE, EngineCommandStatus.CONFLICT),
                Arguments.of(type, 422, NONE, EngineCommandStatus.FAILED),
                Arguments.of(type, 429, NONE, EngineCommandStatus.FAILED),
                Arguments.of(type, 500, NONE, EngineCommandStatus.AWAITING_CONFIRMATION),
                Arguments.of(type, 503, NONE, EngineCommandStatus.AWAITING_CONFIRMATION)));
    }

    @ParameterizedTest(name = "{0} {1} -> awaiting confirmation")
    @MethodSource("ambiguousOutcomeMatrix")
    void sentOrPossiblySentRequestsAreNeverBlindlyRetried(
            EngineCommand.Type type, CommandDispatchOutcome outcome) {
        assertThat(POLICY.transition(COMMAND_ID, EngineCommandStatus.DISPATCHING,
                type, 1, outcome).status())
                .isEqualTo(EngineCommandStatus.AWAITING_CONFIRMATION);
    }

    static Stream<Arguments> ambiguousOutcomeMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type -> Stream.of(
                Arguments.of(type, CommandDispatchOutcome.timeoutAfterSend()),
                Arguments.of(type, CommandDispatchOutcome.readFailureAfterSend()),
                Arguments.of(type, CommandDispatchOutcome.malformedResponse()),
                Arguments.of(type, CommandDispatchOutcome.leaseExpired())));
    }

    @ParameterizedTest(name = "{0} duplicate evidence {1} -> {2}")
    @MethodSource("duplicateMatrix")
    void duplicateResponsesNeedMatchingEvidence(
            EngineCommand.Type type, CommandDispatchOutcome.Evidence evidence,
            EngineCommandStatus expected) {
        assertThat(POLICY.transition(COMMAND_ID, EngineCommandStatus.DISPATCHING,
                type, 1, CommandDispatchOutcome.duplicateResponse(evidence)).status())
                .isEqualTo(expected);
    }

    static Stream<Arguments> duplicateMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type -> Stream.of(
                Arguments.of(type, CONFIRMED, EngineCommandStatus.CONFIRMED),
                Arguments.of(type, NONE, EngineCommandStatus.CONFLICT)));
    }

    @ParameterizedTest(name = "observation confirms {0} {1} -> {2}")
    @MethodSource("observationMatrix")
    void matchingObservationConfirmsEveryNonTerminalCommandAndIsIdempotentWhenConfirmed(
            EngineCommandStatus current, EngineCommand.Type type, EngineCommandStatus expected) {
        assertTransitionOrRejection(current, type, 1,
                CommandDispatchOutcome.observationConfirmed(), expected);
    }

    static Stream<Arguments> observationMatrix() {
        EnumSet<EngineCommandStatus> confirmable = EnumSet.of(
                EngineCommandStatus.PENDING,
                EngineCommandStatus.DISPATCHING,
                EngineCommandStatus.RETRYABLE,
                EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT,
                EngineCommandStatus.MANUAL_REVIEW,
                EngineCommandStatus.CONFIRMED);
        return statusTypeMatrix((status, type) -> confirmable.contains(status)
                ? EngineCommandStatus.CONFIRMED : null);
    }

    @ParameterizedTest(name = "reconcile {0}/{1}, attempt {2} -> {3}")
    @MethodSource("reconciliationMatrix")
    void reconciliationConvertsEvidenceIntoConfirmationSafeRetryOrManualReview(
            EngineCommand.Type type, CommandDispatchOutcome.Evidence evidence,
            int attempts, EngineCommandStatus expected) {
        assertThat(POLICY.transition(COMMAND_ID,
                EngineCommandStatus.AWAITING_CONFIRMATION, type, attempts,
                CommandDispatchOutcome.reconciliation(evidence)).status())
                .isEqualTo(expected);
    }

    static Stream<Arguments> reconciliationMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type -> Stream.of(
                Arguments.of(type, CONFIRMED, 1, EngineCommandStatus.CONFIRMED),
                Arguments.of(type, DEFINITIVE_ABSENCE, 1, EngineCommandStatus.RETRYABLE),
                Arguments.of(type, DEFINITIVE_ABSENCE, EngineCommandPolicy.MAX_ATTEMPTS,
                        EngineCommandStatus.FAILED),
                Arguments.of(type, INCONCLUSIVE, 1, EngineCommandStatus.MANUAL_REVIEW)));
    }

    @ParameterizedTest(name = "manual action {1} from {0} {2} -> {3}")
    @MethodSource("manualActionMatrix")
    void operatorActionsCannotBypassEvidenceOrTerminalImmutability(
            EngineCommandStatus current, CommandDispatchOutcome outcome,
            EngineCommand.Type type, EngineCommandStatus expected) {
        assertTransitionOrRejection(current, type, 1, outcome, expected);
    }

    static Stream<Arguments> manualActionMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type -> Stream.of(
                Arguments.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                        CommandDispatchOutcome.manualReviewRequested(), type,
                        EngineCommandStatus.MANUAL_REVIEW),
                Arguments.of(EngineCommandStatus.CONFLICT,
                        CommandDispatchOutcome.manualReviewRequested(), type,
                        EngineCommandStatus.MANUAL_REVIEW),
                Arguments.of(EngineCommandStatus.MANUAL_REVIEW,
                        CommandDispatchOutcome.reconciliationRequested(), type,
                        EngineCommandStatus.AWAITING_CONFIRMATION),
                Arguments.of(EngineCommandStatus.CONFLICT,
                        CommandDispatchOutcome.reconciliationRequested(), type,
                        EngineCommandStatus.AWAITING_CONFIRMATION),
                Arguments.of(EngineCommandStatus.MANUAL_REVIEW,
                        CommandDispatchOutcome.retryAfterReviewedAbsence(), type,
                        EngineCommandStatus.RETRYABLE),
                Arguments.of(EngineCommandStatus.PENDING,
                        CommandDispatchOutcome.cancelUnsent(), type,
                        EngineCommandStatus.CANCELLED),
                Arguments.of(EngineCommandStatus.RETRYABLE,
                        CommandDispatchOutcome.cancelUnsent(), type,
                        EngineCommandStatus.CANCELLED),
                Arguments.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                        CommandDispatchOutcome.cancelAfterReviewedAbsence(), type,
                        EngineCommandStatus.CANCELLED),
                Arguments.of(EngineCommandStatus.DISPATCHING,
                        CommandDispatchOutcome.cancelUnsent(), type, null),
                Arguments.of(EngineCommandStatus.AWAITING_CONFIRMATION,
                        CommandDispatchOutcome.cancelUnsent(), type, null),
                Arguments.of(EngineCommandStatus.CONFIRMED,
                        CommandDispatchOutcome.retryAfterReviewedAbsence(), type, null),
                Arguments.of(EngineCommandStatus.FAILED,
                        CommandDispatchOutcome.reconciliationRequested(), type, null),
                Arguments.of(EngineCommandStatus.CANCELLED,
                        CommandDispatchOutcome.cancelUnsent(), type,
                        EngineCommandStatus.CANCELLED)));
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void resourceTargetedAndNonIdempotentCommandsBothRequireAbsenceEvidenceBeforeRetry(
            EngineCommand.Type type) {
        assertThat(POLICY.transition(COMMAND_ID, EngineCommandStatus.DISPATCHING,
                type, 1, CommandDispatchOutcome.timeoutAfterSend()).status())
                .isEqualTo(EngineCommandStatus.AWAITING_CONFIRMATION);
        assertThat(POLICY.transition(COMMAND_ID, EngineCommandStatus.AWAITING_CONFIRMATION,
                type, 1, CommandDispatchOutcome.reconciliation(DEFINITIVE_ABSENCE)).status())
                .isEqualTo(EngineCommandStatus.RETRYABLE);
    }

    @Test
    void classifiesEveryCurrentCommandTypeForFutureReconciliation() {
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.CREATE_TASK)).isTrue();
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.CLAIM_TASK)).isTrue();
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.COMPLETE_TASK)).isTrue();
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.CANCEL_PROCESS)).isTrue();
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.START_PROCESS)).isFalse();
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.CORRELATE_MESSAGE)).isFalse();
        assertThat(POLICY.isResourceTargeted(EngineCommand.Type.DEPLOY_ORCHESTRATION)).isFalse();
    }

    @Test
    void retryBackoffIsClockBasedDeterministicAndBounded() {
        EngineCommandPolicy.Decision first = POLICY.transition(COMMAND_ID,
                EngineCommandStatus.DISPATCHING, EngineCommand.Type.COMPLETE_TASK, 0,
                CommandDispatchOutcome.preSendFailure());
        EngineCommandPolicy.Decision repeated = POLICY.transition(COMMAND_ID,
                EngineCommandStatus.DISPATCHING, EngineCommand.Type.COMPLETE_TASK, 0,
                CommandDispatchOutcome.preSendFailure());
        EngineCommandPolicy.Decision later = POLICY.transition(COMMAND_ID,
                EngineCommandStatus.DISPATCHING, EngineCommand.Type.COMPLETE_TASK, 3,
                CommandDispatchOutcome.preSendFailure());
        EngineCommandPolicy.Decision last = POLICY.transition(COMMAND_ID,
                EngineCommandStatus.DISPATCHING, EngineCommand.Type.COMPLETE_TASK, 4,
                CommandDispatchOutcome.preSendFailure());

        assertThat(first.nextAttemptAt()).isEqualTo(repeated.nextAttemptAt());
        assertThat(Duration.between(NOW, first.nextAttemptAt().toInstant()))
                .isBetween(Duration.ofSeconds(48), Duration.ofSeconds(72));
        assertThat(Duration.between(NOW, later.nextAttemptAt().toInstant()))
                .isBetween(Duration.ofMinutes(96), Duration.ofMinutes(144));
        assertThat(Duration.between(NOW, last.nextAttemptAt().toInstant()))
                .isBetween(Duration.ofHours(8), Duration.ofHours(12));
    }

    @ParameterizedTest
    @MethodSource("diagnosticOutcomes")
    void diagnosticsAreStableSafeAndBounded(CommandDispatchOutcome outcome) {
        EngineCommandPolicy.Decision decision = POLICY.transition(COMMAND_ID,
                EngineCommandStatus.DISPATCHING, EngineCommand.Type.START_PROCESS, 1, outcome);

        assertThat(decision.errorCode()).matches("[a-z0-9._-]{1,64}");
        assertThat(decision.safeSummary()).doesNotContainIgnoringCase(
                "authorization", "bearer", "password", "secret", "token", "payload");
        assertThat(decision.safeSummary()).hasSizeLessThanOrEqualTo(
                EngineCommandPolicy.MAX_SAFE_SUMMARY_LENGTH);
    }

    static Stream<CommandDispatchOutcome> diagnosticOutcomes() {
        return Stream.of(
                CommandDispatchOutcome.preSendFailure(),
                CommandDispatchOutcome.http(400, NONE),
                CommandDispatchOutcome.http(503, NONE),
                CommandDispatchOutcome.timeoutAfterSend(),
                CommandDispatchOutcome.readFailureAfterSend(),
                CommandDispatchOutcome.malformedResponse(),
                CommandDispatchOutcome.duplicateResponse(NONE),
                CommandDispatchOutcome.leaseExpired());
    }

    @Test
    void invalidInputsFailClosed() {
        assertThatThrownBy(() -> POLICY.transition(" ", EngineCommandStatus.PENDING,
                EngineCommand.Type.START_PROCESS, 0,
                CommandDispatchOutcome.dispatchRequested()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> POLICY.transition(COMMAND_ID, EngineCommandStatus.PENDING,
                EngineCommand.Type.START_PROCESS, -1,
                CommandDispatchOutcome.dispatchRequested()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(99, NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(200, DEFINITIVE_ABSENCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(400, CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDispatchOutcome(
                CommandDispatchOutcome.Kind.OBSERVATION_CONFIRMED, NONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDispatchOutcome(
                CommandDispatchOutcome.Kind.RETRY_AFTER_REVIEWED_ABSENCE, INCONCLUSIVE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDispatchOutcome(
                CommandDispatchOutcome.Kind.CANCEL_UNSENT, CONFIRMED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "legality {0} × {1} × {2}")
    @MethodSource("completeLegalityMatrix")
    void everyStatusTypeAndOutcomeCombinationIsExplicitlyLegalOrIllegal(
            EngineCommandStatus current, EngineCommand.Type type,
            OutcomeRule rule) {
        if (rule.allowed().contains(current)) {
            assertThat(POLICY.transition(COMMAND_ID, current, type, 1, rule.outcome()))
                    .isNotNull();
        } else {
            assertThatThrownBy(() -> POLICY.transition(
                    COMMAND_ID, current, type, 1, rule.outcome()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    static Stream<Arguments> completeLegalityMatrix() {
        return Stream.of(outcomeRules()).flatMap(rule ->
                Stream.of(EngineCommandStatus.values()).flatMap(status ->
                        Stream.of(EngineCommand.Type.values()).map(type ->
                                Arguments.of(status, type, rule))));
    }

    private static OutcomeRule[] outcomeRules() {
        EnumSet<EngineCommandStatus> dispatching = EnumSet.of(EngineCommandStatus.DISPATCHING);
        EnumSet<EngineCommandStatus> confirmable = EnumSet.of(
                EngineCommandStatus.PENDING, EngineCommandStatus.DISPATCHING,
                EngineCommandStatus.RETRYABLE, EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW,
                EngineCommandStatus.CONFIRMED);
        EnumSet<EngineCommandStatus> reconcilable = EnumSet.of(
                EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT, EngineCommandStatus.MANUAL_REVIEW);
        return new OutcomeRule[] {
                new OutcomeRule(CommandDispatchOutcome.preSendFailure(), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(200, CONFIRMED), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(204, NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(400, NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(404, NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(409, NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(422, NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.http(503, NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.timeoutAfterSend(), dispatching),
                new OutcomeRule(CommandDispatchOutcome.readFailureAfterSend(), dispatching),
                new OutcomeRule(CommandDispatchOutcome.malformedResponse(), dispatching),
                new OutcomeRule(CommandDispatchOutcome.duplicateResponse(CONFIRMED), dispatching),
                new OutcomeRule(CommandDispatchOutcome.duplicateResponse(NONE), dispatching),
                new OutcomeRule(CommandDispatchOutcome.leaseExpired(), dispatching),
                new OutcomeRule(CommandDispatchOutcome.observationConfirmed(), confirmable),
                new OutcomeRule(CommandDispatchOutcome.reconciliation(CONFIRMED), reconcilable),
                new OutcomeRule(CommandDispatchOutcome.reconciliation(DEFINITIVE_ABSENCE), reconcilable),
                new OutcomeRule(CommandDispatchOutcome.reconciliation(INCONCLUSIVE), reconcilable),
                new OutcomeRule(CommandDispatchOutcome.manualReviewRequested(), EnumSet.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CONFLICT)),
                new OutcomeRule(CommandDispatchOutcome.reconciliationRequested(), reconcilable),
                new OutcomeRule(CommandDispatchOutcome.retryAfterReviewedAbsence(), reconcilable),
                new OutcomeRule(CommandDispatchOutcome.cancelUnsent(), EnumSet.of(
                        EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE,
                        EngineCommandStatus.CANCELLED)),
                new OutcomeRule(CommandDispatchOutcome.cancelAfterReviewedAbsence(), EnumSet.of(
                        EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE,
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CONFLICT,
                        EngineCommandStatus.MANUAL_REVIEW, EngineCommandStatus.CANCELLED))
        };
    }

    private static Stream<Arguments> statusTypeMatrix(ExpectedStatus expectedStatus) {
        return Stream.of(EngineCommandStatus.values()).flatMap(status ->
                Stream.of(EngineCommand.Type.values()).map(type ->
                        Arguments.of(status, type, expectedStatus.expected(status, type))));
    }

    private static void assertTransitionOrRejection(
            EngineCommandStatus current, EngineCommand.Type type, int attempts,
            CommandDispatchOutcome outcome, EngineCommandStatus expected) {
        if (expected == null) {
            assertThatThrownBy(() -> POLICY.transition(
                    COMMAND_ID, current, type, attempts, outcome))
                    .isInstanceOf(IllegalStateException.class);
            return;
        }
        assertThat(POLICY.transition(COMMAND_ID, current, type, attempts, outcome).status())
                .isEqualTo(expected);
    }

    @FunctionalInterface
    private interface ExpectedStatus {
        EngineCommandStatus expected(EngineCommandStatus status, EngineCommand.Type type);
    }

    private record OutcomeRule(CommandDispatchOutcome outcome,
                               EnumSet<EngineCommandStatus> allowed) { }
}
