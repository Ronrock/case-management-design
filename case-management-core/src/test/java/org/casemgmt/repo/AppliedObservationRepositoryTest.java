package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Oracle-backed proof that one engine fact can own lifecycle effects only once. */
class AppliedObservationRepositoryTest extends OracleTestBase {

    private AppliedObservationRepository observations;
    private AnnotationConfigApplicationContext transactionContext;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        observations = new AppliedObservationRepository(jdbc());
    }

    @AfterEach
    void closeTransactionContext() {
        if (transactionContext != null) {
            transactionContext.close();
        }
    }

    @Test
    void firstClaimOwnsTheObservationAndRecordsOperationalMetadata() {
        ProcessObservation observation = observation("observation-1", "tenant-a");

        AppliedObservationRepository.ClaimResult result = observations.claim(observation);

        assertThat(result.outcome()).isEqualTo(AppliedObservationRepository.ClaimOutcome.CLAIMED);
        assertThat(result.ownsClaim()).isTrue();
        assertThat(result.claim()).isPresent();
        StoredRow stored = jdbc().sql("""
                SELECT STATUS_, OBSERVATION_ID_, SOURCE_, CASE_ID_, PROCESS_INSTANCE_ID_,
                       ENTITY_ID_, EVENT_TYPE_, CLAIMED_AT_
                FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", observation.fingerprint())
                .query((rs, row) -> new StoredRow(rs.getString("STATUS_"),
                        rs.getString("OBSERVATION_ID_"), rs.getString("SOURCE_"),
                        rs.getString("CASE_ID_"), rs.getString("PROCESS_INSTANCE_ID_"),
                        rs.getString("ENTITY_ID_"), rs.getString("EVENT_TYPE_"),
                        rs.getObject("CLAIMED_AT_", OffsetDateTime.class), null, null))
                .single();
        assertThat(stored.status()).isEqualTo("CLAIMED");
        assertThat(stored.observationId()).isEqualTo("observation-1");
        assertThat(stored.source()).isEqualTo("operaton:embedded");
        assertThat(jdbc().sql("""
                SELECT ENGINE_ID_ FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", observation.fingerprint())
                .query(String.class).single()).isEqualTo(observation.engineId());
        assertThat(stored.caseId()).isEqualTo("case-1");
        assertThat(stored.processInstanceId()).isEqualTo("process-1");
        assertThat(stored.entityId()).isEqualTo("process-1");
        assertThat(stored.eventType()).isEqualTo("STARTED");
        assertThat(stored.claimedAt()).isNotNull();

        observations.markApplied(result.claim().orElseThrow());

        assertThat(jdbc().sql("""
                SELECT STATUS_, APPLIED_AT_ FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", observation.fingerprint())
                .query((rs, row) -> new StoredRow(rs.getString("STATUS_"), null, null, null,
                        null, null, null, null,
                        rs.getObject("APPLIED_AT_", OffsetDateTime.class), null))
                .single().status()).isEqualTo("APPLIED");
    }

    @Test
    void duplicateClaimDoesNotReplaceTheOwningObservationOrMutateItsClaim() {
        ProcessObservation original = observation("observation-1", "tenant-a");
        ProcessObservation redelivery = observation("observation-2", "tenant-a");
        observations.claim(original);
        OffsetDateTime claimedAt = jdbc().sql("""
                SELECT CLAIMED_AT_ FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", original.fingerprint()).query(OffsetDateTime.class).single();

        AppliedObservationRepository.ClaimResult duplicate = observations.claim(redelivery);

        assertThat(duplicate.outcome()).isEqualTo(AppliedObservationRepository.ClaimOutcome.DUPLICATE);
        assertThat(duplicate.ownsClaim()).isFalse();
        assertThat(duplicate.claim()).isEmpty();
        StoredRow stored = jdbc().sql("""
                SELECT OBSERVATION_ID_, STATUS_, CLAIMED_AT_
                FROM CM_APPLIED_ENGINE_OBSERVATION WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", original.fingerprint())
                .query((rs, row) -> new StoredRow(rs.getString("STATUS_"),
                        rs.getString("OBSERVATION_ID_"), null, null, null, null, null,
                        rs.getObject("CLAIMED_AT_", OffsetDateTime.class), null, null))
                .single();
        assertThat(stored.observationId()).isEqualTo("observation-1");
        assertThat(stored.status()).isEqualTo("CLAIMED");
        assertThat(stored.claimedAt()).isEqualTo(claimedAt);
    }

    @Test
    void sameObservationIdReclaimInvalidatesTheStaleOwnershipToken() {
        ProcessObservation original = observation("observation-1", null);
        AppliedObservationRepository.ClaimResult first = observations.claim(original);
        observations.markFailed(first.claim().orElseThrow(), "projection unavailable");

        ProcessObservation retry = observation("observation-1", null);
        AppliedObservationRepository.ClaimResult reclaimed = observations.claim(retry);

        assertThat(reclaimed.outcome()).isEqualTo(AppliedObservationRepository.ClaimOutcome.RECLAIMED);
        assertThat(reclaimed.ownsClaim()).isTrue();
        StoredRow stored = jdbc().sql("""
                SELECT STATUS_, OBSERVATION_ID_, FAILED_AT_, FAILURE_DETAIL_
                FROM CM_APPLIED_ENGINE_OBSERVATION WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", retry.fingerprint())
                .query((rs, row) -> new StoredRow(rs.getString("STATUS_"),
                        rs.getString("OBSERVATION_ID_"), null, null, null, null, null, null,
                        rs.getObject("FAILED_AT_", OffsetDateTime.class),
                        rs.getString("FAILURE_DETAIL_")))
                .single();
        assertThat(stored.status()).isEqualTo("CLAIMED");
        assertThat(stored.observationId()).isEqualTo("observation-1");
        assertThat(stored.failedAt()).isNotNull();
        assertThat(stored.failureDetail()).isEqualTo("projection unavailable");
        assertThatThrownBy(() -> observations.markApplied(first.claim().orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer owns");
        observations.markApplied(reclaimed.claim().orElseThrow());
    }

    @Test
    void staleFinalizationDeduplicatesReplayButIsNotABusinessWatermark() {
        ProcessObservation stale = observation("stale-observation", "tenant-a");
        AppliedObservationRepository.ClaimResult claimed = observations.claim(stale);

        observations.markIgnoredStale(claimed.claim().orElseThrow());

        assertThat(jdbc().sql("""
                SELECT STATUS_, IGNORED_AT_ FROM CM_APPLIED_ENGINE_OBSERVATION
                WHERE FINGERPRINT_ = :fingerprint""")
                .param("fingerprint", stale.fingerprint())
                .query((rs, row) -> Map.entry(rs.getString("STATUS_"),
                        rs.getObject("IGNORED_AT_", OffsetDateTime.class)))
                .single())
                .satisfies(row -> {
                    assertThat(row.getKey()).isEqualTo("IGNORED_STALE");
                    assertThat(row.getValue()).isNotNull();
                });
        assertThat(observations.claim(observation("stale-replay", "tenant-a")).outcome())
                .isEqualTo(AppliedObservationRepository.ClaimOutcome.DUPLICATE);
        assertThat(observations.latestAppliedPosition(stale)).isEmpty();
    }

    @Test
    void observationKindsHaveIndependentEntityNamespaces() {
        ProcessObservation process = observation("process-observation", "tenant-a");
        observations.markApplied(observations.claim(process).claim().orElseThrow());
        UserTaskObservation task = new UserTaskObservation("task-observation", 1,
                process.source(), process.tenantId(), process.caseId(), process.processInstanceId(),
                process.entityId(), 1L, UserTaskObservation.EventType.CREATED,
                process.engineOccurredAt(), process.receivedAt(), Map.of());

        assertThat(observations.latestAppliedPosition(process)).isPresent();
        assertThat(observations.latestAppliedPosition(task)).isEmpty();
    }

    @Test
    void adapterChannelsShareFingerprintClaimsAndEngineWatermark() {
        Instant occurred = Instant.parse("2026-08-28T08:30:00Z");
        ProcessObservation embedded = new ProcessObservation("embedded", 1,
                "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                "process-1", 5L, ProcessObservation.EventType.STARTED, occurred, occurred,
                Map.of("caseDefinition", "claims"));
        ProcessObservation reconciliation = new ProcessObservation("reconciliation", 1,
                "reconciliation", "engine-a", "tenant-a", "case-1", "process-1",
                "process-1", 5L, ProcessObservation.EventType.STARTED, occurred,
                occurred.plusSeconds(30), Map.of("caseDefinition", "claims"));
        observations.markApplied(observations.claim(embedded).claim().orElseThrow());

        assertThat(observations.claim(reconciliation).outcome())
                .isEqualTo(AppliedObservationRepository.ClaimOutcome.DUPLICATE);

        ProcessObservation olderRemote = new ProcessObservation("remote-older", 1,
                "operaton:remote", "engine-a", "tenant-a", "case-1", "process-1",
                "process-1", 4L, ProcessObservation.EventType.STARTED,
                occurred.plusSeconds(1), occurred.plusSeconds(2), Map.of());
        assertThat(observations.latestAppliedPosition(olderRemote)).get()
                .extracting(AppliedObservationRepository.AppliedPosition::entityRevision)
                .isEqualTo(5L);
    }

    @Test
    void legacyAppliedHistoryBlocksTypedOrderingInsteadOfGuessingIdentity() {
        jdbc().sql("""
                INSERT INTO CM_APPLIED_ENGINE_OBSERVATION
                  (OBSERVATION_ID_, TENANT_ID_, FINGERPRINT_, CLAIM_TOKEN_, STATUS_, SOURCE_,
                   CASE_ID_, PROCESS_INSTANCE_ID_, ENTITY_ID_, ENTITY_REVISION_, EVENT_TYPE_,
                   ENGINE_OCCURRED_AT_, CLAIMED_AT_, APPLIED_AT_, OBSERVATION_KIND_)
                VALUES ('legacy-10', 'tenant-a', RPAD('a', 64, 'a'), RPAD('b', 43, 'b'),
                  'APPLIED', 'legacy-adapter', 'case-1', 'process-1', 'process-1', 10,
                  'STARTED', SYSTIMESTAMP, SYSTIMESTAMP, SYSTIMESTAMP, 'LEGACY')""").update();
        ProcessObservation typedRevisionNine = new ProcessObservation("typed-9", 1,
                "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                "process-1", 9L, ProcessObservation.EventType.STARTED,
                Instant.parse("2026-08-28T08:30:00Z"),
                Instant.parse("2026-08-28T08:31:00Z"), Map.of());

        assertThatThrownBy(() -> observations.latestAppliedPosition(typedRevisionNine))
                .isInstanceOf(AppliedObservationRepository.LegacyObservationHistoryException.class)
                .hasMessageContaining("reconciliation")
                .hasMessageNotContaining("tenant-a");
    }

    @Test
    void mixedRevisionAndOccurrenceTimeOrderingModesAreRejected() {
        ProcessObservation revisioned = observation("revisioned", "tenant-a");
        observations.markApplied(observations.claim(revisioned).claim().orElseThrow());
        ProcessObservation unrevisioned = new ProcessObservation("unrevisioned", 1,
                revisioned.source(), revisioned.tenantId(), revisioned.caseId(),
                revisioned.processInstanceId(), revisioned.entityId(), null,
                ProcessObservation.EventType.COMPLETED, revisioned.engineOccurredAt().plusSeconds(1),
                revisioned.receivedAt().plusSeconds(1), Map.of());

        assertThatThrownBy(() -> observations.latestAppliedPosition(unrevisioned))
                .isInstanceOf(AppliedObservationRepository.ObservationOrderingModeException.class)
                .hasMessageContaining("mixed ordering modes")
                .hasMessageNotContaining("tenant-a");

        ProcessObservation timed = new ProcessObservation("timed", 1, revisioned.source(),
                revisioned.tenantId(), revisioned.caseId(), revisioned.processInstanceId(),
                "second-entity", null, ProcessObservation.EventType.STARTED,
                revisioned.engineOccurredAt(), revisioned.receivedAt(), Map.of());
        observations.markApplied(observations.claim(timed).claim().orElseThrow());
        ProcessObservation laterRevisioned = new ProcessObservation("later-revisioned", 1,
                timed.source(), timed.tenantId(), timed.caseId(), timed.processInstanceId(),
                timed.entityId(), 2L, ProcessObservation.EventType.COMPLETED,
                timed.engineOccurredAt().plusSeconds(1), timed.receivedAt().plusSeconds(1), Map.of());
        assertThatThrownBy(() -> observations.latestAppliedPosition(laterRevisioned))
                .isInstanceOf(AppliedObservationRepository.ObservationOrderingModeException.class)
                .hasMessageContaining("mixed ordering modes");
    }

    @Test
    void contenderWaitsForTheOwningCallerTransactionThenReturnsDuplicate() throws Exception {
        TransactionTemplate transaction = transactions();
        CountDownLatch ownerClaimed = new CountDownLatch(1);
        CountDownLatch contenderStarting = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ProcessObservation first = observation("observation-1", "tenant-a");
        ProcessObservation second = observation("observation-2", "tenant-a");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AppliedObservationRepository.ClaimResult> owner = pool.submit(() -> transaction.execute(
                    status -> {
                        AppliedObservationRepository.ClaimResult result =
                                new AppliedObservationRepository(jdbc()).claim(first);
                        ownerClaimed.countDown();
                        await(releaseOwner, "owner transaction release");
                        return result;
                    }));
            await(ownerClaimed, "owner claim");
            Future<AppliedObservationRepository.ClaimResult> contender = pool.submit(() -> transaction.execute(
                    status -> {
                        contenderStarting.countDown();
                        return new AppliedObservationRepository(jdbc()).claim(second);
                    }));
            await(contenderStarting, "contender start");

            assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseOwner.countDown();

            assertThat(owner.get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(AppliedObservationRepository.ClaimOutcome.CLAIMED);
            assertThat(contender.get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(AppliedObservationRepository.ClaimOutcome.DUPLICATE);
        } finally {
            releaseOwner.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rollbackOfOwningCallerTransactionLetsTheContenderClaimTheFact() throws Exception {
        TransactionTemplate transaction = transactions();
        CountDownLatch ownerClaimed = new CountDownLatch(1);
        CountDownLatch contenderStarting = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ProcessObservation first = observation("observation-1", "tenant-a");
        ProcessObservation second = observation("observation-2", "tenant-a");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AppliedObservationRepository.ClaimResult> owner = pool.submit(() -> transaction.execute(
                    status -> {
                        AppliedObservationRepository.ClaimResult result =
                                new AppliedObservationRepository(jdbc()).claim(first);
                        ownerClaimed.countDown();
                        await(releaseOwner, "owner transaction rollback");
                        status.setRollbackOnly();
                        return result;
                    }));
            await(ownerClaimed, "owner claim");
            Future<AppliedObservationRepository.ClaimResult> contender = pool.submit(() -> transaction.execute(
                    status -> {
                        contenderStarting.countDown();
                        return new AppliedObservationRepository(jdbc()).claim(second);
                    }));
            await(contenderStarting, "contender start");

            assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseOwner.countDown();

            assertThat(owner.get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(AppliedObservationRepository.ClaimOutcome.CLAIMED);
            assertThat(contender.get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(AppliedObservationRepository.ClaimOutcome.CLAIMED);
            assertThat(jdbc().sql("""
                    SELECT COUNT(*) FROM CM_APPLIED_ENGINE_OBSERVATION
                    WHERE FINGERPRINT_ = :fingerprint""")
                    .param("fingerprint", second.fingerprint()).query(Integer.class).single()).isEqualTo(1);
        } finally {
            releaseOwner.countDown();
            pool.shutdownNow();
        }
    }

    private TransactionTemplate transactions() {
        if (transactions == null) {
            transactionContext = springContext();
            transactions = new TransactionTemplate(
                    transactionContext.getBean(PlatformTransactionManager.class));
        }
        return transactions;
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + description, exception);
        }
    }

    private static ProcessObservation observation(String observationId, String tenantId) {
        return new ProcessObservation(observationId, 1, "operaton:embedded", tenantId,
                "case-1", "process-1", "process-1", 1L,
                ProcessObservation.EventType.STARTED, Instant.parse("2026-08-28T08:30:00Z"),
                Instant.parse("2026-08-28T08:31:00Z"), Map.of("caseDefinition", "claims"));
    }

    private record StoredRow(String status, String observationId, String source, String caseId,
                             String processInstanceId, String entityId, String eventType,
                             OffsetDateTime claimedAt, OffsetDateTime failedAt,
                             String failureDetail) {
    }
}
