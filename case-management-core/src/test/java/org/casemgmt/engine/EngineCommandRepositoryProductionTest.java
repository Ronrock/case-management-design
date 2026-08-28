package org.casemgmt.engine;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineCommandRepositoryProductionTest extends OracleTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-28T12:00:00Z");
    private EngineCommandRepository repository;

    @BeforeEach
    void setUpRepository() {
        repository = new EngineCommandRepository(dataSource());
    }

    @Test
    void tenantIdempotencyReplaysTheOriginalOperationOnlyForTheSamePayloadDigest() {
        var request = request("command-a", "operation-a", "key-a", "a".repeat(64));

        var first = repository.submit(request);
        var replay = repository.submit(request(
                "command-b", "operation-b", "key-a", "a".repeat(64)));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.command().commandId()).isEqualTo("command-a");
        assertThat(replay.command().operationId()).isEqualTo("operation-a");
        assertThatThrownBy(() -> repository.submit(request(
                "command-c", "operation-c", "key-a", "b".repeat(64))))
                .isInstanceOf(EngineCommandRepository.IdempotencyConflictException.class);
        var differentIntent = new EngineCommandRepository.ProductionCommandRequest(
                "command-d", "case-a", "tenant-a", "operation-d", "key-a",
                "a".repeat(64), EngineCommand.Type.CANCEL_PROCESS,
                Map.of("processInstanceId", "task-a"), "task-a",
                null, null, null, NOW);
        assertThatThrownBy(() -> repository.submit(differentIntent))
                .isInstanceOf(EngineCommandRepository.IdempotencyConflictException.class);
        assertThat(repository.countCommands()).isEqualTo(1);
    }

    @Test
    void leaseClaimIsExclusiveAndIncrementsAttemptsAndVersionExactlyOnce() {
        repository.submit(request("command-a", "operation-a", "key-a", "a".repeat(64)));

        var first = repository.claimDue("worker-a", 10, NOW, Duration.ofMinutes(5));
        var second = repository.claimDue("worker-b", 10, NOW, Duration.ofMinutes(5));

        assertThat(first).singleElement().satisfies(leased -> {
            assertThat(leased.command().state().committedDecision().status())
                    .isEqualTo(EngineCommandStatus.DISPATCHING);
            assertThat(leased.command().state().committedDecision().totalDispatchAttempts())
                    .isEqualTo(1);
            assertThat(leased.command().version()).isEqualTo(1);
            assertThat(leased.leaseOwner()).isEqualTo("worker-a");
            assertThat(leased.leaseExpiresAt()).isEqualTo(NOW.plusMinutes(5));
        });
        assertThat(second).isEmpty();
    }

    @Test
    void expiredLeaseMovesToAwaitingConfirmationWithoutLosingAttemptHistory() {
        repository.submit(request("command-a", "operation-a", "key-a", "a".repeat(64)));
        repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5));

        assertThat(repository.recoverExpiredLeases(NOW.plusMinutes(6))).isEqualTo(1);
        var recovered = repository.require("tenant-a", "operation-a");

        assertThat(recovered.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.AWAITING_CONFIRMATION);
        assertThat(recovered.state().committedDecision().totalDispatchAttempts()).isEqualTo(1);
        assertThat(recovered.state().committedDecision().errorCode())
                .isEqualTo("dispatch.lease_expired");
        assertThat(recovered.version()).isEqualTo(2);
        assertThat(repository.claimDue("worker-b", 1, NOW.plusMinutes(6),
                Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void leaseOutcomeRequiresTheExactTokenAndVersionBeforeItCanCommit() {
        repository.submit(request("command-a", "operation-a", "key-a", "a".repeat(64)));
        var lease = repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5))
                .getFirst();
        var policy = new EngineCommandPolicy(java.time.Clock.fixed(
                NOW.plusSeconds(1).toInstant(), java.time.ZoneOffset.UTC));
        var outcome = policy.transition(lease.command().state(), CommandDispatchOutcome.http(
                202, CommandDispatchOutcome.Acceptance.ACCEPTED, null, null));

        assertThatThrownBy(() -> repository.commitLeaseDecision(
                "tenant-a", "operation-a", "wrong-token", lease.command().version(), outcome))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class);
        assertThat(repository.require("tenant-a", "operation-a").state()
                .committedDecision().status()).isEqualTo(EngineCommandStatus.DISPATCHING);

        var committed = repository.commitLeaseDecision(
                "tenant-a", "operation-a", lease.leaseToken(), lease.command().version(), outcome);
        assertThat(committed.state().committedDecision()).isEqualTo(outcome);
        assertThat(committed.version()).isEqualTo(2);
        assertThatThrownBy(() -> repository.commitLeaseDecision(
                "tenant-a", "operation-a", lease.leaseToken(), lease.command().version(), outcome))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class);
    }

    @Test
    void actionInsertAndSummaryCasAreAtomicAndCollisionReloadIsExactOrConflict() {
        repository.submit(request("command-a", "operation-a", "key-a", "a".repeat(64)));
        repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5));
        repository.recoverExpiredLeases(NOW.plusMinutes(6));
        var current = repository.require("tenant-a", "operation-a");
        var action = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ActionType.MANUAL_REVIEW,
                "action-a", "audit-a", NOW, false);
        var policy = new EngineCommandPolicy(java.time.Clock.fixed(NOW.toInstant(),
                java.time.ZoneOffset.UTC));
        var transition = policy.transition(current.state(),
                CommandDispatchOutcome.manualReviewRequested(action),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());

        assertThatThrownBy(() -> repository.appendActionAndTransition(
                current.commandId(), current.version() + 1, transition))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class);
        assertThat(repository.findAction("command-a", "action-a")).isEmpty();
        assertThat(repository.require("tenant-a", "operation-a").state()
                .committedDecision().actionLedgerSummary())
                .isEqualTo(EngineCommandPolicy.ActionLedgerSummary.empty());

        assertThat(repository.appendActionAndTransition(
                current.commandId(), current.version(), transition))
                .isEqualTo(EngineCommandRepository.ActionCommit.APPLIED);
        var committed = repository.require("tenant-a", "operation-a");
        assertThat(committed.state().committedDecision()).isEqualTo(transition.decision());
        assertThat(repository.findAction("command-a", "action-a"))
                .contains(transition.actionAppend().action());

        assertThat(repository.appendActionAndTransition(
                current.commandId(), current.version(), transition))
                .isEqualTo(EngineCommandRepository.ActionCommit.EXACT_REPLAY);

        var decision = transition.decision();
        var forgedDecision = new EngineCommandPolicy.Decision(
                decision.status(), decision.decidedAt().plusSeconds(1), decision.nextAttemptAt(),
                decision.errorCode(), decision.safeSummary(), decision.totalDispatchAttempts(),
                decision.automaticAttemptsInBudget(), decision.budgetEpoch(),
                decision.automaticBudgetReset(), decision.terminalConfirmation(),
                decision.legacyConfirmation(), decision.decisionEvidence(),
                decision.appliedAction(), decision.appliedActionPriorSummary(),
                decision.actionLedgerSummary());
        assertThatThrownBy(() -> repository.appendActionAndTransition(
                current.commandId(), current.version(),
                new EngineCommandPolicy.OperatorTransition(
                        forgedDecision, transition.actionAppend())))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class)
                .hasMessageContaining("exact replay");

        var changed = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ActionType.MANUAL_REVIEW,
                "action-a", "different-audit", NOW, false);
        var forged = policy.transition(current.state(),
                CommandDispatchOutcome.manualReviewRequested(changed),
                EngineCommandPolicy.AuthoritativeActionLookup.absent());
        assertThatThrownBy(() -> repository.appendActionAndTransition(
                current.commandId(), current.version(), forged))
                .isInstanceOf(EngineCommandRepository.ActionIdentityConflictException.class);
    }

    private static EngineCommandRepository.ProductionCommandRequest request(
            String commandId, String operationId, String key, String digest) {
        return new EngineCommandRepository.ProductionCommandRequest(
                commandId, "case-a", "tenant-a", operationId, key, digest,
                EngineCommand.Type.COMPLETE_TASK, Map.of("engineTaskId", "task-a"),
                "task-a", null, null, null, NOW);
    }
}
