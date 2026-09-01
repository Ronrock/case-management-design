package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.MANUAL_REVIEW;
import static org.casemgmt.engine.CommandDispatchOutcome.ActionType.RECONCILE;

class EngineCommandNormalizedActionLedgerTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-28T12:00:00Z");
    private static final EngineCommandPolicy POLICY = new EngineCommandPolicy(Clock.fixed(
            Instant.parse("2026-08-28T13:00:00Z"), ZoneOffset.UTC));

    @Test
    void absentLookupEmitsOneAppendIntentWithoutLoadingHistoricalRows() {
        var summary = new EngineCommandPolicy.ActionLedgerSummary(
                Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 0, 0);
        var committed = awaiting(summary);
        var action = action(MANUAL_REVIEW, "action:large-ledger", 0);

        var result = POLICY.transition(
                state(committed), CommandDispatchOutcome.manualReviewRequested(action),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());

        assertThat(result.decision().status()).isEqualTo(EngineCommandStatus.MANUAL_REVIEW);
        assertThat(result.decision().actionLedgerSummary())
                .isEqualTo(new EngineCommandPolicy.ActionLedgerSummary(
                        Long.MAX_VALUE, Long.MAX_VALUE, 0, 0));
        assertThat(result.actionAppend()).isEqualTo(new EngineCommandPolicy.ActionAppend(
                summary,
                new EngineCommandPolicy.ProcessedAction(Long.MAX_VALUE, action, null),
                result.decision().actionLedgerSummary()));
    }

    @Test
    void exactHistoricalLookupReplaysCurrentDecisionAfterInterveningActionsAndReload() {
        var first = POLICY.transition(
                state(awaiting(EngineCommandPolicy.ActionLedgerSummary.empty())),
                CommandDispatchOutcome.manualReviewRequested(
                        action(MANUAL_REVIEW, "action:a", 0)),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());
        var second = POLICY.transition(
                state(first.decision()), CommandDispatchOutcome.reconciliationRequested(
                        action(RECONCILE, "action:b", 1)),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());
        var reloaded = rehydrate(second.decision());

        var replay = POLICY.transition(state(reloaded),
                CommandDispatchOutcome.manualReviewRequested(
                        action(MANUAL_REVIEW, "action:a", 0)),
                EngineCommandPolicy.AuthoritativeActionLookup.exact(first.actionAppend().action()));

        assertThat(replay.decision()).isEqualTo(reloaded);
        assertThat(replay.actionAppend()).isNull();
    }

    @Test
    void lookupConflictAndForgedExactMatchFailClosed() {
        var committed = awaiting(new EngineCommandPolicy.ActionLedgerSummary(1, 1, 0, 0));
        var incoming = action(MANUAL_REVIEW, "action:a", 0);

        assertThatThrownBy(() -> POLICY.transition(state(committed),
                CommandDispatchOutcome.manualReviewRequested(incoming),
                EngineCommandPolicy.AuthoritativeActionLookup.conflict()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflict");

        var repackaged = action(MANUAL_REVIEW, "action:a", 1);
        var stored = new EngineCommandPolicy.ProcessedAction(1, incoming, null);
        assertThatThrownBy(() -> POLICY.transition(state(committed),
                CommandDispatchOutcome.manualReviewRequested(repackaged),
                EngineCommandPolicy.AuthoritativeActionLookup.exact(stored)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repackaged");
    }

    @Test
    void missingAuthoritativeLookupCannotAppendAnOperatorAction() {
        assertThatThrownBy(() -> POLICY.transition(
                state(awaiting(EngineCommandPolicy.ActionLedgerSummary.empty())),
                CommandDispatchOutcome.manualReviewRequested(
                        action(MANUAL_REVIEW, "action:no-lookup", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative action lookup");
    }

    @Test
    void appendSequenceOverflowFailsBeforeProducingAnIntent() {
        var exhausted = new EngineCommandPolicy.ActionLedgerSummary(
                Long.MAX_VALUE, Long.MAX_VALUE, 0, 0);

        assertThatThrownBy(() -> POLICY.transition(state(awaiting(exhausted)),
                CommandDispatchOutcome.manualReviewRequested(
                        action(MANUAL_REVIEW, "action:overflow", 0)),
                EngineCommandPolicy.AuthoritativeActionLookup.absent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void insertRaceReloadsSummaryAndEitherAppendsAtNextSequenceOrReplaysExactly() {
        var initial = awaiting(EngineCommandPolicy.ActionLedgerSummary.empty());
        var actionA = action(MANUAL_REVIEW, "action:a", 0);
        var actionB = action(MANUAL_REVIEW, "action:b", 1);
        var proposedA = POLICY.transition(state(initial),
                CommandDispatchOutcome.manualReviewRequested(actionA),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());
        var committedB = POLICY.transition(state(initial),
                CommandDispatchOutcome.manualReviewRequested(actionB),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());

        assertThat(proposedA.actionAppend().action().sequence()).isEqualTo(1);
        assertThat(committedB.actionAppend().action().sequence()).isEqualTo(1);
        assertThat(proposedA.actionAppend().expectedSummary())
                .isEqualTo(committedB.actionAppend().expectedSummary());

        var reproposedA = POLICY.transition(state(committedB.decision()),
                CommandDispatchOutcome.manualReviewRequested(actionA),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());
        assertThat(reproposedA.actionAppend().action().sequence()).isEqualTo(2);
        assertThat(reproposedA.actionAppend().expectedSummary())
                .isEqualTo(committedB.decision().actionLedgerSummary());

        var replayAfterWinningInsert = POLICY.transition(state(reproposedA.decision()),
                CommandDispatchOutcome.manualReviewRequested(actionA),
                EngineCommandPolicy.AuthoritativeActionLookup.exact(
                        reproposedA.actionAppend().action()));
        assertThat(replayAfterWinningInsert.decision()).isEqualTo(reproposedA.decision());
        assertThat(replayAfterWinningInsert.actionAppend()).isNull();
    }

    @Test
    void repositoryVerifiedResetAggregateControlsBudgetEpochWithoutLoadingRows() {
        var summary = new EngineCommandPolicy.ActionLedgerSummary(9, 9, 1, 0);
        var decision = new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, AT, AT.plusMinutes(1),
                "review.retry", "Reviewed evidence permits another dispatch attempt",
                8, 2, 1, false, null, null, null, null, summary);
        assertThat(new EngineCommandPolicy.CommandState(command(), decision)
                .committedDecision()).isEqualTo(decision);

        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, AT, AT.plusMinutes(1),
                "review.retry", "Reviewed evidence permits another dispatch attempt",
                8, 2, 0, false, null, null, null, null, summary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
    }

    @Test
    void appliedHighWaterRowMustExactlyAdvanceTheRepositoryVerifiedPriorSummary() {
        var cancel = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ActionType.CANCEL, "action:cancel",
                "audit:cancel", AT, false);
        var applied = new EngineCommandPolicy.ProcessedAction(2, cancel, null);
        var prior = new EngineCommandPolicy.ActionLedgerSummary(1, 1, 0, 0);

        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.CANCELLED, AT, null, null, null,
                0, 0, 0, false, null, null, null, applied,
                prior,
                // Same count/high-water, but the CANCEL subtype total was forged away.
                new EngineCommandPolicy.ActionLedgerSummary(2, 2, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prior summary");
    }

    private static EngineCommandPolicy.Decision awaiting(
            EngineCommandPolicy.ActionLedgerSummary summary) {
        return new EngineCommandPolicy.Decision(
                EngineCommandStatus.AWAITING_CONFIRMATION, AT, null,
                "transport.possibly_sent", "Remote request may have been sent",
                0, 0, 0, false, null, null, null, null, summary);
    }

    private static EngineCommandPolicy.Decision rehydrate(EngineCommandPolicy.Decision decision) {
        return new EngineCommandPolicy.Decision(
                decision.status(), decision.decidedAt(), decision.nextAttemptAt(),
                decision.errorCode(), decision.safeSummary(), decision.totalDispatchAttempts(),
                decision.automaticAttemptsInBudget(), decision.budgetEpoch(),
                decision.automaticBudgetReset(), decision.terminalConfirmation(),
                decision.legacyConfirmation(), decision.decisionEvidence(),
                decision.appliedAction(), decision.appliedActionPriorSummary(),
                decision.actionLedgerSummary());
    }

    private static EngineCommandPolicy.CommandState state(
            EngineCommandPolicy.Decision decision) {
        return new EngineCommandPolicy.CommandState(command(), decision);
    }

    private static EngineCommandPolicy.CommandContext command() {
        return new EngineCommandPolicy.CommandContext(
                "tenant-a", "operation-a", "command-a",
                EngineCommand.Type.COMPLETE_TASK, "task-a");
    }

    private static CommandDispatchOutcome.OperatorAction action(
            CommandDispatchOutcome.ActionType type, String id, int seconds) {
        return new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", type, id, "audit:" + id.substring("action:".length()),
                AT.plusSeconds(seconds), false);
    }
}
