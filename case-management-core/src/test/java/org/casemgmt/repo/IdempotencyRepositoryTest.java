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
}
