package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_WEBHOOK_SUB", "CM_EVENT")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
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
        // nothing else ever un-claims a row otherwise.
        jdbc().sql("UPDATE CM_WEBHOOK_DELIVERY SET CLAIMED_AT_ = SYSTIMESTAMP - INTERVAL '10' MINUTE")
                .update();

        assertThat(webhooks.claimDueDeliveries(10)).hasSize(1);
    }

    @Test
    void freshlyClaimedDeliveriesAreNotReclaimedBeforeTheLeaseExpires() {
        enqueue(1);
        assertThat(webhooks.claimDueDeliveries(10)).hasSize(1);

        // No back-dating this time: the claim is still fresh, so it must NOT come back.
        assertThat(webhooks.claimDueDeliveries(10)).isEmpty();
    }

    private void enqueue(int n) {
        for (int i = 0; i < n; i++) {
            long seq = events.append(new CaseEvent(CaseIds.newId(), "eng-a", "case.created",
                    "eng-a:" + i, "t1", OffsetDateTime.now(), Map.of("i", i)));
            webhooks.enqueueDelivery(CaseIds.newId(), "w-1", seq);
        }
    }
}
