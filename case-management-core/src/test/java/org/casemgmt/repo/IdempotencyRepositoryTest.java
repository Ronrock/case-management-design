package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.error.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IdempotencyRepositoryTest extends OracleTestBase {

    private IdempotencyRepository repo;

    // No per-class DELETE here: OracleTestBase's own @BeforeEach already wipes every
    // CM_ table (including CM_IDEMPOTENCY_KEY) before each test method runs.
    @BeforeEach
    void setUp() {
        repo = new IdempotencyRepository(jdbc());
    }

    @Test
    void firstCallProceeds() {
        assertThat(repo.begin("k1", "POST /cases", "hash-a")).isEmpty();
    }

    @Test
    void replayWithTheSameHashReturnsTheStoredResponse() {
        repo.begin("k1", "POST /cases", "hash-a");
        repo.complete("k1", "POST /cases", 201, "{\"id\":\"eng-a:1\"}");

        var replay = repo.begin("k1", "POST /cases", "hash-a");

        assertThat(replay).isPresent();
        assertThat(replay.get().status()).isEqualTo(201);
        assertThat(replay.get().body()).contains("eng-a:1");
    }

    @Test
    void sameKeyWithADifferentPayloadConflicts() {
        repo.begin("k1", "POST /cases", "hash-a");
        repo.complete("k1", "POST /cases", 201, "{}");

        assertThatThrownBy(() -> repo.begin("k1", "POST /cases", "hash-b"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void anInFlightDuplicateConflicts() {
        repo.begin("k1", "POST /cases", "hash-a");   // never completed

        assertThatThrownBy(() -> repo.begin("k1", "POST /cases", "hash-a"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("in progress");
    }

    @Test
    void keysAreScopedPerOperation() {
        repo.begin("k1", "POST /cases", "hash-a");
        assertThat(repo.begin("k1", "POST /cases/bulk", "hash-a")).isEmpty();
    }

    // Review fix (Important, I5): a caller that begin()s and then crashes or throws before
    // calling complete() must not wedge every retry of that key for the full 48h purge
    // window. Simulates an abandoned claim by backdating CREATED_AT_ past the reclaim
    // lease directly (the public API has no way to create an old row instantly), then
    // proves a same-key retry reclaims it rather than conflicting.
    @Test
    void anAbandonedInProgressRowIsReclaimedAfterTheLeaseExpires() {
        repo.begin("k1", "POST /cases", "hash-a");   // never completed
        jdbc().sql("""
                UPDATE CM_IDEMPOTENCY_KEY SET CREATED_AT_ = SYSTIMESTAMP - NUMTODSINTERVAL(10, 'MINUTE')
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("key", "k1").param("scope", "POST /cases").update();

        assertThat(repo.begin("k1", "POST /cases", "hash-a")).isEmpty();
    }

    /**
     * Final whole-branch review, Important 4 (release half). A released claim must be
     * indistinguishable from a key never used — including for a retry carrying a DIFFERENT
     * payload hash, which is the whole point: the client's first attempt was rejected for being
     * wrong, so the retry that matters is the corrected one. A status flip instead of a DELETE
     * would leave the old hash behind and turn that corrected retry into the same
     * {@code IdempotencyConflictException} this exists to prevent.
     */
    @Test
    void aReleasedClaimLetsTheSameKeyBeRetriedWithACorrectedPayload() {
        repo.begin("k1", "POST /cases", "hash-a");

        assertThat(repo.release("k1", "POST /cases")).isTrue();

        assertThat(repo.begin("k1", "POST /cases", "hash-b")).isEmpty();
    }

    /**
     * The guard half of the same finding: {@code release} must never destroy a real, replayable
     * response. Without the {@code RESPONSE_STATUS_ = 0} predicate a stray release would delete
     * a completed row and silently re-enable a duplicate create.
     */
    @Test
    void releaseCannotDeleteAClaimThatAlreadyCarriesAStoredResponse() {
        repo.begin("k1", "POST /cases", "hash-a");
        repo.complete("k1", "POST /cases", 201, "{\"id\":\"eng-a:1\"}");

        assertThat(repo.release("k1", "POST /cases")).isFalse();

        var replay = repo.begin("k1", "POST /cases", "hash-a");
        assertThat(replay).isPresent();
        assertThat(replay.get().body()).contains("eng-a:1");
    }

    /**
     * Final whole-branch review, Important 4 (second half): {@code complete} ignored its
     * affected-row count and carried no {@code WHERE RESPONSE_STATUS_ = 0} guard, so a
     * duplicate or late {@code complete()} — a caller whose lease expired and whose claim was
     * reclaimed, finishing anyway — silently overwrote the response another caller had already
     * stored, and every replay afterwards served the wrong body.
     *
     * <p>Attribution, not just outcome: the second {@code complete} carries a status AND a body
     * that could not be confused with the first, and both are checked on the replay.
     */
    @Test
    void aLateCompleteCannotOverwriteAResponseSomeoneElseAlreadyStored() {
        repo.begin("k1", "POST /cases", "hash-a");
        assertThat(repo.complete("k1", "POST /cases", 201, "{\"id\":\"winner\"}")).isTrue();

        assertThat(repo.complete("k1", "POST /cases", 500, "{\"id\":\"latecomer\"}")).isFalse();

        var replay = repo.begin("k1", "POST /cases", "hash-a");
        assertThat(replay).isPresent();
        assertThat(replay.get().status()).isEqualTo(201);
        assertThat(replay.get().body()).contains("winner").doesNotContain("latecomer");
    }
}
