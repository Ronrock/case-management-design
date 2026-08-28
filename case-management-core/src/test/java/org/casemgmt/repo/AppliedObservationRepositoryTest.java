package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.observation.ProcessObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Oracle-backed proof that one engine fact can own lifecycle effects only once. */
class AppliedObservationRepositoryTest extends OracleTestBase {

    private AppliedObservationRepository observations;

    @BeforeEach
    void setUp() {
        observations = new AppliedObservationRepository(jdbc());
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
    void failedObservationCanBeAtomicallyReclaimedByARedelivery() {
        ProcessObservation original = observation("observation-1", null);
        AppliedObservationRepository.ClaimResult first = observations.claim(original);
        observations.markFailed(first.claim().orElseThrow(), "projection unavailable");

        ProcessObservation retry = observation("observation-2", null);
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
        assertThat(stored.observationId()).isEqualTo("observation-2");
        assertThat(stored.failedAt()).isNotNull();
        assertThat(stored.failureDetail()).isEqualTo("projection unavailable");
        assertThatThrownBy(() -> observations.markApplied(first.claim().orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer owns");
    }

    @Test
    void concurrentClaimsHaveExactlyOneOwner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ProcessObservation first = observation("observation-1", "tenant-a");
        ProcessObservation second = observation("observation-2", "tenant-a");

        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstResult = pool.submit(() -> claimAfter(start, first));
            var secondResult = pool.submit(() -> claimAfter(start, second));
            start.countDown();

            List<AppliedObservationRepository.ClaimResult> results = List.of(
                    firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
            assertThat(results).filteredOn(AppliedObservationRepository.ClaimResult::ownsClaim).hasSize(1);
            assertThat(results).extracting(AppliedObservationRepository.ClaimResult::outcome)
                    .containsExactlyInAnyOrder(AppliedObservationRepository.ClaimOutcome.CLAIMED,
                            AppliedObservationRepository.ClaimOutcome.DUPLICATE);
        }
    }

    private AppliedObservationRepository.ClaimResult claimAfter(
            CountDownLatch start, ProcessObservation observation) throws InterruptedException {
        start.await();
        return new AppliedObservationRepository(jdbc()).claim(observation);
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
