package org.casemgmt.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.CANCEL;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.MANUAL_REVIEW;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.RECONCILE;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.RETRY_OVERRIDE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.OBSERVATION;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewSource.RECONCILIATION;

class EngineCommandDurableStateTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final EngineCommandPolicy POLICY = new EngineCommandPolicy(
            Clock.fixed(NOW, ZoneOffset.UTC));
    private static final EngineCommand.Type TYPE = EngineCommand.Type.COMPLETE_TASK;

    @ParameterizedTest(name = "historical {0} replay after an intervening action")
    @MethodSource("historicalReplayScenarios")
    void historicalActionReplayReturnsTheCurrentCommittedDecision(
            CommandDispatchOutcome.ActionType actionType,
            EngineCommandPolicy.CommandState initial,
            CommandDispatchOutcome first,
            CommandDispatchOutcome intervening) {
        EngineCommandPolicy.Decision afterFirst = POLICY.transition(initial, first);
        EngineCommandPolicy.Decision current = POLICY.transition(
                state(afterFirst), intervening);

        EngineCommandPolicy.Decision replay = POLICY.transition(state(current), first);

        assertThat(replay).isEqualTo(current);
        assertThat(replay.processedActions()).hasSize(2);
        assertThat(replay.processedActions()).extracting(
                        processed -> processed.action().actionType())
                .containsExactly(actionType, intervening.operatorAction().actionType());
    }

    static Stream<Arguments> historicalReplayScenarios() {
        var absence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:absence");
        var manualA = action(MANUAL_REVIEW, "action:manual-a", false, 0);
        var reconcileA = action(RECONCILE, "action:reconcile-a", false, 0);
        var retryA = action(RETRY_OVERRIDE, "action:retry-a", false, 0);
        var cancelB = action(CANCEL, "action:cancel-b", false, 1);
        return Stream.of(
                Arguments.of(MANUAL_REVIEW, state(EngineCommandStatus.AWAITING_CONFIRMATION),
                        CommandDispatchOutcome.manualReviewRequested(manualA),
                        CommandDispatchOutcome.reconciliationRequested(
                                action(RECONCILE, "action:reconcile-b", false, 1))),
                Arguments.of(RECONCILE, state(EngineCommandStatus.AWAITING_CONFIRMATION),
                        CommandDispatchOutcome.reconciliationRequested(reconcileA),
                        CommandDispatchOutcome.manualReviewRequested(
                                action(MANUAL_REVIEW, "action:manual-b", false, 1))),
                Arguments.of(RETRY_OVERRIDE, state(EngineCommandStatus.MANUAL_REVIEW),
                        CommandDispatchOutcome.retryAfterReviewedAbsence(absence, retryA),
                        CommandDispatchOutcome.cancelUnsent(cancelB)));
    }

    @Test
    void cancellationReplaySurvivesPriorActionsAndPersistenceReloadButRemainsTerminal() {
        var manual = action(MANUAL_REVIEW, "action:manual-before-cancel", false, 0);
        var cancel = action(CANCEL, "action:cancel", false, 1);
        var absence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:cancel");
        var afterManual = POLICY.transition(state(EngineCommandStatus.AWAITING_CONFIRMATION),
                CommandDispatchOutcome.manualReviewRequested(manual));
        var cancelled = POLICY.transition(state(afterManual),
                CommandDispatchOutcome.cancelAfterReviewedAbsence(absence, cancel));
        var rehydrated = rehydrate(cancelled);

        assertThat(POLICY.transition(state(rehydrated),
                CommandDispatchOutcome.cancelAfterReviewedAbsence(
                        copy(absence), copy(cancel)))).isEqualTo(rehydrated);
        assertThatThrownBy(() -> POLICY.transition(state(rehydrated),
                CommandDispatchOutcome.reconciliationRequested(
                        action(RECONCILE, "action:after-cancel", false, 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @ParameterizedTest
    @EnumSource(CommandDispatchOutcome.ActionType.class)
    void historicalActionIdCannotBeRepackagedAfterInterveningStateChanges(
            CommandDispatchOutcome.ActionType actionType) {
        HistoricalFixture fixture = historicalFixture(actionType);
        EngineCommandPolicy.Decision current = fixture.current();
        CommandDispatchOutcome.OperatorAction original = fixture.replayed().operatorAction();
        var repackaged = new CommandDispatchOutcome.OperatorAction(
                original.tenantId(), original.operationId(), original.commandId(),
                original.commandType(), original.expectedTargetIdentity(), original.actionType(),
                original.actionId(), "audit:repackaged", original.performedAt(),
                original.overrideAutomaticAttemptCap());

        assertThatThrownBy(() -> POLICY.transition(state(current),
                outcomeFor(repackaged, fixture.replayed().reviewEvidence())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repackaged");
    }

    @Test
    void normalizedActionHistoryRemainsAppendablePastTheFormerArbitraryLimit() {
        List<EngineCommandPolicy.ProcessedAction> history = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            history.add(new EngineCommandPolicy.ProcessedAction(
                    i + 1L, action(MANUAL_REVIEW, "action:history-" + i, false, i), null));
        }
        var committed = decision(EngineCommandStatus.AWAITING_CONFIRMATION,
                null, null, null, history);

        var next = POLICY.transition(state(committed),
                CommandDispatchOutcome.manualReviewRequested(
                        action(MANUAL_REVIEW, "action:history-65", false, 65)));

        assertThat(next.processedActions()).hasSize(66);
        assertThat(next.processedActions().get(65).sequence()).isEqualTo(66L);
    }

    @ParameterizedTest(name = "{0} with {1} provenance is {2}")
    @MethodSource("statusProvenanceMatrix")
    void decisionConstructionAcceptsOnlyCoherentStatusProvenanceFamilies(
            EngineCommandStatus status, ProvenanceFamily family, boolean valid) {
        if (valid) {
            assertThatCode(() -> new EngineCommandPolicy.CommandState(
                    command(), decisionFor(status, family))).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(
                    command(), decisionFor(status, family)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<Arguments> statusProvenanceMatrix() {
        EnumSet<EngineCommandStatus> none = EnumSet.of(
                EngineCommandStatus.PENDING, EngineCommandStatus.DISPATCHING,
                EngineCommandStatus.RETRYABLE, EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.FAILED, EngineCommandStatus.CONFLICT);
        return Stream.of(EngineCommandStatus.values()).flatMap(status ->
                Stream.of(ProvenanceFamily.values()).map(family -> Arguments.of(
                        status, family, switch (family) {
                            case NONE -> none.contains(status);
                            case CONFIRMATION -> status == EngineCommandStatus.CONFIRMED;
                            case MANUAL_ACTION -> status == EngineCommandStatus.MANUAL_REVIEW;
                            case RECONCILE_ACTION ->
                                    status == EngineCommandStatus.AWAITING_CONFIRMATION;
                            case RETRY_ACTION -> status == EngineCommandStatus.RETRYABLE;
                            case CANCEL_ACTION -> status == EngineCommandStatus.CANCELLED;
                            case RECONCILIATION_ABSENCE -> status == EngineCommandStatus.RETRYABLE
                                    || status == EngineCommandStatus.FAILED;
                            case RECONCILIATION_INCONCLUSIVE ->
                                    status == EngineCommandStatus.MANUAL_REVIEW;
                        })));
    }

    @Test
    void budgetResetRequiresAuditedOverrideAbsenceZeroBudgetAndIncrementedEpoch() {
        var absence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:override");
        var override = action(RETRY_OVERRIDE, "action:override", true, 0);
        var processed = List.of(new EngineCommandPolicy.ProcessedAction(1, override, absence));

        assertThatCode(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.RETRYABLE, true, 6, 0, 1,
                null, absence, override, processed))).doesNotThrowAnyException();
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 0, 1,
                null, null, override,
                List.of(new EngineCommandPolicy.ProcessedAction(1, override, null))));
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 1, 1,
                null, absence, override, processed));
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 0, 0,
                null, absence, override, processed));
        var ordinary = action(RETRY_OVERRIDE, "action:ordinary", false, 0);
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 0, 1,
                null, absence, ordinary,
                List.of(new EngineCommandPolicy.ProcessedAction(1, ordinary, absence))));
    }

    @Test
    void budgetEpochAndAttemptCountersAreDerivedFromTheCompleteOverrideHistory() {
        var absence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:override-history");
        var first = action(RETRY_OVERRIDE, "action:override-one", true, 0);
        var second = action(RETRY_OVERRIDE, "action:override-two", true, 1);
        var history = List.of(
                new EngineCommandPolicy.ProcessedAction(1L, first, absence),
                new EngineCommandPolicy.ProcessedAction(2L, second, absence));

        assertThatCode(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.RETRYABLE, true, 12, 0, 2,
                null, absence, second, history))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.RETRYABLE, true, 12, 0, 1,
                null, absence, second, history)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.RETRYABLE, true, 11, 0, 2,
                null, absence, second, history)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.RETRYABLE, true, 13, 0, 2,
                null, absence, second, history)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void forgedRehydratedBindingsTerminalStatesHistoryAndCountersFailClosed() {
        var confirmed = decisionFor(EngineCommandStatus.CONFIRMED,
                ProvenanceFamily.CONFIRMATION);
        var wrongStateEvidence = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", "task-a",
                CommandDispatchOutcome.RemoteState.TASK_CLAIMED, HTTP_RESPONSE,
                "evidence:wrong-state");
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.CONFIRMED, false, 2, 0,
                wrongStateEvidence, null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");

        var wrongTenant = action(MANUAL_REVIEW, "action:wrong-tenant", false, 0,
                "other-tenant");
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), decision(
                EngineCommandStatus.MANUAL_REVIEW, null, null, wrongTenant,
                List.of(new EngineCommandPolicy.ProcessedAction(1, wrongTenant, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");

        var manual = action(MANUAL_REVIEW, "action:missing", false, 0);
        assertThatThrownBy(() -> decision(
                EngineCommandStatus.MANUAL_REVIEW, null, null, manual, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history");

        var duplicate = new EngineCommandPolicy.ProcessedAction(1, manual, null);
        assertThatThrownBy(() -> decision(EngineCommandStatus.MANUAL_REVIEW,
                null, null, manual, List.of(duplicate, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        var later = action(MANUAL_REVIEW, "action:gap", false, 1);
        assertThatThrownBy(() -> decision(EngineCommandStatus.MANUAL_REVIEW,
                null, null, later, List.of(
                        new EngineCommandPolicy.ProcessedAction(2, manual, null),
                        new EngineCommandPolicy.ProcessedAction(4, later, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");

        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.PENDING, AT, null, null, null,
                1, 2, 0, false, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifetime");

        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.DISPATCHING, AT, null, null, null,
                0, 0, 0, false, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dispatching");

        var firstCancel = action(CANCEL, "action:first-cancel", false, 0);
        var secondCancel = action(CANCEL, "action:second-cancel", false, 1);
        assertThatThrownBy(() -> decision(EngineCommandStatus.CANCELLED,
                null, null, secondCancel, List.of(
                        new EngineCommandPolicy.ProcessedAction(1, firstCancel, null),
                        new EngineCommandPolicy.ProcessedAction(2, secondCancel, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one cancellation");
    }

    @Test
    void terminalDiagnosticsAndInternallyMismatchedActionEvidenceFailClosed() {
        var confirmation = confirmation(HTTP_RESPONSE, "evidence:terminal");
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.CONFIRMED, AT, null, "forged.error",
                "Forged terminal diagnostic", 2, 2, 0, false,
                confirmation, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        var retry = action(RETRY_OVERRIDE, "action:cross-boundary", false, 0);
        var wrongReview = new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "different-operation", "command-a", TYPE, "task-a",
                DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:different-operation");
        assertThatThrownBy(() -> new EngineCommandPolicy.ProcessedAction(
                1, retry, wrongReview))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void persistedDiagnosticsAcceptOnlyTheExactTypedCodeAndDerivedSummary() {
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.PENDING, AT, null, "custom.code",
                "A caller supplied this text", 2, 2, 0, false,
                null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnostic");
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, AT, AT.plusMinutes(1), "transport.not_sent",
                "password=should-not-survive", 2, 2, 0, false,
                null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnostic");

        var persisted = new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, AT, AT.plusMinutes(1), "transport.not_sent",
                "Remote request sent zero bytes", 2, 2, 0, false,
                null, null, null, List.of());
        assertThat(persisted.errorCode()).isEqualTo("transport.not_sent");
        assertThat(persisted.safeSummary()).isEqualTo("Remote request sent zero bytes");
    }

    @Test
    void persistedTimestampsCanonicalizeToUtcOracleMicrosecondsBeforeReplay() {
        OffsetDateTime jdbcUnstable = OffsetDateTime.parse(
                "2026-08-28T14:00:00.123456789+02:00");
        OffsetDateTime canonical = OffsetDateTime.parse("2026-08-28T12:00:00.123456Z");
        var action = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", MANUAL_REVIEW,
                "action:canonical-time", "audit:canonical-time", jdbcUnstable, false);
        var decision = new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, jdbcUnstable, jdbcUnstable.plusMinutes(1),
                "transport.not_sent", "Remote request sent zero bytes",
                2, 2, 0, false, null, null, null, List.of());

        assertThat(action.performedAt()).isEqualTo(canonical);
        assertThat(decision.decidedAt()).isEqualTo(canonical);
        assertThat(decision.nextAttemptAt()).isEqualTo(canonical.plusMinutes(1));

        var first = POLICY.transition(state(EngineCommandStatus.AWAITING_CONFIRMATION),
                CommandDispatchOutcome.manualReviewRequested(action));
        var reconstructed = new CommandDispatchOutcome.OperatorAction(
                action.tenantId(), action.operationId(), action.commandId(), action.commandType(),
                action.expectedTargetIdentity(), action.actionType(), action.actionId(),
                action.auditReference(), canonical, action.overrideAutomaticAttemptCap());
        assertThat(POLICY.transition(state(first),
                CommandDispatchOutcome.manualReviewRequested(reconstructed))).isEqualTo(first);

        assertThatThrownBy(() -> new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", MANUAL_REVIEW,
                "action:before-oracle-range", "audit:before-oracle-range",
                OffsetDateTime.parse("0000-12-31T23:59:59Z"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Oracle timestamp range");
    }

    @Test
    void freshRecordRoundtripPreservesHistoricalActionAndConfirmationReplay() {
        var manual = action(MANUAL_REVIEW, "action:roundtrip", false, 0);
        var afterAction = POLICY.transition(state(EngineCommandStatus.AWAITING_CONFIRMATION),
                CommandDispatchOutcome.manualReviewRequested(manual));
        var rehydratedActionState = state(rehydrate(afterAction));
        assertThat(POLICY.transition(rehydratedActionState,
                CommandDispatchOutcome.manualReviewRequested(copy(manual))))
                .isEqualTo(rehydratedActionState.committedDecision());

        var confirmation = confirmation(HTTP_RESPONSE, "evidence:http");
        var confirmed = POLICY.transition(state(EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.http(200,
                        CommandDispatchOutcome.Acceptance.ACCEPTED, null, confirmation));
        var rehydratedConfirmation = rehydrate(confirmed);
        var observation = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", "task-a",
                CommandDispatchOutcome.RemoteState.TASK_COMPLETED, OBSERVATION,
                "evidence:observation");
        assertThat(POLICY.transition(state(rehydratedConfirmation),
                CommandDispatchOutcome.observation(observation)))
                .isEqualTo(rehydratedConfirmation);
    }

    private static HistoricalFixture historicalFixture(
            CommandDispatchOutcome.ActionType actionType) {
        var absence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:history");
        return switch (actionType) {
            case MANUAL_REVIEW -> twoActions(EngineCommandStatus.AWAITING_CONFIRMATION,
                    CommandDispatchOutcome.manualReviewRequested(
                            action(actionType, "action:target", false, 0)),
                    CommandDispatchOutcome.reconciliationRequested(
                            action(RECONCILE, "action:later", false, 1)));
            case RECONCILE -> twoActions(EngineCommandStatus.AWAITING_CONFIRMATION,
                    CommandDispatchOutcome.reconciliationRequested(
                            action(actionType, "action:target", false, 0)),
                    CommandDispatchOutcome.manualReviewRequested(
                            action(MANUAL_REVIEW, "action:later", false, 1)));
            case RETRY_OVERRIDE -> twoActions(EngineCommandStatus.MANUAL_REVIEW,
                    CommandDispatchOutcome.retryAfterReviewedAbsence(absence,
                            action(actionType, "action:target", false, 0)),
                    CommandDispatchOutcome.cancelUnsent(
                            action(CANCEL, "action:later", false, 1)));
            case CANCEL -> {
                var prior = POLICY.transition(state(EngineCommandStatus.AWAITING_CONFIRMATION),
                        CommandDispatchOutcome.manualReviewRequested(
                                action(MANUAL_REVIEW, "action:earlier", false, 0)));
                var target = CommandDispatchOutcome.cancelAfterReviewedAbsence(absence,
                        action(actionType, "action:target", false, 1));
                yield new HistoricalFixture(POLICY.transition(state(prior), target), target);
            }
        };
    }

    private static HistoricalFixture twoActions(
            EngineCommandStatus initial, CommandDispatchOutcome target,
            CommandDispatchOutcome later) {
        var afterTarget = POLICY.transition(state(initial), target);
        return new HistoricalFixture(POLICY.transition(state(afterTarget), later), target);
    }

    private static CommandDispatchOutcome outcomeFor(
            CommandDispatchOutcome.OperatorAction action,
            CommandDispatchOutcome.ReviewEvidence evidence) {
        return switch (action.actionType()) {
            case MANUAL_REVIEW -> CommandDispatchOutcome.manualReviewRequested(action);
            case RECONCILE -> CommandDispatchOutcome.reconciliationRequested(action);
            case RETRY_OVERRIDE ->
                    CommandDispatchOutcome.retryAfterReviewedAbsence(evidence, action);
            case CANCEL -> evidence == null
                    ? CommandDispatchOutcome.cancelUnsent(action)
                    : CommandDispatchOutcome.cancelAfterReviewedAbsence(evidence, action);
        };
    }

    private static EngineCommandPolicy.Decision decisionFor(
            EngineCommandStatus status, ProvenanceFamily family) {
        CommandDispatchOutcome.ConfirmationEvidence confirmation = null;
        CommandDispatchOutcome.ReviewEvidence evidence = null;
        CommandDispatchOutcome.OperatorAction action = null;
        switch (family) {
            case NONE -> { }
            case CONFIRMATION -> confirmation = confirmation(HTTP_RESPONSE, "evidence:http");
            case MANUAL_ACTION -> action = action(MANUAL_REVIEW, "action:manual", false, 0);
            case RECONCILE_ACTION -> action = action(RECONCILE, "action:reconcile", false, 0);
            case RETRY_ACTION -> {
                evidence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:retry");
                action = action(RETRY_OVERRIDE, "action:retry", false, 0);
            }
            case CANCEL_ACTION -> action = action(CANCEL, "action:cancel", false, 0);
            case RECONCILIATION_ABSENCE ->
                    evidence = review(DEFINITIVE_ABSENCE, RECONCILIATION, "review:absence");
            case RECONCILIATION_INCONCLUSIVE ->
                    evidence = review(INCONCLUSIVE, RECONCILIATION, "review:inconclusive");
        }
        List<EngineCommandPolicy.ProcessedAction> history = action == null
                ? List.of() : List.of(new EngineCommandPolicy.ProcessedAction(1, action, evidence));
        return decision(status, confirmation, evidence, action, history);
    }

    private static EngineCommandPolicy.Decision decision(
            EngineCommandStatus status,
            CommandDispatchOutcome.ConfirmationEvidence confirmation,
            CommandDispatchOutcome.ReviewEvidence evidence,
            CommandDispatchOutcome.OperatorAction action,
            List<EngineCommandPolicy.ProcessedAction> history) {
        return newDecision(status, false, 2, 0,
                confirmation, evidence, action, history);
    }

    private static EngineCommandPolicy.Decision newDecision(
            EngineCommandStatus status, boolean reset, int budget, long epoch,
            CommandDispatchOutcome.ConfirmationEvidence confirmation,
            CommandDispatchOutcome.ReviewEvidence evidence,
            CommandDispatchOutcome.OperatorAction action,
            List<EngineCommandPolicy.ProcessedAction> history) {
        return newDecision(status, reset, 2, budget, epoch,
                confirmation, evidence, action, history);
    }

    private static EngineCommandPolicy.Decision newDecision(
            EngineCommandStatus status, boolean reset, long total, int budget, long epoch,
            CommandDispatchOutcome.ConfirmationEvidence confirmation,
            CommandDispatchOutcome.ReviewEvidence evidence,
            CommandDispatchOutcome.OperatorAction action,
            List<EngineCommandPolicy.ProcessedAction> history) {
        String code = switch (status) {
            case PENDING, DISPATCHING, CONFIRMED, CANCELLED -> null;
            case RETRYABLE -> "transport.not_sent";
            case AWAITING_CONFIRMATION -> "transport.possibly_sent";
            case FAILED -> "attempts.exhausted";
            case CONFLICT -> "response.duplicate";
            case MANUAL_REVIEW -> "reconcile.inconclusive";
        };
        String summary = switch (status) {
            case PENDING, DISPATCHING, CONFIRMED, CANCELLED -> null;
            case RETRYABLE -> "Remote request sent zero bytes";
            case AWAITING_CONFIRMATION -> "Remote request may have been sent";
            case FAILED -> "Remote command exhausted automatic dispatch attempts";
            case CONFLICT -> "Duplicate response lacked matching confirmation evidence";
            case MANUAL_REVIEW -> "Reconciliation could not determine the remote outcome";
        };
        return new EngineCommandPolicy.Decision(status, AT,
                status == EngineCommandStatus.RETRYABLE ? AT.plusMinutes(1) : null,
                code, summary,
                total, budget, epoch, reset, confirmation, evidence, action, history);
    }

    private static void assertInvalidReset(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EngineCommandPolicy.CommandState state(EngineCommandStatus status) {
        return status == EngineCommandStatus.MANUAL_REVIEW
                ? state(decision(status, null,
                        review(INCONCLUSIVE, RECONCILIATION, "review:prior"),
                        null, List.of()))
                : state(decision(status, null, null, null, List.of()));
    }

    private static EngineCommandPolicy.CommandState state(EngineCommandPolicy.Decision decision) {
        return new EngineCommandPolicy.CommandState(command(), decision);
    }

    private static EngineCommandPolicy.CommandContext command() {
        return new EngineCommandPolicy.CommandContext(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a");
    }

    private static CommandDispatchOutcome.OperatorAction action(
            CommandDispatchOutcome.ActionType type, String id, boolean override, int minute) {
        return action(type, id, override, minute, "tenant-a");
    }

    private static CommandDispatchOutcome.OperatorAction action(
            CommandDispatchOutcome.ActionType type, String id, boolean override, int minute,
            String tenant) {
        return new CommandDispatchOutcome.OperatorAction(
                tenant, "operation-a", "command-a", TYPE, "task-a", type, id,
                "audit:" + id.substring("action:".length()), AT.plusMinutes(minute), override);
    }

    private static CommandDispatchOutcome.ReviewEvidence review(
            CommandDispatchOutcome.ReviewFinding finding,
            CommandDispatchOutcome.ReviewSource source, String reference) {
        return new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a",
                finding, source, reference);
    }

    private static CommandDispatchOutcome.ConfirmationEvidence confirmation(
            CommandDispatchOutcome.ConfirmationSource source, String reference) {
        return new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", "task-a",
                CommandDispatchOutcome.RemoteState.TASK_COMPLETED, source, reference);
    }

    private static CommandDispatchOutcome.OperatorAction copy(
            CommandDispatchOutcome.OperatorAction action) {
        return new CommandDispatchOutcome.OperatorAction(
                action.tenantId(), action.operationId(), action.commandId(), action.commandType(),
                action.expectedTargetIdentity(), action.actionType(), action.actionId(),
                action.auditReference(), action.performedAt(),
                action.overrideAutomaticAttemptCap());
    }

    private static CommandDispatchOutcome.ReviewEvidence copy(
            CommandDispatchOutcome.ReviewEvidence evidence) {
        return new CommandDispatchOutcome.ReviewEvidence(
                evidence.tenantId(), evidence.operationId(), evidence.commandId(),
                evidence.commandType(), evidence.expectedTargetIdentity(), evidence.finding(),
                evidence.source(), evidence.evidenceReference());
    }

    private static EngineCommandPolicy.Decision rehydrate(
            EngineCommandPolicy.Decision decision) {
        List<EngineCommandPolicy.ProcessedAction> history = decision.processedActions().stream()
                .map(processed -> new EngineCommandPolicy.ProcessedAction(
                        processed.sequence(), copy(processed.action()),
                        processed.reviewEvidence() == null
                                ? null : copy(processed.reviewEvidence())))
                .toList();
        return new EngineCommandPolicy.Decision(
                decision.status(), decision.decidedAt(), decision.nextAttemptAt(),
                decision.errorCode(), decision.safeSummary(), decision.totalDispatchAttempts(),
                decision.automaticAttemptsInBudget(), decision.budgetEpoch(),
                decision.automaticBudgetReset(),
                decision.terminalConfirmation() == null ? null
                        : new CommandDispatchOutcome.ConfirmationEvidence(
                                decision.terminalConfirmation().tenantId(),
                                decision.terminalConfirmation().operationId(),
                                decision.terminalConfirmation().commandId(),
                                decision.terminalConfirmation().commandType(),
                                decision.terminalConfirmation().expectedTargetIdentity(),
                                decision.terminalConfirmation().remoteIdentity(),
                                decision.terminalConfirmation().remoteState(),
                                decision.terminalConfirmation().source(),
                                decision.terminalConfirmation().evidenceReference()),
                decision.decisionEvidence() == null ? null : copy(decision.decisionEvidence()),
                decision.appliedOperatorAction() == null
                        ? null : copy(decision.appliedOperatorAction()), history);
    }

    private enum ProvenanceFamily {
        NONE,
        CONFIRMATION,
        MANUAL_ACTION,
        RECONCILE_ACTION,
        RETRY_ACTION,
        CANCEL_ACTION,
        RECONCILIATION_ABSENCE,
        RECONCILIATION_INCONCLUSIVE
    }

    private record HistoricalFixture(
            EngineCommandPolicy.Decision current,
            CommandDispatchOutcome replayed) { }
}
