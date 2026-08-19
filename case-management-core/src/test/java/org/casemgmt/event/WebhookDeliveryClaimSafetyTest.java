package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link WebhookRepository#claimDueDeliveries} makes double-processing structurally
 * impossible, the same way {@code EngineCommandClaimSafetyTest} proves it for the engine command
 * outbox (Task 13 review round 2) — this outbox reuses that exact claim-by-UPDATE shape (see
 * {@code WebhookRepository.claimDueDeliveries}'s Javadoc) instead of the brief's original {@code
 * SELECT ... FOR UPDATE SKIP LOCKED}, which never mutates a row and so releases its lock the
 * instant the SELECT completes on this codebase's autocommit-pooled connections — long before the
 * outbound HTTP call — letting two dispatchers claim and deliver the same row.
 */
class WebhookDeliveryClaimSafetyTest extends OracleTestBase {

    private WebhookRepository webhooks;
    private EventRepository events;

    @BeforeEach
    void setUp() {
        webhooks = new WebhookRepository(jdbc());
        events = new EventRepository(jdbc());
        webhooks.insert("w-1", "t1", "http://localhost:1/hook", List.of("*"), "hash", 5);
    }

    @Test
    void claimDueDeliveriesDoesNotReturnTheSameDeliveryOnASecondClaimBeforeAnyMarkCompletes() {
        enqueue(4);

        List<WebhookRepository.Delivery> first = webhooks.claimDueDeliveries(10);
        // No markDelivered/markRetry/markDead call in between: simulates a second dispatcher
        // instance (or the same one mid rolling-restart) claiming before the first finishes.
        List<WebhookRepository.Delivery> second = webhooks.claimDueDeliveries(10);

        assertThat(first).hasSize(4);
        assertThat(second).isEmpty();
    }

    @Test
    void concurrentClaimsNeverAssignTheSameDeliveryToBothCallers() throws Exception {
        int total = 40;
        enqueue(total);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<WebhookRepository.Delivery>> a = pool.submit(() -> claimAfter(start, 20));
            Future<List<WebhookRepository.Delivery>> b = pool.submit(() -> claimAfter(start, 20));
            start.countDown();

            Set<String> idsA = a.get(30, TimeUnit.SECONDS).stream()
                    .map(WebhookRepository.Delivery::id).collect(Collectors.toSet());
            Set<String> idsB = b.get(30, TimeUnit.SECONDS).stream()
                    .map(WebhookRepository.Delivery::id).collect(Collectors.toSet());

            assertThat(idsA).doesNotContainAnyElementsOf(idsB);
            assertThat(idsA.size()).isLessThanOrEqualTo(20);
            assertThat(idsB.size()).isLessThanOrEqualTo(20);
            assertThat(idsA.size() + idsB.size()).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<WebhookRepository.Delivery> claimAfter(CountDownLatch start, int limit) throws InterruptedException {
        start.await();
        // A fresh repository over the SAME pooled DataSource: each claimDueDeliveries call
        // borrows its own physical connection, exactly like two independent dispatcher
        // instances would.
        return new WebhookRepository(jdbc()).claimDueDeliveries(limit);
    }

    @Test
    void staleClaimsBecomeReclaimableOnceTheLeaseExpires() {
        enqueue(1);
        assertThat(webhooks.claimDueDeliveries(10)).hasSize(1);

        // Simulate a dispatcher that claimed the row and then died before finishing: back-date
        // CLAIMED_AT_ past the lease instead of calling markDelivered/markRetry/markDead —
        // nothing else ever un-claims a row otherwise. Comfortably past CLAIM_LEASE (now 10
        // minutes, review round 1 — it used to be 5), not exactly on it: the boundary is compared
        // between a JVM-computed instant and Oracle's SYSTIMESTAMP, so an equal-to-the-lease
        // back-date would flip on clock skew alone.
        expireTheClaim();

        assertThat(webhooks.claimDueDeliveries(10)).hasSize(1);
    }

    /**
     * The second half of the review-round-1 duplicate-delivery fix. Sizing {@link
     * WebhookRepository#CLAIM_LEASE} above the worst-case batch makes a mid-batch reclaim
     * implausible; this guard makes it harmless if it happens anyway. Without the {@code AND
     * CLAIM_TOKEN_ = :claimToken} on the mark* statements, the original claimer's late mark
     * silently overwrote the reclaimer's outcome — a just-dead-lettered row reset to RETRYING,
     * or the reverse.
     */
    @Test
    void aMarkFromAClaimerThatLostItsLeaseWritesNothingAndSaysSo() {
        enqueue(1);
        WebhookRepository.Delivery first = webhooks.claimDueDeliveries(10).get(0);

        expireTheClaim();
        WebhookRepository.Delivery reclaimed = webhooks.claimDueDeliveries(10).get(0);
        assertThat(reclaimed.id()).isEqualTo(first.id());
        assertThat(reclaimed.claimToken()).isNotEqualTo(first.claimToken());

        // The reclaimer decides the row's outcome.
        assertThat(webhooks.markDead(reclaimed.id(), reclaimed.claimToken(), 500, "gave up")).isTrue();
        assertThat(status()).isEqualTo("DEAD");

        // The original claimer finally finishes and tries to mark it delivered: refused.
        assertThat(webhooks.markDelivered(first.id(), first.claimToken(), 200)).isFalse();
        assertThat(status()).isEqualTo("DEAD");
        assertThat(webhooks.markRetry(first.id(), first.claimToken(), null, "late",
                OffsetDateTime.now())).isFalse();
        assertThat(status()).isEqualTo("DEAD");
    }

    /**
     * The arithmetic review round 1 found had drifted: 50 rows x 10s = 500s of worst-case batch
     * against a 300s lease made a second dispatcher reclaiming the tail of a batch the first was
     * still working through the EXPECTED behaviour for a hung subscriber, not a crash-only one.
     * Asserted as a relationship between the three constants, and enforced at construction, so it
     * cannot silently drift again when any one of them is next tuned.
     */
    @Test
    void theClaimLeaseOutlastsTheWorstCaseBatchAndOversizedSettingsAreRejected() {
        Duration worstCaseBatch = WebhookDispatcher.CONNECT_TIMEOUT
                .plus(WebhookDispatcher.RESPONSE_TIMEOUT)
                .multipliedBy(WebhookRepository.MAX_CLAIM_BATCH);
        assertThat(worstCaseBatch).isLessThan(WebhookRepository.CLAIM_LEASE);

        assertThatThrownBy(() -> new WebhookDispatcher(webhooks, events, id -> "s",
                Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLAIM_LEASE");

        assertThatThrownBy(() -> webhooks.claimDueDeliveries(WebhookRepository.MAX_CLAIM_BATCH + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLAIM_LEASE");
    }

    private void expireTheClaim() {
        jdbc().sql("UPDATE CM_WEBHOOK_DELIVERY SET CLAIMED_AT_ = SYSTIMESTAMP - INTERVAL '60' MINUTE")
                .update();
    }

    private String status() {
        return jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY").query(String.class).single();
    }

    private void enqueue(int n) {
        for (int i = 0; i < n; i++) {
            long seq = events.append(new CaseEvent(CaseIds.newId(), "eng-a", "case.created",
                    "eng-a:" + i, "t1", OffsetDateTime.now(), Map.of("i", i)));
            webhooks.enqueueDelivery(CaseIds.newId(), "w-1", seq);
        }
    }
}
