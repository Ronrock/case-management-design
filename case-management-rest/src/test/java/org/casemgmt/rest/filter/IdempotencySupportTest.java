package org.casemgmt.rest.filter;

import org.casemgmt.repo.IdempotencyRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Review fix (Important, I2): IdempotencySupport shipped with no test coverage at all —
 * the concurrency-critical replay branch, the request hashing, and the silent no-key bypass
 * were all unexercised, which is exactly why the status-discarding bug (I1) went undetected.
 *
 * <p>Uses a fake {@link IdempotencyRepository} (a subclass overriding begin/complete, built
 * with a null JdbcClient it never touches) rather than a mock: IdempotencyRepository is a
 * concrete class with no interface, and subclassing to fake it keeps this test independent
 * of Oracle/Testcontainers — IdempotencySupport's own logic is what's under test here, not
 * the repository's SQL (that is IdempotencyRepositoryTest's job, against real Oracle).
 */
class IdempotencySupportTest {

    static class FakeIdempotencyRepository extends IdempotencyRepository {
        record BeginCall(String key, String scope, String hash) {}

        final List<BeginCall> beginCalls = new ArrayList<>();
        final List<String> completedKeys = new ArrayList<>();
        final Map<String, StoredResponse> stored = new HashMap<>();

        FakeIdempotencyRepository() {
            super(null);
        }

        @Override
        public Optional<StoredResponse> begin(String key, String scope, String requestHash) {
            beginCalls.add(new BeginCall(key, scope, requestHash));
            return Optional.ofNullable(stored.get(key + "|" + scope));
        }

        @Override
        public void complete(String key, String scope, int status, String responseJson) {
            completedKeys.add(key);
            stored.put(key + "|" + scope, new StoredResponse(status, responseJson));
        }
    }

    private final FakeIdempotencyRepository repo = new FakeIdempotencyRepository();
    private final IdempotencySupport support = new IdempotencySupport(repo);

    @Test
    void aNullKeyRunsTheOperationUnguarded() {
        var result = support.execute(null, "POST /cases", "{}",
                () -> "value", s -> s, v -> v, 201);

        assertThat(result.value()).isEqualTo("value");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(repo.beginCalls).isEmpty();
    }

    @Test
    void aBlankKeyRunsTheOperationUnguarded() {
        var result = support.execute("   ", "POST /cases", "{}",
                () -> "value", s -> s, v -> v, 201);

        assertThat(result.replayed()).isFalse();
        assertThat(repo.beginCalls).isEmpty();
    }

    @Test
    void firstCallExecutesTheOperationAndStoresTheResult() {
        var result = support.execute("k1", "POST /cases", "{\"a\":1}",
                () -> "created", s -> s, v -> v, 201);

        assertThat(result.value()).isEqualTo("created");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(repo.completedKeys).containsExactly("k1");
        assertThat(repo.stored.get("k1|POST /cases").body()).isEqualTo("created");
    }

    // Review fix (I1's regression test): a replay must surface the STORED status, not the
    // successStatus this call happens to pass — and must not re-run the operation.
    @Test
    void replayReturnsTheStoredStatusAndBodyWithoutRerunningTheOperation() {
        repo.stored.put("k1|POST /cases", new IdempotencyRepository.StoredResponse(201, "stored-body"));

        var result = support.execute("k1", "POST /cases", "{\"a\":1}",
                () -> { throw new AssertionError("operation must not run on replay"); },
                s -> s, v -> v, 500);

        assertThat(result.replayed()).isTrue();
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.value()).isEqualTo("stored-body");
    }

    @Test
    void identicalRequestBodiesHashIdentically() {
        support.execute("k1", "POST /cases", "same-body", () -> "v", s -> s, v -> v, 201);
        support.execute("k2", "POST /cases", "same-body", () -> "v", s -> s, v -> v, 201);

        assertThat(repo.beginCalls.get(0).hash()).isEqualTo(repo.beginCalls.get(1).hash());
    }

    @Test
    void differentRequestBodiesHashDifferently() {
        support.execute("k1", "POST /cases", "body-a", () -> "v", s -> s, v -> v, 201);
        support.execute("k2", "POST /cases", "body-b", () -> "v", s -> s, v -> v, 201);

        assertThat(repo.beginCalls.get(0).hash()).isNotEqualTo(repo.beginCalls.get(1).hash());
    }
}
