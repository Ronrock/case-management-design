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
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.CANCEL;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.MANUAL_REVIEW;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.RECONCILE;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.RETRY_OVERRIDE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.DUPLICATE_RESPONSE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.OBSERVATION;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.RECONCILIATION;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.MID_WRITE_FAILURE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.PRE_SEND_ZERO_BYTES;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.READ_FAILURE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.TIMEOUT;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.UNKNOWN;

class EngineCommandPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final OffsetDateTime NOW_OFFSET = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final OffsetDateTime PRIOR = OffsetDateTime.parse("2026-08-28T11:00:00Z");
    private static final OffsetDateTime SECOND_RETRY = OffsetDateTime.parse(
            "2026-08-28T12:04:27.150Z");
    private static final EngineCommandPolicy POLICY = new EngineCommandPolicy(
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static final long TOTAL = 2;
    private static final int BUDGET_ATTEMPTS = 2;
    private static final long BUDGET_EPOCH = 0;

    @ParameterizedTest(name = "{0} {1} after {2}")
    @MethodSource("completeDecisionMatrix")
    void completeMatrixAssertsEveryPersistedDecisionField(
            EngineCommand.Type type, EngineCommandStatus status,
            Scenario scenario, EngineCommandPolicy.Decision expected) {
        EngineCommandPolicy.CommandState state = state(type, status);

        if (expected == null) {
            assertThatThrownBy(() -> POLICY.transition(state, scenario.outcome()))
                    .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
            return;
        }

        assertThat(POLICY.transition(state, scenario.outcome())).isEqualTo(expected);
    }

    static Stream<Arguments> completeDecisionMatrix() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type ->
                Stream.of(scenarios(type)).flatMap(scenario ->
                        Stream.of(EngineCommandStatus.values()).map(status -> Arguments.of(
                                type, status, scenario,
                                scenario.expectedByStatus().get(status)))));
    }

    private static Scenario[] scenarios(EngineCommand.Type type) {
        var dispatch = decision(EngineCommandStatus.DISPATCHING, NOW_OFFSET, null,
                null, null, 3, 3, 0, false, null, null, null);
        var retry = decision(EngineCommandStatus.RETRYABLE, NOW_OFFSET, SECOND_RETRY,
                "transport.not_sent", "Remote request sent zero bytes",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var possiblySent = decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                "transport.possibly_sent", "Remote request may have been sent",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var accepted = decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                "response.unconfirmed", "Accepted response lacked matching confirmation evidence",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var malformed = decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                "response.malformed", "Remote response was not valid confirmation evidence",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var duplicate = decision(EngineCommandStatus.CONFLICT, NOW_OFFSET, null,
                "response.duplicate", "Duplicate response lacked matching confirmation evidence",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var lease = decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                "dispatch.lease_expired", "Dispatch lease expired with an unknown remote outcome",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var httpEvidence = confirmation(type, HTTP_RESPONSE);
        var duplicateEvidence = confirmation(type, DUPLICATE_RESPONSE);
        var observationEvidence = confirmation(type, OBSERVATION);
        var reconciliationEvidence = confirmation(type, RECONCILIATION);
        var confirmedHttp = confirmed(httpEvidence);
        var confirmedDuplicate = confirmed(duplicateEvidence);
        var confirmedObservation = confirmed(observationEvidence);
        var confirmedReconciliation = confirmed(reconciliationEvidence);
        var committedConfirmation = state(type, EngineCommandStatus.CONFIRMED).committedDecision();
        var absence = review(type, DEFINITIVE_ABSENCE,
                CommandDispatchOutcome.ReviewSource.RECONCILIATION);
        var absentRetry = decision(EngineCommandStatus.RETRYABLE, NOW_OFFSET, SECOND_RETRY,
                "reconcile.absent", "Reconciliation proved the remote effect is absent",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, absence, null);
        var inconclusiveEvidence = review(type, INCONCLUSIVE,
                CommandDispatchOutcome.ReviewSource.RECONCILIATION);
        var inconclusive = decision(EngineCommandStatus.MANUAL_REVIEW, NOW_OFFSET, null,
                "reconcile.inconclusive", "Reconciliation could not determine the remote outcome",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                null, inconclusiveEvidence, null);
        var manualAction = operator(type, MANUAL_REVIEW, "action:manual", false, NOW_OFFSET);
        var manual = decision(EngineCommandStatus.MANUAL_REVIEW, NOW_OFFSET, null,
                "review.requested", "Operator requested manual review",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, manualAction);
        var reconcileAction = operator(type, RECONCILE, "action:reconcile", false, NOW_OFFSET);
        var reconciling = decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                "reconcile.requested", "Operator requested reconciliation",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, reconcileAction);
        var operatorAbsence = review(type, DEFINITIVE_ABSENCE, OPERATOR_REVIEW);
        var retryAction = operator(type, RETRY_OVERRIDE, "action:retry", false, NOW_OFFSET);
        var reviewedRetry = decision(EngineCommandStatus.RETRYABLE, NOW_OFFSET, NOW_OFFSET,
                "review.retry", "Reviewed evidence permits another dispatch attempt",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                null, operatorAbsence, retryAction);
        var cancelAction = operator(type, CANCEL, "action:cancel", false, NOW_OFFSET);
        var cancelled = decision(EngineCommandStatus.CANCELLED, NOW_OFFSET, null,
                null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                null, null, cancelAction);
        var reviewedCancelled = decision(EngineCommandStatus.CANCELLED, NOW_OFFSET, null,
                null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                null, operatorAbsence, cancelAction);

        return new Scenario[] {
                scenario("dispatch", CommandDispatchOutcome.dispatchRequested(), Map.of(
                        EngineCommandStatus.PENDING, dispatch,
                        EngineCommandStatus.RETRYABLE, dispatch)),
                scenario("zero-byte failure",
                        CommandDispatchOutcome.transportFailure(PRE_CONNECT_FAILURE), Map.of(
                        EngineCommandStatus.DISPATCHING, retry)),
                scenario("possibly-sent failure",
                        CommandDispatchOutcome.transportFailure(MID_WRITE_FAILURE), Map.of(
                        EngineCommandStatus.DISPATCHING, possiblySent)),
                scenario("accepted without proof",
                        CommandDispatchOutcome.http(202, ACCEPTED, null, null), Map.of(
                        EngineCommandStatus.DISPATCHING, accepted)),
                scenario("malformed response", CommandDispatchOutcome.malformedResponse(), Map.of(
                        EngineCommandStatus.DISPATCHING, malformed)),
                scenario("unproven duplicate", CommandDispatchOutcome.duplicateResponse(null), Map.of(
                        EngineCommandStatus.DISPATCHING, duplicate)),
                scenario("expired lease", CommandDispatchOutcome.leaseExpired(), Map.of(
                        EngineCommandStatus.DISPATCHING, lease)),
                scenario("HTTP confirmation", CommandDispatchOutcome.http(
                        200, ACCEPTED, null, httpEvidence), Map.of(
                        EngineCommandStatus.DISPATCHING, confirmedHttp,
                        EngineCommandStatus.CONFIRMED, committedConfirmation)),
                scenario("duplicate confirmation",
                        CommandDispatchOutcome.duplicateResponse(duplicateEvidence), Map.of(
                        EngineCommandStatus.DISPATCHING, confirmedDuplicate,
                        EngineCommandStatus.CONFIRMED, committedConfirmation)),
                scenario("observation confirmation",
                        CommandDispatchOutcome.observation(observationEvidence), Map.of(
                        EngineCommandStatus.PENDING, confirmedObservation,
                        EngineCommandStatus.DISPATCHING, confirmedObservation,
                        EngineCommandStatus.RETRYABLE, confirmedObservation,
                        EngineCommandStatus.AWAITING_CONFIRMATION, confirmedObservation,
                        EngineCommandStatus.CONFLICT, confirmedObservation,
                        EngineCommandStatus.MANUAL_REVIEW, confirmedObservation,
                        EngineCommandStatus.CONFIRMED, committedConfirmation)),
                scenario("reconciliation confirmation",
                        CommandDispatchOutcome.reconciliationConfirmed(reconciliationEvidence), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, confirmedReconciliation,
                        EngineCommandStatus.CONFLICT, confirmedReconciliation,
                        EngineCommandStatus.MANUAL_REVIEW, confirmedReconciliation,
                        EngineCommandStatus.CONFIRMED, committedConfirmation)),
                scenario("reconciliation absence",
                        CommandDispatchOutcome.reconciliation(absence), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, absentRetry,
                        EngineCommandStatus.CONFLICT, absentRetry,
                        EngineCommandStatus.MANUAL_REVIEW, absentRetry)),
                scenario("inconclusive reconciliation",
                        CommandDispatchOutcome.reconciliation(inconclusiveEvidence), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, inconclusive,
                        EngineCommandStatus.CONFLICT, inconclusive,
                        EngineCommandStatus.MANUAL_REVIEW, inconclusive)),
                scenario("manual review",
                        CommandDispatchOutcome.manualReviewRequested(manualAction), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, manual,
                        EngineCommandStatus.CONFLICT, manual,
                        EngineCommandStatus.MANUAL_REVIEW, manual)),
                scenario("reconciliation request",
                        CommandDispatchOutcome.reconciliationRequested(reconcileAction), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, reconciling,
                        EngineCommandStatus.CONFLICT, reconciling,
                        EngineCommandStatus.MANUAL_REVIEW, reconciling)),
                scenario("reviewed retry",
                        CommandDispatchOutcome.retryAfterReviewedAbsence(
                                operatorAbsence, retryAction), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, reviewedRetry,
                        EngineCommandStatus.CONFLICT, reviewedRetry,
                        EngineCommandStatus.MANUAL_REVIEW, reviewedRetry,
                        EngineCommandStatus.RETRYABLE, reviewedRetry)),
                scenario("cancel unsent",
                        CommandDispatchOutcome.cancelUnsent(cancelAction), Map.of(
                        EngineCommandStatus.PENDING, cancelled,
                        EngineCommandStatus.RETRYABLE, cancelled,
                        EngineCommandStatus.CANCELLED,
                                state(type, EngineCommandStatus.CANCELLED).committedDecision())),
                scenario("cancel after review",
                        CommandDispatchOutcome.cancelAfterReviewedAbsence(
                                operatorAbsence, cancelAction), Map.of(
                        EngineCommandStatus.PENDING, reviewedCancelled,
                        EngineCommandStatus.RETRYABLE, reviewedCancelled,
                        EngineCommandStatus.AWAITING_CONFIRMATION, reviewedCancelled,
                        EngineCommandStatus.CONFLICT, reviewedCancelled,
                        EngineCommandStatus.MANUAL_REVIEW, reviewedCancelled))
        };
    }

    @ParameterizedTest(name = "{0}: {1} then {2}")
    @MethodSource("confirmationSourcePermutations")
    void laterConfirmationSourcesPreserveTheFirstCommittedDecisionExactly(
            EngineCommand.Type type,
            CommandDispatchOutcome.ConfirmationSource firstSource,
            CommandDispatchOutcome.ConfirmationSource laterSource) {
        EngineCommandStatus initialStatus = firstSource == RECONCILIATION
                ? EngineCommandStatus.AWAITING_CONFIRMATION : EngineCommandStatus.DISPATCHING;
        var firstEvidence = confirmation(type, firstSource);
        var expectedFirst = confirmed(firstEvidence);
        var first = POLICY.transition(state(type, initialStatus),
                confirmationOutcome(type, firstSource));
        assertThat(first).isEqualTo(expectedFirst);

        EngineCommandPolicy laterPolicy = new EngineCommandPolicy(Clock.fixed(
                NOW.plus(Duration.ofDays(4)), ZoneOffset.UTC));
        var replay = laterPolicy.transition(new EngineCommandPolicy.CommandState(
                command(type), first), confirmationOutcome(type, laterSource));

        assertThat(replay).isEqualTo(expectedFirst);
        assertThat(replay.terminalConfirmation().source()).isEqualTo(firstSource);
        assertThat(replay.terminalConfirmation().evidenceReference())
                .isEqualTo("evidence:" + sourceName(firstSource));
        assertThat(replay.decidedAt()).isEqualTo(NOW_OFFSET);
    }

    static Stream<Arguments> confirmationSourcePermutations() {
        var liveSources = Stream.of(CommandDispatchOutcome.ConfirmationSource.values())
                .filter(source -> source
                        != CommandDispatchOutcome.ConfirmationSource.LEGACY_MIGRATION)
                .toList();
        return Stream.of(EngineCommand.Type.values()).flatMap(type ->
                liveSources.stream().flatMap(first ->
                        liveSources.stream().map(later ->
                                Arguments.of(type, first, later))));
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void terminalReplayRejectsAChangedRemoteResultIdentityOrState(EngineCommand.Type type) {
        var original = confirmation(type, HTTP_RESPONSE);
        var committed = confirmed(original);
        var state = new EngineCommandPolicy.CommandState(command(type), committed);
        var wrongIdentity = new CommandDispatchOutcome.ConfirmationEvidence(
                original.tenantId(), original.operationId(), original.commandId(), type,
                original.expectedTargetIdentity(), "different-remote-result",
                original.remoteState(), OBSERVATION, "evidence:changed-identity");
        var wrongState = new CommandDispatchOutcome.ConfirmationEvidence(
                original.tenantId(), original.operationId(), original.commandId(), type,
                original.expectedTargetIdentity(), original.remoteIdentity(),
                wrongState(type), OBSERVATION, "evidence:changed-state");

        assertThatThrownBy(() -> POLICY.transition(
                state, CommandDispatchOutcome.observation(wrongIdentity)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote identity");
        assertThatThrownBy(() -> POLICY.transition(
                state, CommandDispatchOutcome.observation(wrongState)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @ParameterizedTest
    @MethodSource("operatorReplayCases")
    void onlyTheExactCommittedOperatorActionAndEvidenceCanReplay(
            EngineCommandPolicy.CommandState initial,
            CommandDispatchOutcome outcome,
            EngineCommandPolicy.Decision expected) {
        var first = POLICY.transition(initial, outcome);
        assertThat(first).isEqualTo(expected);

        EngineCommandPolicy laterPolicy = new EngineCommandPolicy(Clock.fixed(
                NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC));
        assertThat(laterPolicy.transition(new EngineCommandPolicy.CommandState(
                initial.command(), first), outcome)).isEqualTo(expected);
    }

    static Stream<Arguments> operatorReplayCases() {
        EngineCommand.Type type = EngineCommand.Type.COMPLETE_TASK;
        var manual = operator(type, MANUAL_REVIEW, "action:manual-replay", false, NOW_OFFSET);
        var reconcile = operator(type, RECONCILE, "action:reconcile-replay", false, NOW_OFFSET);
        var cancel = operator(type, CANCEL, "action:cancel-replay", false, NOW_OFFSET);
        var retry = operator(type, RETRY_OVERRIDE, "action:retry-replay", false, NOW_OFFSET);
        var operatorAbsence = review(type, DEFINITIVE_ABSENCE, OPERATOR_REVIEW);
        return Stream.of(
                Arguments.of(state(type, EngineCommandStatus.AWAITING_CONFIRMATION),
                        CommandDispatchOutcome.manualReviewRequested(manual),
                        decision(EngineCommandStatus.MANUAL_REVIEW, NOW_OFFSET, null,
                                "review.requested", "Operator requested manual review",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, manual)),
                Arguments.of(state(type, EngineCommandStatus.MANUAL_REVIEW),
                        CommandDispatchOutcome.reconciliationRequested(reconcile),
                        decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                                "reconcile.requested", "Operator requested reconciliation",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, reconcile)),
                Arguments.of(state(type, EngineCommandStatus.AWAITING_CONFIRMATION),
                        CommandDispatchOutcome.retryAfterReviewedAbsence(operatorAbsence, retry),
                        decision(EngineCommandStatus.RETRYABLE, NOW_OFFSET, NOW_OFFSET,
                                "review.retry", "Reviewed evidence permits another dispatch attempt",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, operatorAbsence, retry)),
                Arguments.of(state(type, EngineCommandStatus.PENDING),
                        CommandDispatchOutcome.cancelUnsent(cancel),
                        decision(EngineCommandStatus.CANCELLED, NOW_OFFSET, null,
                                null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, cancel)),
                Arguments.of(state(type, EngineCommandStatus.AWAITING_CONFIRMATION),
                        CommandDispatchOutcome.cancelAfterReviewedAbsence(operatorAbsence, cancel),
                        decision(EngineCommandStatus.CANCELLED, NOW_OFFSET, null,
                                null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, operatorAbsence, cancel)));
    }

    @Test
    void repackagingAnAppliedActionIdOrUsingTheWrongActionTypeFailsClosed() {
        EngineCommand.Type type = EngineCommand.Type.COMPLETE_TASK;
        var action = operator(type, MANUAL_REVIEW, "action:stable", false, NOW_OFFSET);
        var first = POLICY.transition(state(type, EngineCommandStatus.AWAITING_CONFIRMATION),
                CommandDispatchOutcome.manualReviewRequested(action));
        var committed = new EngineCommandPolicy.CommandState(command(type), first);
        var repackaged = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                MANUAL_REVIEW, "action:stable", "audit:different", NOW_OFFSET, false);

        assertThatThrownBy(() -> POLICY.transition(committed,
                CommandDispatchOutcome.manualReviewRequested(repackaged)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repackaged");
        assertThatThrownBy(() -> CommandDispatchOutcome.reconciliationRequested(action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action type");
    }

    @Test
    void lifetimeAttemptsRemainMonotonicAcrossAnAuditedBudgetReset() {
        EngineCommand.Type type = EngineCommand.Type.START_PROCESS;
        var initial = decision(EngineCommandStatus.PENDING, PRIOR, null,
                null, null, 0, 0, 0, false, null, null, null);
        var firstDispatch = POLICY.transition(new EngineCommandPolicy.CommandState(
                command(type), initial), CommandDispatchOutcome.dispatchRequested());
        assertThat(firstDispatch).isEqualTo(decision(
                EngineCommandStatus.DISPATCHING, NOW_OFFSET, null, null, null,
                1, 1, 0, false, null, null, null));

        var firstFailure = POLICY.transition(new EngineCommandPolicy.CommandState(
                command(type), firstDispatch),
                CommandDispatchOutcome.transportFailure(PRE_SEND_ZERO_BYTES));
        assertThat(firstFailure).isEqualTo(decision(
                EngineCommandStatus.RETRYABLE, NOW_OFFSET,
                OffsetDateTime.parse("2026-08-28T12:00:53.424Z"),
                "transport.not_sent", "Remote request sent zero bytes",
                1, 1, 0, false, null, null, null));

        var exhaustedReview = decision(EngineCommandStatus.MANUAL_REVIEW, PRIOR, null,
                "reconcile.inconclusive",
                "Reconciliation could not determine the remote outcome", 6,
                EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS, 0,
                false, null,
                review(type, INCONCLUSIVE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION), null);
        var absence = review(type, DEFINITIVE_ABSENCE, OPERATOR_REVIEW);
        var override = operator(type, RETRY_OVERRIDE,
                "action:override", true, NOW_OFFSET);
        var overridden = POLICY.transition(new EngineCommandPolicy.CommandState(
                command(type), exhaustedReview),
                CommandDispatchOutcome.retryAfterReviewedAbsence(absence, override));
        var expectedOverride = decision(EngineCommandStatus.RETRYABLE, NOW_OFFSET, NOW_OFFSET,
                "review.retry", "Reviewed evidence permits another dispatch attempt",
                6, 0, 1, true, null, absence, override);
        assertThat(overridden).isEqualTo(expectedOverride);
        assertThat(POLICY.transition(new EngineCommandPolicy.CommandState(
                command(type), overridden),
                CommandDispatchOutcome.retryAfterReviewedAbsence(absence, override)))
                .isEqualTo(expectedOverride);

        var nextDispatch = POLICY.transition(new EngineCommandPolicy.CommandState(
                command(type), overridden), CommandDispatchOutcome.dispatchRequested());
        assertThat(nextDispatch).isEqualTo(new EngineCommandPolicy.Decision(
                EngineCommandStatus.DISPATCHING, NOW_OFFSET, null, null, null,
                7, 1, 1, false, null, null, null,
                java.util.List.of(new EngineCommandPolicy.ProcessedAction(
                        1, override, absence))));
    }

    @Test
    void lifetimeAndEpochCountersCannotBeForgedBeyondTheirAuditedHistory() {
        assertThatThrownBy(() -> decision(EngineCommandStatus.RETRYABLE, PRIOR,
                PRIOR.plusMinutes(1), "transport.not_sent",
                "Remote request sent zero bytes", Long.MAX_VALUE, 1, 0,
                false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lifetime attempts");
        assertThatThrownBy(() -> decision(EngineCommandStatus.RETRYABLE, PRIOR,
                PRIOR.plusMinutes(1), "transport.not_sent",
                "Remote request sent zero bytes", 1, 1, Long.MAX_VALUE,
                false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
    }

    @ParameterizedTest
    @MethodSource("httpDecisionTable")
    void httpAcceptanceAndRetryAfterProduceTheCompleteExpectedDecision(
            int status, CommandDispatchOutcome.Acceptance acceptance,
            EngineCommand.Type type, Duration retryAfter,
            EngineCommandPolicy.Decision expected) {
        assertThat(POLICY.transition(state(type, EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.http(status, acceptance, retryAfter, null)))
                .isEqualTo(expected);
    }

    static Stream<Arguments> httpDecisionTable() {
        return Stream.of(
                http(400, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK, null,
                        decision(EngineCommandStatus.FAILED, NOW_OFFSET, null,
                                "http.400.rejected", "Remote endpoint definitively rejected the request",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)),
                http(404, PROVEN_NOT_ACCEPTED, EngineCommand.Type.CANCEL_PROCESS, null,
                        decision(EngineCommandStatus.CONFLICT, NOW_OFFSET, null,
                                "target.not_found",
                                "Cancellation target was not found without terminal proof",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)),
                http(408, PROVEN_NOT_ACCEPTED, EngineCommand.Type.START_PROCESS,
                        Duration.ofMinutes(10), decision(EngineCommandStatus.RETRYABLE,
                                NOW_OFFSET, OffsetDateTime.parse("2026-08-28T12:10:00Z"),
                                "http.408.not_accepted",
                                "Remote endpoint proved the request was not accepted",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)),
                http(425, POSSIBLY_ACCEPTED, EngineCommand.Type.CORRELATE_MESSAGE, null,
                        decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                                "response.ambiguous", "Remote request may have been accepted",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)),
                http(429, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        Duration.ofMinutes(30), decision(EngineCommandStatus.RETRYABLE,
                                NOW_OFFSET, OffsetDateTime.parse("2026-08-28T12:30:00Z"),
                                "http.429.not_accepted",
                                "Remote endpoint proved the request was not accepted",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)),
                http(500, POSSIBLY_ACCEPTED, EngineCommand.Type.START_PROCESS, null,
                        decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                                "response.ambiguous", "Remote request may have been accepted",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)),
                http(503, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        Duration.ofHours(2), decision(EngineCommandStatus.RETRYABLE,
                                NOW_OFFSET, OffsetDateTime.parse("2026-08-28T14:00:00Z"),
                                "http.503.not_accepted",
                                "Remote endpoint proved the request was not accepted",
                                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                                null, null, null)));
    }

    @ParameterizedTest
    @MethodSource("transportDecisionTable")
    void everyTransportPhaseProducesTheCompleteExpectedDecision(
            CommandDispatchOutcome.TransportFailure failure,
            EngineCommandPolicy.Decision expected) {
        assertThat(POLICY.transition(state(
                EngineCommand.Type.START_PROCESS, EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.transportFailure(failure))).isEqualTo(expected);
    }

    static Stream<Arguments> transportDecisionTable() {
        var retry = decision(EngineCommandStatus.RETRYABLE, NOW_OFFSET, SECOND_RETRY,
                "transport.not_sent", "Remote request sent zero bytes",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        var awaiting = decision(EngineCommandStatus.AWAITING_CONFIRMATION, NOW_OFFSET, null,
                "transport.possibly_sent", "Remote request may have been sent",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false, null, null, null);
        return Stream.of(
                Arguments.of(PRE_CONNECT_FAILURE, retry),
                Arguments.of(PRE_SEND_ZERO_BYTES, retry),
                Arguments.of(MID_WRITE_FAILURE, awaiting),
                Arguments.of(TIMEOUT, awaiting),
                Arguments.of(READ_FAILURE, awaiting),
                Arguments.of(UNKNOWN, awaiting));
    }

    @Test
    void commandAndEvidenceBindingsRemainFailClosed() {
        EngineCommand.Type type = EngineCommand.Type.COMPLETE_TASK;
        var matching = confirmation(type, OBSERVATION);
        var wrongTenant = new CommandDispatchOutcome.ConfirmationEvidence(
                "other-tenant", matching.operationId(), matching.commandId(), type,
                matching.expectedTargetIdentity(), matching.remoteIdentity(),
                matching.remoteState(), OBSERVATION, "evidence:wrong-tenant");
        assertThatThrownBy(() -> POLICY.transition(
                state(type, EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.observation(wrongTenant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }

    @ParameterizedTest(name = "reject {0} mismatch before and after confirmation")
    @MethodSource("mismatchedConfirmationBindings")
    void everyConfirmationBindingRemainsFailClosedAfterTerminalCommit(
            String mismatch, CommandDispatchOutcome.ConfirmationEvidence evidence) {
        EngineCommand.Type type = EngineCommand.Type.COMPLETE_TASK;
        CommandDispatchOutcome outcome = CommandDispatchOutcome.observation(evidence);

        assertThatThrownBy(() -> POLICY.transition(
                state(type, EngineCommandStatus.DISPATCHING), outcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mismatch);
        assertThatThrownBy(() -> POLICY.transition(
                state(type, EngineCommandStatus.CONFIRMED), outcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mismatch);
    }

    static Stream<Arguments> mismatchedConfirmationBindings() {
        EngineCommand.Type type = EngineCommand.Type.COMPLETE_TASK;
        return Stream.of(
                mismatch("tenant", "other-tenant", "operation-a", "command-a", type,
                        "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                        OBSERVATION),
                mismatch("operation", "tenant-a", "other-operation", "command-a", type,
                        "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                        OBSERVATION),
                mismatch("command", "tenant-a", "operation-a", "other-command", type,
                        "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                        OBSERVATION),
                mismatch("type", "tenant-a", "operation-a", "command-a",
                        EngineCommand.Type.CLAIM_TASK, "task-a", "task-a",
                        CommandDispatchOutcome.RemoteState.TASK_CLAIMED, OBSERVATION),
                mismatch("target", "tenant-a", "operation-a", "command-a", type,
                        "other-task", "task-a",
                        CommandDispatchOutcome.RemoteState.TASK_COMPLETED, OBSERVATION),
                mismatch("remote identity", "tenant-a", "operation-a", "command-a", type,
                        "task-a", "other-task",
                        CommandDispatchOutcome.RemoteState.TASK_COMPLETED, OBSERVATION),
                mismatch("state", "tenant-a", "operation-a", "command-a", type,
                        "task-a", "task-a",
                        CommandDispatchOutcome.RemoteState.TASK_CLAIMED, OBSERVATION),
                mismatch("source", "tenant-a", "operation-a", "command-a", type,
                        "task-a", "task-a",
                        CommandDispatchOutcome.RemoteState.TASK_COMPLETED, HTTP_RESPONSE));
    }

    @Test
    void referencesAndImpossibleOutcomeShapesRemainFailClosed() {
        assertThatThrownBy(() -> new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                OBSERVATION, "Bearer secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CANCEL, "action-a", "password=hunter2", NOW_OFFSET, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(99, ACCEPTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(
                200, PROVEN_NOT_ACCEPTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(
                400, ACCEPTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retrySchedulingStillSaturatesAtThePersistableTimestamp() {
        EngineCommandPolicy farFuture = new EngineCommandPolicy(Clock.fixed(
                Instant.parse("9999-12-31T23:59:59.999998Z"), ZoneOffset.UTC));
        var retry = farFuture.transition(
                state(EngineCommand.Type.COMPLETE_TASK, EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.transportFailure(PRE_SEND_ZERO_BYTES));
        assertThat(retry.nextAttemptAt()).isEqualTo(
                EngineCommandPolicy.MAX_PERSISTABLE_TIMESTAMP);

        var bounded = POLICY.transition(
                state(EngineCommand.Type.COMPLETE_TASK, EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.http(429, PROVEN_NOT_ACCEPTED,
                        Duration.ofDays(365_000), null));
        assertThat(bounded).isEqualTo(decision(
                EngineCommandStatus.RETRYABLE, NOW_OFFSET,
                OffsetDateTime.parse("2026-09-27T12:00:00Z"),
                "http.429.not_accepted",
                "Remote endpoint proved the request was not accepted",
                TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH, false,
                null, null, null));
    }

    private static Arguments mismatch(
            String label, String tenant, String operation, String commandId,
            EngineCommand.Type type, String target, String remoteIdentity,
            CommandDispatchOutcome.RemoteState state,
            CommandDispatchOutcome.ConfirmationSource source) {
        return Arguments.of(label, new CommandDispatchOutcome.ConfirmationEvidence(
                tenant, operation, commandId, type, target, remoteIdentity, state, source,
                "evidence:mismatch"));
    }

    private static EngineCommandPolicy.CommandState state(
            EngineCommand.Type type, EngineCommandStatus status) {
        EngineCommandPolicy.Decision committed = switch (status) {
            case PENDING, DISPATCHING -> decision(status, PRIOR, null,
                    null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null, null, null);
            case RETRYABLE -> decision(status, PRIOR, PRIOR.plusMinutes(20),
                    "transport.not_sent", "Remote request sent zero bytes",
                    TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null, null, null);
            case AWAITING_CONFIRMATION -> decision(status, PRIOR, null,
                    "transport.possibly_sent", "Remote request may have been sent",
                    TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null, null, null);
            case CONFIRMED -> decision(status, PRIOR, null,
                    null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, confirmation(type, HTTP_RESPONSE), null, null);
            case FAILED -> decision(status, PRIOR, null,
                    "attempts.exhausted", "Remote command exhausted automatic dispatch attempts",
                    TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null, null, null);
            case CONFLICT -> decision(status, PRIOR, null,
                    "response.duplicate",
                    "Duplicate response lacked matching confirmation evidence",
                    TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null, null, null);
            case MANUAL_REVIEW -> decision(status, PRIOR, null,
                    "reconcile.inconclusive",
                    "Reconciliation could not determine the remote outcome",
                    TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null,
                    review(type, INCONCLUSIVE,
                            CommandDispatchOutcome.ReviewSource.RECONCILIATION), null);
            case CANCELLED -> decision(status, PRIOR, null,
                    null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                    false, null, null,
                    operator(type, CANCEL, "action:cancel", false, NOW_OFFSET));
        };
        return new EngineCommandPolicy.CommandState(command(type), committed);
    }

    private static EngineCommandPolicy.CommandContext command(EngineCommand.Type type) {
        return new EngineCommandPolicy.CommandContext(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type));
    }

    private static EngineCommandPolicy.Decision confirmed(
            CommandDispatchOutcome.ConfirmationEvidence evidence) {
        return decision(EngineCommandStatus.CONFIRMED, NOW_OFFSET, null,
                null, null, TOTAL, BUDGET_ATTEMPTS, BUDGET_EPOCH,
                false, evidence, null, null);
    }

    private static EngineCommandPolicy.Decision decision(
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
        return new EngineCommandPolicy.Decision(status, decidedAt, nextAttemptAt,
                errorCode, safeSummary, totalDispatchAttempts, automaticAttemptsInBudget,
                budgetEpoch, automaticBudgetReset, terminalConfirmation,
                decisionEvidence, appliedOperatorAction);
    }

    private static CommandDispatchOutcome confirmationOutcome(
            EngineCommand.Type type, CommandDispatchOutcome.ConfirmationSource source) {
        var evidence = confirmation(type, source);
        return switch (source) {
            case HTTP_RESPONSE -> CommandDispatchOutcome.http(200, ACCEPTED, null, evidence);
            case DUPLICATE_RESPONSE -> CommandDispatchOutcome.duplicateResponse(evidence);
            case OBSERVATION -> CommandDispatchOutcome.observation(evidence);
            case RECONCILIATION -> CommandDispatchOutcome.reconciliationConfirmed(evidence);
            case LEGACY_MIGRATION -> throw new IllegalArgumentException(
                    "Legacy migration is not a live confirmation outcome");
        };
    }

    private static CommandDispatchOutcome.ConfirmationEvidence confirmation(
            EngineCommand.Type type, CommandDispatchOutcome.ConfirmationSource source) {
        return new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                remoteIdentity(type), expectedState(type), source,
                "evidence:" + sourceName(source));
    }

    private static String sourceName(CommandDispatchOutcome.ConfirmationSource source) {
        return switch (source) {
            case HTTP_RESPONSE -> "http";
            case DUPLICATE_RESPONSE -> "duplicate";
            case OBSERVATION -> "observation";
            case RECONCILIATION -> "reconciliation";
            case LEGACY_MIGRATION -> "legacy-migration";
        };
    }

    private static CommandDispatchOutcome.ReviewEvidence review(
            EngineCommand.Type type, CommandDispatchOutcome.ReviewFinding finding,
            CommandDispatchOutcome.ReviewSource source) {
        return new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                finding, source, "review:44");
    }

    private static CommandDispatchOutcome.OperatorAction operator(
            EngineCommand.Type type, CommandDispatchOutcome.ActionType actionType,
            String actionId, boolean override, OffsetDateTime performedAt) {
        return new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                actionType, actionId, "audit:" + actionId.substring("action:".length()),
                performedAt, override);
    }

    private static String expectedTarget(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> "plan-item-a";
            case CLAIM_TASK, COMPLETE_TASK -> "task-a";
            case START_PROCESS -> "process-definition-a";
            case CANCEL_PROCESS -> "process-instance-a";
            case DEPLOY_ORCHESTRATION -> "release-a";
            case CORRELATE_MESSAGE -> "message-correlation-a";
        };
    }

    private static String remoteIdentity(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> "engine-task-a";
            case CLAIM_TASK, COMPLETE_TASK -> "task-a";
            case START_PROCESS -> "process-instance-a";
            case CANCEL_PROCESS -> "process-instance-a";
            case DEPLOY_ORCHESTRATION -> "deployment-a";
            case CORRELATE_MESSAGE -> "execution-a";
        };
    }

    private static CommandDispatchOutcome.RemoteState expectedState(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> CommandDispatchOutcome.RemoteState.TASK_CREATED;
            case CLAIM_TASK -> CommandDispatchOutcome.RemoteState.TASK_CLAIMED;
            case COMPLETE_TASK -> CommandDispatchOutcome.RemoteState.TASK_COMPLETED;
            case START_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
            case CANCEL_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED;
            case DEPLOY_ORCHESTRATION ->
                    CommandDispatchOutcome.RemoteState.ORCHESTRATION_DEPLOYED;
            case CORRELATE_MESSAGE -> CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED;
        };
    }

    private static CommandDispatchOutcome.RemoteState wrongState(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> CommandDispatchOutcome.RemoteState.TASK_COMPLETED;
            case CLAIM_TASK -> CommandDispatchOutcome.RemoteState.TASK_CREATED;
            case COMPLETE_TASK -> CommandDispatchOutcome.RemoteState.TASK_CLAIMED;
            case START_PROCESS, DEPLOY_ORCHESTRATION, CORRELATE_MESSAGE ->
                    CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED;
            case CANCEL_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
        };
    }

    private static Scenario scenario(
            String name, CommandDispatchOutcome outcome,
            Map<EngineCommandStatus, EngineCommandPolicy.Decision> expected) {
        return new Scenario(name, outcome, expected);
    }

    private static Arguments http(
            int status, CommandDispatchOutcome.Acceptance acceptance,
            EngineCommand.Type type, Duration retryAfter,
            EngineCommandPolicy.Decision expected) {
        return Arguments.of(status, acceptance, type, retryAfter, expected);
    }

    private record Scenario(
            String name,
            CommandDispatchOutcome outcome,
            Map<EngineCommandStatus, EngineCommandPolicy.Decision> expectedByStatus) {
        @Override public String toString() { return name; }
    }
}
