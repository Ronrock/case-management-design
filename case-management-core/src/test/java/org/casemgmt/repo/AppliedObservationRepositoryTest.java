package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.observation.ProcessObservation;
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
