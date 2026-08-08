package org.casemgmt.engine;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 review round 2 (CRITICAL): claimDue must make double-processing structurally
 * impossible, not merely unlikely under this PoC's single-dispatcher-per-JVM assumption.
 *
 * <p>The original {@code SELECT ... FOR UPDATE SKIP LOCKED} claimDue never mutated any row —
 * it was a pure read — so calling it twice with no {@code markDone}/{@code markRetry} in
 * between returned the SAME rows both times, deterministically, with no concurrency required
 * to reproduce it: {@link #claimDueDoesNotReturnTheSameCommandOnASecondClaimBeforeAnyMarkCompletes}
 * fails against that code (confirmed by running it before the claim-by-UPDATE fix below existed).
 * {@link #concurrentClaimsNeverAssignTheSameCommandToBothCallers} additionally stresses a
 * genuine concurrent race between two independent {@link EngineCommandRepository} instances
 * sharing the same pooled {@code DataSource} — standing in for two dispatcher instances, or one
 * instance mid rolling-restart.
 */
class EngineCommandClaimSafetyTest extends OracleTestBase {

    private EngineCommandRepository commands;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_ENGINE_COMMAND").update();
        commands = new EngineCommandRepository(jdbc());
    }

    @Test
    void claimDueDoesNotReturnTheSameCommandOnASecondClaimBeforeAnyMarkCompletes() {
        enqueue(4);

        List<EngineCommand> first = commands.claimDue(10);
        // No markDone/markRetry/markDead call in between: simulates a second dispatcher
        // instance (or the same one mid rolling-restart) claiming before the first finishes.
        List<EngineCommand> second = commands.claimDue(10);

        assertThat(first).hasSize(4);
        assertThat(second).isEmpty();
    }

    @Test
    void concurrentClaimsNeverAssignTheSameCommandToBothCallers() throws Exception {
        int total = 40;
        enqueue(total);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<EngineCommand>> a = pool.submit(() -> claimAfter(start, 20));
            Future<List<EngineCommand>> b = pool.submit(() -> claimAfter(start, 20));
            start.countDown();

            Set<String> idsA = a.get(30, TimeUnit.SECONDS).stream()
                    .map(EngineCommand::id).collect(Collectors.toSet());
            Set<String> idsB = b.get(30, TimeUnit.SECONDS).stream()
                    .map(EngineCommand::id).collect(Collectors.toSet());

            assertThat(idsA).doesNotContainAnyElementsOf(idsB);
            // Neither caller alone ever gets more than it asked for.
            assertThat(idsA.size()).isLessThanOrEqualTo(20);
            assertThat(idsB.size()).isLessThanOrEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<EngineCommand> claimAfter(CountDownLatch start, int limit) throws InterruptedException {
        start.await();
        // A fresh repository over the SAME pooled DataSource: each claimDue call borrows its
        // own physical connection, exactly like two independent dispatcher instances would.
        return new EngineCommandRepository(jdbc()).claimDue(limit);
    }

    @Test
    void staleClaimsBecomeReclaimableOnceTheLeaseExpires() {
        enqueue(1);
        assertThat(commands.claimDue(10)).hasSize(1);

        // Simulate a dispatcher that claimed the row and then died before finishing: back-date
        // CLAIMED_AT_ past the lease instead of calling markDone/markRetry/markDead — nothing
        // else ever un-claims a row otherwise.
        jdbc().sql("UPDATE CM_ENGINE_COMMAND SET CLAIMED_AT_ = SYSTIMESTAMP - INTERVAL '10' MINUTE")
                .update();

        assertThat(commands.claimDue(10)).hasSize(1);
    }

    @Test
    void freshlyClaimedCommandsAreNotReclaimedBeforeTheLeaseExpires() {
        enqueue(1);
        assertThat(commands.claimDue(10)).hasSize(1);

        // No back-dating this time: the claim is still fresh, so it must NOT come back.
        assertThat(commands.claimDue(10)).isEmpty();
    }

    private void enqueue(int n) {
        for (int i = 0; i < n; i++) {
            commands.enqueue(new EngineCommand("cmd-" + UUID.randomUUID(), "eng-a:1",
                    EngineCommand.Type.CREATE_TASK, Map.of("planItemId", "pi-" + i, "name", "T"),
                    "PENDING", 0, OffsetDateTime.now(), null));
        }
    }
}
