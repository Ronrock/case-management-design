package org.casemgmt.engine;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineCommandRepositoryProductionTest extends OracleTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-28T12:00:00Z");
    private EngineCommandRepository repository;

    @BeforeEach
    void setUpRepository() {
        repository = new EngineCommandRepository(dataSource(), Clock.fixed(
                NOW.plusSeconds(10).toInstant(), ZoneOffset.UTC));
        JdbcClient.create(dataSource()).sql("""
                BEGIN
                  EXECUTE IMMEDIATE 'CREATE TABLE CM_COMMAND_TX_PROBE (ID_ VARCHAR2(64) PRIMARY KEY)';
                EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
                END;
                """).update();
        JdbcClient.create(dataSource()).sql("DELETE FROM CM_COMMAND_TX_PROBE").update();
    }

    @Test
    void tenantIdempotencyReplaysOnlyTheSameCanonicalIntent() {
        var request = request("command-a", "operation-a", "key-a", Map.of(
                "engineTaskId", "task-a", "variables", Map.of("b", 2, "a", 1)));

        var first = repository.submit(request);
        var replay = repository.submit(request(
                "command-b", "operation-b", "key-a", Map.of(
                        "variables", Map.of("a", 1.0, "b", 2), "engineTaskId", "task-a")));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.command().commandId()).isEqualTo("command-a");
        assertThat(replay.command().operationId()).isEqualTo("operation-a");
        assertThatThrownBy(() -> repository.submit(request(
                "command-c", "operation-c", "key-a", Map.of(
                        "engineTaskId", "task-a", "variables", Map.of("a", 2)))))
                .isInstanceOf(EngineCommandRepository.IdempotencyConflictException.class);
        assertThatThrownBy(() -> repository.submit(request(
                "command-a", "operation-a", "key-a", Map.of("engineTaskId", "changed"))))
                .isInstanceOf(EngineCommandRepository.IdempotencyConflictException.class);
        assertThatThrownBy(() -> repository.submit(request(
                "command-a", "operation-a", "different-key")))
                .isInstanceOf(EngineCommandRepository.OperationConflictException.class);
        var differentIntent = new EngineCommandRepository.ProductionCommandRequest(
                "command-d", "case-a", "tenant-a", "operation-d", "key-a",
                EngineCommand.Type.CANCEL_PROCESS,
                Map.of("processInstanceId", "task-a"), "task-a",
                null, null, null, NOW);
        assertThatThrownBy(() -> repository.submit(differentIntent))
                .isInstanceOf(EngineCommandRepository.IdempotencyConflictException.class);
        assertThat(repository.countCommands()).isEqualTo(1);
    }

    @Test
    void leaseClaimIsExclusiveAndIncrementsAttemptsAndVersionExactlyOnce() {
        repository.submit(request("command-a", "operation-a", "key-a"));

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

    @ParameterizedTest(name = "rejects forged current timestamps for {0}")
    @EnumSource(EngineCommandStatus.class)
    void rejectsForgedCurrentTimestampTupleForEveryStatus(EngineCommandStatus status) {
        var command = commandInStatus(status);
        JdbcClient.create(dataSource()).sql("""
                UPDATE CM_ENGINE_COMMAND
                SET UPDATED_AT_=UPDATED_AT_ + INTERVAL '1' SECOND
                WHERE ID_=:id
                """).param("id", command.commandId()).update();

        assertThatThrownBy(() -> repository.require("tenant-a", command.operationId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision/update timestamps");
    }

    @Test
    void failedRehydrateRollsBackEarlierClaimsInTheSameRepositoryTransaction() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        repository.submit(request("command-b", "operation-b", "key-b"));
        JdbcClient.create(dataSource()).sql("""
                UPDATE CM_ENGINE_COMMAND SET PAYLOAD_DIGEST_=:bad WHERE ID_='command-b'
                """).param("bad", "0".repeat(64)).update();

        assertThatThrownBy(() -> repository.claimDue(
                "worker-a", 2, NOW, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadDigest");

        var row = JdbcClient.create(dataSource()).sql("""
                SELECT STATUS_ || ':' || ROW_VERSION_ FROM CM_ENGINE_COMMAND WHERE ID_='command-a'
                """).query(String.class).single();
        assertThat(row).isEqualTo("PENDING:0");
    }

    @Test
    void claimSkipsALockedCandidateAndStillClaimsUnrelatedDueWork() throws Exception {
        repository.submit(request("command-a", "operation-a", "key-a"));
        repository.submit(request("command-b", "operation-b", "key-b"));
        try (Connection blocker = dataSource().getConnection()) {
            blocker.setAutoCommit(false);
            blocker.prepareStatement("SELECT ID_ FROM CM_ENGINE_COMMAND "
                    + "WHERE ID_='command-a' FOR UPDATE").executeQuery();

            var claimed = repository.claimDue("worker-b", 1, NOW, Duration.ofMinutes(5));

            assertThat(claimed).singleElement().satisfies(lease ->
                    assertThat(lease.command().commandId()).isEqualTo("command-b"));
            blocker.rollback();
        }
        assertThat(repository.require("tenant-a", "operation-a").state()
                .committedDecision().status()).isEqualTo(EngineCommandStatus.PENDING);
    }

    @Test
    void recoverySkipsALockedExpiredLeaseAndRecoversItAfterUnlock() throws Exception {
        repository.submit(request("command-a", "operation-a", "key-a"));
        repository.submit(request("command-b", "operation-b", "key-b"));
        assertThat(repository.claimDue("worker-a", 2, NOW, Duration.ofMinutes(5))).hasSize(2);
        try (Connection blocker = dataSource().getConnection()) {
            blocker.setAutoCommit(false);
            blocker.prepareStatement("SELECT ID_ FROM CM_ENGINE_COMMAND "
                    + "WHERE ID_='command-a' FOR UPDATE").executeQuery();
            assertThat(repository.recoverExpiredLeases(NOW.plusMinutes(6))).isEqualTo(1);
            assertThat(repository.require("tenant-a", "operation-a").state()
                    .committedDecision().status()).isEqualTo(EngineCommandStatus.DISPATCHING);
            blocker.rollback();
        }
        assertThat(repository.recoverExpiredLeases(NOW.plusMinutes(6))).isEqualTo(1);
        assertThat(repository.require("tenant-a", "operation-a").state()
                .committedDecision().status())
                .isEqualTo(EngineCommandStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void expiredLeaseMovesToAwaitingConfirmationWithoutLosingAttemptHistory() {
        repository.submit(request("command-a", "operation-a", "key-a"));
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
    void secondClaimPersistsTheExactAutomaticRetryBudgetAndLifetimeCounters() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        var first = repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5)).getFirst();
        var retry = repository.commitLeaseOutcome(
                "tenant-a", "operation-a", first.leaseToken(), first.command().version(),
                CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.PRE_SEND_ZERO_BYTES));
        assertThat(retry.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.RETRYABLE);

        var second = repository.claimDue("worker-b", 1,
                retry.state().committedDecision().nextAttemptAt().plusSeconds(1),
                Duration.ofMinutes(5)).getFirst();
        assertThat(second.command().state().committedDecision())
                .satisfies(decision -> {
                    assertThat(decision.status()).isEqualTo(EngineCommandStatus.DISPATCHING);
                    assertThat(decision.totalDispatchAttempts()).isEqualTo(2);
                    assertThat(decision.automaticAttemptsInBudget()).isEqualTo(2);
                    assertThat(decision.nextAttemptAt()).isNull();
                    assertThat(decision.errorCode()).isNull();
                    assertThat(decision.appliedAction()).isNull();
                });
    }

    @Test
    void leaseOutcomeRequiresTheExactTokenAndVersionBeforeItCanCommit() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        var lease = repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5))
                .getFirst();
        var outcome = CommandDispatchOutcome.http(
                202, CommandDispatchOutcome.Acceptance.ACCEPTED, null, null);

        assertThatThrownBy(() -> repository.commitLeaseOutcome(
                "tenant-a", "operation-a", "wrong-token", lease.command().version(), outcome))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class);
        assertThat(repository.require("tenant-a", "operation-a").state()
                .committedDecision().status()).isEqualTo(EngineCommandStatus.DISPATCHING);

        var committed = repository.commitLeaseOutcome(
                "tenant-a", "operation-a", lease.leaseToken(), lease.command().version(), outcome);
        assertThat(committed.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.AWAITING_CONFIRMATION);
        assertThat(committed.version()).isEqualTo(2);
        assertThatThrownBy(() -> repository.commitLeaseOutcome(
                "tenant-a", "operation-a", lease.leaseToken(), lease.command().version(), outcome))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class);
    }

    @Test
    void exactTerminalObservationReplayDoesNotAdvanceVersionOrRewriteDecision() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        var pending = repository.require("tenant-a", "operation-a");
        var evidence = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                CommandDispatchOutcome.ConfirmationSource.OBSERVATION, "evidence-a");
        var confirmed = repository.applyOutcome("tenant-a", "operation-a", pending.version(),
                CommandDispatchOutcome.observation(evidence));

        var replay = repository.applyOutcome("tenant-a", "operation-a", confirmed.version(),
                CommandDispatchOutcome.observation(evidence));

        assertThat(replay).isEqualTo(confirmed);
        assertThat(replay.version()).isEqualTo(confirmed.version());
    }

    @Test
    void actionInsertAndSummaryCasAreAtomicAndCollisionReloadIsExactOrConflict() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5));
        repository.recoverExpiredLeases(NOW.plusMinutes(6));
        var current = repository.require("tenant-a", "operation-a");
        var action = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ActionType.MANUAL_REVIEW,
                "action-a", "audit-a", NOW.plusSeconds(10), false);
        var outcome = CommandDispatchOutcome.manualReviewRequested(action);

        assertThatThrownBy(() -> repository.applyOperatorOutcome(
                "tenant-a", "operation-a", current.version() + 1, outcome))
                .isInstanceOf(EngineCommandRepository.OptimisticCommandException.class);
        assertThat(repository.findAction("command-a", "action-a")).isEmpty();
        assertThat(repository.require("tenant-a", "operation-a").state()
                .committedDecision().actionLedgerSummary())
                .isEqualTo(EngineCommandPolicy.ActionLedgerSummary.empty());

        assertThat(repository.applyOperatorOutcome(
                "tenant-a", "operation-a", current.version(), outcome))
                .isEqualTo(EngineCommandRepository.ActionCommit.APPLIED);
        var committed = repository.require("tenant-a", "operation-a");
        assertThat(committed.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.MANUAL_REVIEW);
        assertThat(repository.findAction("command-a", "action-a"))
                .contains(committed.state().committedDecision().appliedAction());

        assertThat(repository.applyOperatorOutcome(
                "tenant-a", "operation-a", current.version(), outcome))
                .isEqualTo(EngineCommandRepository.ActionCommit.EXACT_REPLAY);

        var changed = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ActionType.MANUAL_REVIEW,
                "action-a", "different-audit", NOW.plusSeconds(10), false);
        assertThatThrownBy(() -> repository.applyOperatorOutcome(
                "tenant-a", "operation-a", committed.version(),
                CommandDispatchOutcome.manualReviewRequested(changed)))
                .isInstanceOf(EngineCommandRepository.ActionIdentityConflictException.class);
    }

    @Test
    void retryClaimClearsCurrentActionButRetainsTheCompleteNormalizedHistory() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5));
        repository.recoverExpiredLeases(NOW.plusMinutes(6));
        var current = repository.require("tenant-a", "operation-a");
        var manual = action("action-manual", CommandDispatchOutcome.ActionType.MANUAL_REVIEW,
                false, null);
        repository.applyOperatorOutcome("tenant-a", "operation-a", current.version(),
                CommandDispatchOutcome.manualReviewRequested(manual));
        var reviewed = repository.require("tenant-a", "operation-a");
        var absence = new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW, "evidence-a");
        var retry = action("action-retry", CommandDispatchOutcome.ActionType.RETRY_OVERRIDE,
                false, absence);
        repository.applyOperatorOutcome("tenant-a", "operation-a", reviewed.version(),
                CommandDispatchOutcome.retryAfterReviewedAbsence(absence, retry));

        var lease = repository.claimDue("worker-b", 1, NOW.plusSeconds(11),
                Duration.ofMinutes(5)).getFirst();
        var decision = lease.command().state().committedDecision();
        assertThat(decision.status()).isEqualTo(EngineCommandStatus.DISPATCHING);
        assertThat(decision.totalDispatchAttempts()).isEqualTo(2);
        assertThat(decision.appliedAction()).isNull();
        assertThat(decision.decisionEvidence()).isNull();
        assertThat(decision.actionLedgerSummary())
                .isEqualTo(new EngineCommandPolicy.ActionLedgerSummary(2, 2, 0, 0));
    }

    @Test
    void duplicateSubmitDoesNotPoisonACallerOwnedTransaction() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        var jdbc = JdbcClient.create(dataSource());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

        transaction.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO CM_COMMAND_TX_PROBE(ID_) VALUES ('before-replay')").update();
            assertThat(repository.submit(request(
                    "command-b", "operation-b", "key-a")).replayed()).isTrue();
            jdbc.sql("INSERT INTO CM_COMMAND_TX_PROBE(ID_) VALUES ('after-replay')").update();
        });
        transaction.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO CM_COMMAND_TX_PROBE(ID_) VALUES ('before-conflict')").update();
            assertThatThrownBy(() -> repository.submit(request(
                    "command-c", "operation-c", "key-a", Map.of("engineTaskId", "different"))))
                    .isInstanceOf(EngineCommandRepository.IdempotencyConflictException.class);
            jdbc.sql("INSERT INTO CM_COMMAND_TX_PROBE(ID_) VALUES ('after-conflict')").update();
        });
        transaction.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO CM_COMMAND_TX_PROBE(ID_) VALUES ('before-operation')").update();
            assertThatThrownBy(() -> repository.submit(request(
                    "command-d", "operation-a", "different-key")))
                    .isInstanceOf(EngineCommandRepository.OperationConflictException.class);
            jdbc.sql("INSERT INTO CM_COMMAND_TX_PROBE(ID_) VALUES ('after-operation')").update();
        });

        assertThat(jdbc.sql("SELECT ID_ FROM CM_COMMAND_TX_PROBE ORDER BY ID_")
                .query(String.class).list()).containsExactly(
                "after-conflict", "after-operation", "after-replay",
                "before-conflict", "before-operation", "before-replay");
    }

    @Test
    void concurrentSubmissionsResolveToOneCanonicalIntent() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentSubmit(ready, go,
                    request("command-a", "operation-a", "key-a", Map.of("n", 1))));
            var second = pool.submit(() -> concurrentSubmit(ready, go,
                    request("command-b", "operation-b", "key-a", Map.of("n", 1.0))));
            ready.await();
            go.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("CREATED", "REPLAY");
        }
        assertThat(repository.countCommands()).isEqualTo(1);
    }

    @Test
    void concurrentSameCommandIdClassifiesExactlyOneLoserAsReplay() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var request = request("command-a", "operation-a", "key-a", Map.of("n", 1));
            var first = pool.submit(() -> concurrentSubmit(ready, go, request));
            var second = pool.submit(() -> concurrentSubmit(ready, go, request));
            ready.await();
            go.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("CREATED", "REPLAY");
        }
        assertThat(repository.countCommands()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadRejectsTheLoserWithoutASecondRow() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentSubmit(ready, go,
                    request("command-a", "operation-a", "key-a", Map.of("n", 1))));
            var second = pool.submit(() -> concurrentSubmit(ready, go,
                    request("command-b", "operation-b", "key-a", Map.of("n", 2))));
            ready.await();
            go.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("CREATED", "CONFLICT");
        }
        assertThat(repository.countCommands()).isEqualTo(1);
    }

    @Test
    void operatorActionCannotCrossACommandsParentBinding() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        repository.claimDue("worker-a", 1, NOW, Duration.ofMinutes(5));
        repository.recoverExpiredLeases(NOW.plusMinutes(6));
        var current = repository.require("tenant-a", "operation-a");
        var forged = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "other-operation", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", CommandDispatchOutcome.ActionType.MANUAL_REVIEW,
                "action-a", "audit-a", NOW.plusSeconds(10), false);

        assertThatThrownBy(() -> repository.applyOperatorOutcome(
                "tenant-a", "operation-a", current.version(),
                CommandDispatchOutcome.manualReviewRequested(forged)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAction("command-a", "action-a")).isEmpty();
    }

    @Test
    void rehydrateRejectsEveryNormalizedRowWhoseParentBindingWasForged() {
        repository.submit(request("command-a", "operation-a", "key-a"));
        var jdbc = JdbcClient.create(dataSource());
        jdbc.sql("""
                INSERT INTO CM_ENGINE_COMMAND_ACTION
                  (COMMAND_ID_, SEQUENCE_, ACTION_ID_, TENANT_ID_, OPERATION_ID_, COMMAND_TYPE_,
                   EXPECTED_TARGET_, ACTION_TYPE_, AUDIT_REFERENCE_, PERFORMED_AT_,
                   OVERRIDE_AUTO_CAP_)
                VALUES ('command-a',1,'action-a','other-tenant','operation-a','COMPLETE_TASK',
                        'task-a','MANUAL_REVIEW','audit-a',:performedAt,0)
                """).param("performedAt", NOW).update();
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET ACTION_COUNT_=1, ACTION_HIGH_WATER_=1,
                  CURRENT_ACTION_SEQ_=1 WHERE ID_='command-a'
                """).update();

        assertThatThrownBy(() -> repository.require("tenant-a", "operation-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent binding");
    }

    private String concurrentSubmit(
            CountDownLatch ready, CountDownLatch go,
            EngineCommandRepository.ProductionCommandRequest request) throws Exception {
        ready.countDown();
        go.await();
        try {
            var result = new EngineCommandRepository(dataSource()).submit(request);
            return result.replayed() ? "REPLAY" : "CREATED";
        } catch (EngineCommandRepository.IdempotencyConflictException conflict) {
            return "CONFLICT";
        }
    }

    private EngineCommandRepository.StoredCommand commandInStatus(EngineCommandStatus status) {
        String suffix = status.name().toLowerCase(java.util.Locale.ROOT);
        String commandId = "matrix-" + suffix;
        String operationId = "matrix-op-" + suffix;
        repository.submit(request(commandId, operationId, "matrix-key-" + suffix));
        if (status == EngineCommandStatus.PENDING) {
            return repository.require("tenant-a", operationId);
        }
        if (status == EngineCommandStatus.CONFIRMED) {
            var evidence = new CommandDispatchOutcome.ConfirmationEvidence(
                    "tenant-a", operationId, commandId, EngineCommand.Type.COMPLETE_TASK,
                    "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                    CommandDispatchOutcome.ConfirmationSource.OBSERVATION,
                    "matrix-evidence-" + suffix);
            var pending = repository.require("tenant-a", operationId);
            return repository.applyOutcome("tenant-a", operationId, pending.version(),
                    CommandDispatchOutcome.observation(evidence));
        }
        if (status == EngineCommandStatus.CANCELLED) {
            var pending = repository.require("tenant-a", operationId);
            var cancel = matrixAction(commandId, operationId,
                    CommandDispatchOutcome.ActionType.CANCEL);
            repository.applyOperatorOutcome("tenant-a", operationId, pending.version(),
                    CommandDispatchOutcome.cancelUnsent(cancel));
            return repository.require("tenant-a", operationId);
        }

        var lease = repository.claimDue("matrix-worker-" + suffix, 1,
                NOW, Duration.ofMinutes(5)).getFirst();
        if (status == EngineCommandStatus.DISPATCHING) {
            return lease.command();
        }
        CommandDispatchOutcome outcome = switch (status) {
            case RETRYABLE -> CommandDispatchOutcome.transportFailure(
                    CommandDispatchOutcome.TransportFailure.PRE_SEND_ZERO_BYTES);
            case AWAITING_CONFIRMATION, MANUAL_REVIEW -> CommandDispatchOutcome.http(
                    202, CommandDispatchOutcome.Acceptance.ACCEPTED, null, null);
            case FAILED -> CommandDispatchOutcome.http(
                    400, CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED, null, null);
            case CONFLICT -> CommandDispatchOutcome.http(
                    409, CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED, null, null);
            default -> throw new IllegalArgumentException("Unsupported matrix status " + status);
        };
        var committed = repository.commitLeaseOutcome(
                "tenant-a", operationId, lease.leaseToken(), lease.command().version(), outcome);
        if (status != EngineCommandStatus.MANUAL_REVIEW) {
            return committed;
        }
        var review = matrixAction(commandId, operationId,
                CommandDispatchOutcome.ActionType.MANUAL_REVIEW);
        repository.applyOperatorOutcome("tenant-a", operationId, committed.version(),
                CommandDispatchOutcome.manualReviewRequested(review));
        return repository.require("tenant-a", operationId);
    }

    private static CommandDispatchOutcome.OperatorAction matrixAction(
            String commandId, String operationId, CommandDispatchOutcome.ActionType type) {
        return new CommandDispatchOutcome.OperatorAction(
                "tenant-a", operationId, commandId, EngineCommand.Type.COMPLETE_TASK,
                "task-a", type, "matrix-action-" + type.name().toLowerCase(
                        java.util.Locale.ROOT), "matrix-audit", NOW.plusSeconds(10), false);
    }

    private static CommandDispatchOutcome.OperatorAction action(
            String id, CommandDispatchOutcome.ActionType type, boolean override,
            CommandDispatchOutcome.ReviewEvidence ignored) {
        return new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", type, id, "audit-" + id, NOW.plusSeconds(10), override);
    }

    private static EngineCommandRepository.ProductionCommandRequest request(
            String commandId, String operationId, String key) {
        return request(commandId, operationId, key, Map.of("engineTaskId", "task-a"));
    }

    private static EngineCommandRepository.ProductionCommandRequest request(
            String commandId, String operationId, String key, Map<String, Object> payload) {
        return new EngineCommandRepository.ProductionCommandRequest(
                commandId, "case-a", "tenant-a", operationId, key,
                EngineCommand.Type.COMPLETE_TASK, payload,
                "task-a", null, null, null, NOW);
    }
}
