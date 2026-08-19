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
        IdempotencyRepository.BeginResult begin = repo.begin("k1", "POST /cases", "hash-a");

        assertThat(begin.replay()).isEmpty();
        assertThat(begin.claim().token()).isNotBlank();
    }

    @Test
    void replayWithTheSameHashReturnsTheStoredResponse() {
        String token = repo.begin("k1", "POST /cases", "hash-a").claim().token();
        repo.complete("k1", "POST /cases", token, 201, "{\"id\":\"eng-a:1\"}");

        var replay = repo.begin("k1", "POST /cases", "hash-a");

        assertThat(replay.replay()).isPresent();
        assertThat(replay.replay().orElseThrow().status()).isEqualTo(201);
        assertThat(replay.replay().orElseThrow().body()).contains("eng-a:1");
    }

    @Test
    void sameKeyWithADifferentPayloadConflicts() {
        String token = repo.begin("k1", "POST /cases", "hash-a").claim().token();
        repo.complete("k1", "POST /cases", token, 201, "{}");

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
        IdempotencyRepository.BeginResult begin = repo.begin("k1", "POST /cases/bulk", "hash-a");
        assertThat(begin.replay()).isEmpty();
        assertThat(begin.claim().token()).isNotBlank();
    }

    @Test
    void anOldInProgressRowIsNotAutomaticallyReclaimedByADuplicateRequest() {
        repo.begin("k1", "POST /cases", "hash-a");   // never completed
        jdbc().sql("""
                UPDATE CM_IDEMPOTENCY_KEY SET CREATED_AT_ = SYSTIMESTAMP - NUMTODSINTERVAL(10, 'MINUTE')
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("key", "k1").param("scope", "POST /cases").update();

        assertThatThrownBy(() -> repo.begin("k1", "POST /cases", "hash-a"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("in progress");
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
        String token = repo.begin("k1", "POST /cases", "hash-a").claim().token();

        assertThat(repo.release("k1", "POST /cases", token)).isTrue();

        assertThat(repo.begin("k1", "POST /cases", "hash-b").replay()).isEmpty();
    }

    /**
     * The guard half of the same finding: {@code release} must never destroy a real, replayable
     * response. Without the {@code RESPONSE_STATUS_ = 0} predicate a stray release would delete
     * a completed row and silently re-enable a duplicate create.
     */
    @Test
    void releaseCannotDeleteAClaimThatAlreadyCarriesAStoredResponse() {
        String token = repo.begin("k1", "POST /cases", "hash-a").claim().token();
        repo.complete("k1", "POST /cases", token, 201, "{\"id\":\"eng-a:1\"}");

        assertThat(repo.release("k1", "POST /cases", token)).isFalse();

        var replay = repo.begin("k1", "POST /cases", "hash-a");
        assertThat(replay.replay()).isPresent();
        assertThat(replay.replay().orElseThrow().body()).contains("eng-a:1");
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
        String token = repo.begin("k1", "POST /cases", "hash-a").claim().token();
        assertThat(repo.complete("k1", "POST /cases", token, 201, "{\"id\":\"winner\"}")).isTrue();

        assertThat(repo.complete("k1", "POST /cases", token, 500, "{\"id\":\"latecomer\"}")).isFalse();

        var replay = repo.begin("k1", "POST /cases", "hash-a");
        assertThat(replay.replay()).isPresent();
        assertThat(replay.replay().orElseThrow().status()).isEqualTo(201);
        assertThat(replay.replay().orElseThrow().body()).contains("winner").doesNotContain("latecomer");
    }

    @Test
    void staleClaimTokensCannotCompleteOrReleaseCurrentWork() {
        String token = repo.begin("k1", "POST /cases", "hash-a").claim().token();
        String stale = token + "-stale";

        assertThat(repo.complete("k1", "POST /cases", stale, 201, "{\"id\":\"late\"}")).isFalse();
        assertThat(repo.release("k1", "POST /cases", stale)).isFalse();

        assertThat(repo.complete("k1", "POST /cases", token, 201, "{\"id\":\"winner\"}")).isTrue();
        assertThat(repo.begin("k1", "POST /cases", "hash-a").replay().orElseThrow().body())
                .contains("winner");
    }
}
