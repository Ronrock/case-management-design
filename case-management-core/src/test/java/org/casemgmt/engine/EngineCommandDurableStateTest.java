package org.casemgmt.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
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
    void budgetResetRequiresVerifiedAggregateAbsenceZeroBudgetAndIncrementedEpoch() {
        var absence = review(DEFINITIVE_ABSENCE, OPERATOR_REVIEW, "review:override");
        var override = action(RETRY_OVERRIDE, "action:override", true, 0);
        var applied = new EngineCommandPolicy.ProcessedAction(1, override, absence);
        var summary = new EngineCommandPolicy.ActionLedgerSummary(1, 1, 1, 0);

        assertThatCode(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.RETRYABLE, true, 6, 0, 1,
                null, absence, applied, summary))).doesNotThrowAnyException();
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 6, 0, 0,
                null, absence, applied, summary));
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 6, 1, 1,
                null, absence, applied, summary));
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 6, 0, 1,
                null, null, applied, summary));
        assertInvalidReset(() -> newDecision(EngineCommandStatus.RETRYABLE, true, 6, 0, 1,
                null, absence, applied,
                new EngineCommandPolicy.ActionLedgerSummary(1, 1, 0, 0)));
    }

    @Test
    void forgedRehydratedBindingsAndAggregatesFailClosed() {
        var wrongStateEvidence = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", "task-a",
                CommandDispatchOutcome.RemoteState.TASK_CLAIMED, HTTP_RESPONSE,
                "evidence:wrong-state");
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.CONFIRMED, false, 2, 2, 0,
                wrongStateEvidence, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");

        var wrongTenant = action(MANUAL_REVIEW, "action:wrong-tenant", false, 0,
                "other-tenant");
        var wrongApplied = new EngineCommandPolicy.ProcessedAction(1, wrongTenant, null);
        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(command(), newDecision(
                EngineCommandStatus.MANUAL_REVIEW, false, 2, 2, 0,
                null, null, wrongApplied,
                new EngineCommandPolicy.ActionLedgerSummary(1, 1, 0, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");

        var manual = action(MANUAL_REVIEW, "action:high-water", false, 0);
        assertThatThrownBy(() -> newDecision(
                EngineCommandStatus.MANUAL_REVIEW, false, 2, 2, 0,
                null, null, new EngineCommandPolicy.ProcessedAction(1, manual, null),
                new EngineCommandPolicy.ActionLedgerSummary(2, 2, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("high-water");

        assertThatThrownBy(() -> new EngineCommandPolicy.ActionLedgerSummary(3, 4, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("high-water");
        assertThatThrownBy(() -> new EngineCommandPolicy.ActionLedgerSummary(1, 1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subtype");
        assertThatThrownBy(() -> new EngineCommandPolicy.ActionLedgerSummary(2, 2, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one cancellation");

        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.PENDING, AT, null, null, null,
                1, 2, 0, false, null, null, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifetime");
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.DISPATCHING, AT, null, null, null,
                0, 0, 0, false, null, null, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dispatching");
    }

    @Test
    void terminalDiagnosticsAndInternallyMismatchedActionEvidenceFailClosed() {
        var confirmation = confirmation(HTTP_RESPONSE, "evidence:terminal");
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.CONFIRMED, AT, null, "forged.error",
                "Forged terminal diagnostic", 2, 2, 0, false,
                confirmation, null, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty()))
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
                null, null, null, null, EngineCommandPolicy.ActionLedgerSummary.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnostic");
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, AT, AT.plusMinutes(1), "transport.not_sent",
                "password=should-not-survive", 2, 2, 0, false,
                null, null, null, null, EngineCommandPolicy.ActionLedgerSummary.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnostic");

        var persisted = new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, AT, AT.plusMinutes(1), "transport.not_sent",
                "Remote request sent zero bytes", 2, 2, 0, false,
                null, null, null, null, EngineCommandPolicy.ActionLedgerSummary.empty());
        assertThat(persisted.errorCode()).isEqualTo("transport.not_sent");
        assertThat(persisted.safeSummary()).isEqualTo("Remote request sent zero bytes");
    }

    @Test
    void persistedTimestampsCanonicalizeToUtcOracleMicroseconds() {
        OffsetDateTime jdbcUnstable = OffsetDateTime.parse(
                "2026-08-28T14:00:00.123456789+02:00");
        OffsetDateTime canonical = OffsetDateTime.parse("2026-08-28T12:00:00.123456Z");
        var action = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", MANUAL_REVIEW,
                "action:canonical-time", "audit:canonical-time", jdbcUnstable, false);
        var decision = new EngineCommandPolicy.Decision(
                EngineCommandStatus.RETRYABLE, jdbcUnstable, jdbcUnstable.plusMinutes(1),
                "transport.not_sent", "Remote request sent zero bytes",
                2, 2, 0, false, null, null, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty());

        assertThat(action.performedAt()).isEqualTo(canonical);
        assertThat(decision.decidedAt()).isEqualTo(canonical);
        assertThat(decision.nextAttemptAt()).isEqualTo(canonical.plusMinutes(1));
        assertThatThrownBy(() -> new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", MANUAL_REVIEW,
                "action:before-oracle-range", "audit:before-oracle-range",
                OffsetDateTime.parse("0000-12-31T23:59:59Z"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Oracle timestamp range");
    }

    @Test
    void confirmationRoundtripPreservesFirstEvidence() {
        var confirmation = confirmation(HTTP_RESPONSE, "evidence:http");
        var confirmed = POLICY.transition(state(EngineCommandStatus.DISPATCHING),
                CommandDispatchOutcome.http(200,
                        CommandDispatchOutcome.Acceptance.ACCEPTED, null, confirmation));
        var rehydrated = rehydrate(confirmed);
        var observation = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", TYPE, "task-a", "task-a",
                CommandDispatchOutcome.RemoteState.TASK_COMPLETED, OBSERVATION,
                "evidence:observation");

        assertThat(POLICY.transition(state(rehydrated),
                CommandDispatchOutcome.observation(observation))).isEqualTo(rehydrated);
        assertThat(rehydrated.terminalConfirmation()).isEqualTo(confirmation);
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
        EngineCommandPolicy.ProcessedAction applied = action == null ? null
                : new EngineCommandPolicy.ProcessedAction(1, action, evidence);
        EngineCommandPolicy.ActionLedgerSummary ledger = applied == null
                ? EngineCommandPolicy.ActionLedgerSummary.empty()
                : new EngineCommandPolicy.ActionLedgerSummary(
                        1, 1, action.overrideAutomaticAttemptCap() ? 1 : 0,
                        action.actionType() == CANCEL ? 1 : 0);
        return newDecision(status, false, 2, 2,
                action != null && action.overrideAutomaticAttemptCap() ? 1 : 0,
                confirmation, evidence, applied, ledger);
    }

    private static EngineCommandPolicy.Decision newDecision(
            EngineCommandStatus status, boolean reset, long total, int budget, long epoch,
            CommandDispatchOutcome.ConfirmationEvidence confirmation,
            CommandDispatchOutcome.ReviewEvidence evidence,
            EngineCommandPolicy.ProcessedAction applied,
            EngineCommandPolicy.ActionLedgerSummary ledger) {
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
                code, summary, total, budget, epoch, reset,
                confirmation, null, evidence, applied, ledger);
    }

    private static void assertInvalidReset(Runnable construction) {
        assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static EngineCommandPolicy.CommandState state(EngineCommandStatus status) {
        return status == EngineCommandStatus.MANUAL_REVIEW
                ? state(newDecision(status, false, 2, 2, 0, null,
                        review(INCONCLUSIVE, RECONCILIATION, "review:prior"), null,
                        EngineCommandPolicy.ActionLedgerSummary.empty()))
                : state(newDecision(status, false, 2, 2, 0, null, null, null,
                        EngineCommandPolicy.ActionLedgerSummary.empty()));
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

    private static EngineCommandPolicy.Decision rehydrate(
            EngineCommandPolicy.Decision decision) {
        EngineCommandPolicy.ProcessedAction applied = decision.appliedAction() == null ? null
                : new EngineCommandPolicy.ProcessedAction(
                        decision.appliedAction().sequence(), decision.appliedAction().action(),
                        decision.appliedAction().reviewEvidence());
        return new EngineCommandPolicy.Decision(
                decision.status(), decision.decidedAt(), decision.nextAttemptAt(),
                decision.errorCode(), decision.safeSummary(), decision.totalDispatchAttempts(),
                decision.automaticAttemptsInBudget(), decision.budgetEpoch(),
                decision.automaticBudgetReset(), decision.terminalConfirmation(),
                decision.legacyConfirmation(), decision.decisionEvidence(), applied,
                decision.actionLedgerSummary());
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
}
