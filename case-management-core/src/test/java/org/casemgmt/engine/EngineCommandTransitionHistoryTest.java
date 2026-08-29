package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineCommandTransitionHistoryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-08-29T08:00:00Z");
    private static final EngineCommandPolicy.CommandContext COMMAND =
            new EngineCommandPolicy.CommandContext("tenant-a", "operation-a", "command-a",
                    EngineCommand.Type.COMPLETE_TASK, "task-a");

    @Test
    void versionedOutcomeCodecRoundTripsEveryPersistenceShape() {
        var confirmation = confirmation(CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE);
        var review = review(CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW);
        var action = action(CommandDispatchOutcome.ActionType.CANCEL, false);
        List<CommandDispatchOutcome> outcomes = List.of(
                CommandDispatchOutcome.dispatchRequested(),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.PRE_SEND_ZERO_BYTES),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.MID_WRITE_FAILURE),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.TIMEOUT),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.READ_FAILURE),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.UNKNOWN),
                CommandDispatchOutcome.http(202,
                        CommandDispatchOutcome.Acceptance.ACCEPTED, Duration.ofSeconds(17), null),
                CommandDispatchOutcome.http(200,
                        CommandDispatchOutcome.Acceptance.ACCEPTED, null, confirmation),
                CommandDispatchOutcome.malformedResponse(),
                CommandDispatchOutcome.duplicateResponse(confirmation(
                        CommandDispatchOutcome.ConfirmationSource.DUPLICATE_RESPONSE)),
                CommandDispatchOutcome.leaseExpired(),
                CommandDispatchOutcome.observation(confirmation(
                        CommandDispatchOutcome.ConfirmationSource.OBSERVATION)),
                CommandDispatchOutcome.reconciliationConfirmed(confirmation(
                        CommandDispatchOutcome.ConfirmationSource.RECONCILIATION)),
                CommandDispatchOutcome.reconciliation(review(
                        CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION)),
                CommandDispatchOutcome.manualReviewRequested(action(
                        CommandDispatchOutcome.ActionType.MANUAL_REVIEW, false)),
                CommandDispatchOutcome.reconciliationRequested(action(
                        CommandDispatchOutcome.ActionType.RECONCILE, false)),
                CommandDispatchOutcome.retryAfterReviewedAbsence(review,
                        action(CommandDispatchOutcome.ActionType.RETRY_OVERRIDE, true)),
                CommandDispatchOutcome.cancelUnsent(action),
                CommandDispatchOutcome.cancelAfterReviewedAbsence(review, action));

        assertThat(outcomes).allSatisfy(outcome -> {
            String encoded = EngineCommandTransitionHistory.encodeOutcome(outcome);
            assertThat(encoded).doesNotContain("password", "authorization", "payload");
            assertThat(EngineCommandTransitionHistory.decodeOutcome(encoded)).isEqualTo(outcome);
        });
    }

    @Test
    void replaysPendingDispatchRetryAndCancelFromAContiguousDigestChain() {
        EngineCommandPolicy.Decision baseline = pending(T0);
        var dispatch = EngineCommandTransitionHistory.record(COMMAND, 1, baseline,
                CommandDispatchOutcome.dispatchRequested(), T0.plusSeconds(1));
        var retry = EngineCommandTransitionHistory.record(COMMAND, 2, dispatch.nextDecision(),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE),
                T0.plusSeconds(2));
        var cancel = EngineCommandTransitionHistory.record(COMMAND, 3, retry.nextDecision(),
                CommandDispatchOutcome.cancelUnsent(action(
                        CommandDispatchOutcome.ActionType.CANCEL, false)),
                T0.plusSeconds(3));

        assertThat(EngineCommandTransitionHistory.replay(
                COMMAND, baseline, List.of(dispatch.row(), retry.row(), cancel.row())))
                .isEqualTo(cancel.nextDecision())
                .extracting(EngineCommandPolicy.Decision::status)
                .isEqualTo(EngineCommandStatus.CANCELLED);

        var missingVersion = List.of(dispatch.row(), cancel.row());
        assertThatThrownBy(() -> EngineCommandTransitionHistory.replay(
                COMMAND, baseline, missingVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
        var forgedDigest = retry.row().withPreviousDecisionDigest("0".repeat(64));
        assertThatThrownBy(() -> EngineCommandTransitionHistory.replay(
                COMMAND, baseline, List.of(dispatch.row(), forgedDigest, cancel.row())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void replaysMigratedClaimedReconciliationAndCancellationWithoutHeuristics() {
        EngineCommandPolicy.Decision claimedBaseline = new EngineCommandPolicy.Decision(
                EngineCommandStatus.AWAITING_CONFIRMATION, T0, null, null, null,
                1, 1, 0, false, null, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty());
        var reconciliation = EngineCommandTransitionHistory.record(COMMAND, 1, claimedBaseline,
                CommandDispatchOutcome.reconciliation(review(
                        CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION)),
                T0.plusSeconds(1));
        var cancel = EngineCommandTransitionHistory.record(COMMAND, 2,
                reconciliation.nextDecision(), CommandDispatchOutcome.cancelUnsent(
                        action(CommandDispatchOutcome.ActionType.CANCEL, false)),
                T0.plusSeconds(2));

        assertThat(EngineCommandTransitionHistory.replay(COMMAND, claimedBaseline,
                List.of(reconciliation.row(), cancel.row()))).isEqualTo(cancel.nextDecision());
    }

    @Test
    void baselineCodecRoundTripsNativeAndTruthfulLegacyConfirmation() {
        EngineCommandPolicy.Decision nativeBaseline = pending(T0);
        EngineCommandPolicy.Decision legacyDone = LegacyDoneCommandMigration.migrate(
                new LegacyDoneCommandMigration.LegacyDoneRow(COMMAND, "command-a",
                        "ws4-task2", T0, 1));

        assertThat(EngineCommandTransitionHistory.decodeBaseline(COMMAND,
                EngineCommandTransitionHistory.encodeBaseline(nativeBaseline)))
                .isEqualTo(nativeBaseline);
        assertThat(EngineCommandTransitionHistory.decodeBaseline(COMMAND,
                EngineCommandTransitionHistory.encodeBaseline(legacyDone)))
                .isEqualTo(legacyDone);
    }

    @Test
    void outcomeCodecRejectsUnknownNonCanonicalFractionalAndOversizedEvidence() {
        String canonical = EngineCommandTransitionHistory.encodeOutcome(
                CommandDispatchOutcome.http(429,
                        CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED,
                        Duration.ofSeconds(30, 12), null));
        String unknown = canonical.substring(0, canonical.length() - 1) + ",\"unknown\":null}";
        String fractionalStatus = canonical.replace("\"status\":429", "\"status\":429.5");
        String invalidNanos = canonical.replace("\"retryAfterNanos\":12",
                "\"retryAfterNanos\":1000000000");

        assertThatThrownBy(() -> EngineCommandTransitionHistory.decodeOutcome(unknown))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fields");
        assertThatThrownBy(() -> EngineCommandTransitionHistory.decodeOutcome(" " + canonical))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");
        assertThatThrownBy(() -> EngineCommandTransitionHistory.decodeOutcome(fractionalStatus))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integral");
        assertThatThrownBy(() -> EngineCommandTransitionHistory.decodeOutcome(invalidNanos))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nanos");
        assertThatThrownBy(() -> EngineCommandTransitionHistory.decodeOutcome(
                canonical + " ".repeat(EngineCommandTransitionHistory.MAX_ENCODED_CHARS)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size");
    }

    @Test
    void replayRequiresEveryOperatorTransitionToMatchItsNormalizedActionRowExactly() {
        EngineCommandPolicy.Decision baseline = pending(T0);
        CommandDispatchOutcome.OperatorAction cancelAction = action(
                CommandDispatchOutcome.ActionType.CANCEL, false);
        CommandDispatchOutcome cancelOutcome = CommandDispatchOutcome.cancelUnsent(cancelAction);
        var cancel = EngineCommandTransitionHistory.record(COMMAND, 1, baseline,
                cancelOutcome, T0.plusSeconds(3));
        EngineCommandPolicy.ProcessedAction exact = new EngineCommandPolicy.ProcessedAction(
                1, cancelAction, null);
        EngineCommandPolicy.ProcessedAction tampered = new EngineCommandPolicy.ProcessedAction(
                1, new CommandDispatchOutcome.OperatorAction(
                        cancelAction.tenantId(), cancelAction.operationId(),
                        cancelAction.commandId(), cancelAction.commandType(),
                        cancelAction.expectedTargetIdentity(), cancelAction.actionType(),
                        cancelAction.actionId(), "different-audit", cancelAction.performedAt(),
                        cancelAction.overrideAutomaticAttemptCap()), null);

        assertThat(EngineCommandTransitionHistory.replay(COMMAND, baseline,
                List.of(cancel.row()), List.of(exact))).isEqualTo(cancel.nextDecision());
        assertThatThrownBy(() -> EngineCommandTransitionHistory.replay(COMMAND, baseline,
                List.of(cancel.row()), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("action");
        assertThatThrownBy(() -> EngineCommandTransitionHistory.replay(COMMAND, baseline,
                List.of(cancel.row()), List.of(tampered)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("action");
        EngineCommandPolicy.ProcessedAction extra = new EngineCommandPolicy.ProcessedAction(
                2, action(CommandDispatchOutcome.ActionType.CANCEL, false), null);
        assertThatThrownBy(() -> EngineCommandTransitionHistory.replay(COMMAND, baseline,
                List.of(cancel.row()), List.of(exact, extra)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unmatched");
    }

    private static EngineCommandPolicy.Decision pending(OffsetDateTime at) {
        return new EngineCommandPolicy.Decision(EngineCommandStatus.PENDING, at, null,
                null, null, 0, 0, 0, false, null, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty());
    }

    private static CommandDispatchOutcome.ConfirmationEvidence confirmation(
            CommandDispatchOutcome.ConfirmationSource source) {
        return new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                source, "evidence-a");
    }

    private static CommandDispatchOutcome.ReviewEvidence review(
            CommandDispatchOutcome.ReviewFinding finding,
            CommandDispatchOutcome.ReviewSource source) {
        return new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", finding, source, "review-a");
    }

    private static CommandDispatchOutcome.OperatorAction action(
            CommandDispatchOutcome.ActionType type, boolean override) {
        return new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", type, "action-" + type.name().toLowerCase(), "audit-a",
                T0.plusSeconds(3), override);
    }
}
