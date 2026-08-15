package org.casemgmt.rest.filter;

import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.FormValidationException;
import org.casemgmt.error.IdempotencyConflictException;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.error.PreconditionRequiredException;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.rest.error.ForbiddenException;
import org.casemgmt.rest.error.InvalidRequestException;
import org.casemgmt.rest.error.MalformedETagException;
import org.casemgmt.rest.error.PreconditionFailedException;
import org.casemgmt.rules.PlanModelLoopException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * <p><b>Models the in-progress claim, not just the stored response</b> (final whole-branch
     * review, Important 4). The earlier version of this fake returned {@code Optional.empty()}
     * for any key it had no stored RESPONSE for — so a key that had been claimed and never
     * completed looked, to this fake, exactly like a key never used. That made the wedge this
     * class exists to prevent structurally invisible to every test here: the claim leak could
     * not be reproduced against it. It now mirrors {@code IdempotencyRepository.begin}'s three
     * real branches — a completed row replays, an in-flight row with a matching hash conflicts
     * ("still in progress"), and a differing hash conflicts ("different payload") — so
     * {@link #aClientErrorReleasesTheClaimSoACorrectedRetrySucceeds} genuinely fails without the
     * release rather than passing on a fake that forgives everything.
     */
    static class FakeIdempotencyRepository extends IdempotencyRepository {
        record BeginCall(String key, String scope, String hash) {}

        final List<BeginCall> beginCalls = new ArrayList<>();
        final List<String> completedKeys = new ArrayList<>();
        final List<String> releasedKeys = new ArrayList<>();
        final Map<String, StoredResponse> stored = new HashMap<>();
        private final Map<String, String> inProgressHashes = new HashMap<>();
        private final Map<String, String> inProgressTokens = new HashMap<>();

        FakeIdempotencyRepository() {
            super(null);
        }

        @Override
        public BeginResult begin(String key, String scope, String requestHash) {
            beginCalls.add(new BeginCall(key, scope, requestHash));
            String row = key + "|" + scope;
            StoredResponse completed = stored.get(row);
            if (completed != null) {
                return BeginResult.replay(completed);
            }
            String claimedHash = inProgressHashes.get(row);
            if (claimedHash != null) {
                throw new IdempotencyConflictException(claimedHash.equals(requestHash)
                        ? "A request with idempotency key " + key + " is still in progress"
                        : "Idempotency key " + key + " was already used with a different payload");
            }
            inProgressHashes.put(row, requestHash);
            String token = "claim-" + (beginCalls.size());
            inProgressTokens.put(row, token);
            return BeginResult.claimed(token);
        }

        @Override
        public boolean complete(String key, String scope, String claimToken, int status,
                                String responseJson) {
            completedKeys.add(key);
            String row = key + "|" + scope;
            if (!claimToken.equals(inProgressTokens.get(row))) {
                return false;
            }
            inProgressHashes.remove(key + "|" + scope);
            inProgressTokens.remove(key + "|" + scope);
            stored.put(key + "|" + scope, new StoredResponse(status, responseJson));
            return true;
        }

        @Override
        public boolean release(String key, String scope, String claimToken) {
            releasedKeys.add(key);
            String row = key + "|" + scope;
            if (!claimToken.equals(inProgressTokens.get(row))) {
                return false;
            }
            inProgressTokens.remove(row);
            return inProgressHashes.remove(row) != null;
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
    void replayOfANoBodyResponseDoesNotCallTheDeserializerWithNull() {
        repo.stored.put("k1|DELETE /thing", new IdempotencyRepository.StoredResponse(204, null));

        var result = support.execute("k1", "DELETE /thing", "",
                () -> { throw new AssertionError("operation must not run on replay"); },
                s -> { throw new AssertionError("no-body replay must not deserialize null"); },
                v -> null,
                204);

        assertThat(result.replayed()).isTrue();
        assertThat(result.status()).isEqualTo(204);
        assertThat(result.value()).isNull();
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

    // ---------------------------------------------------------------------------------------
    // Final whole-branch review, Important 4: begin() commits an IN_PROGRESS row before the
    // operation runs; if the operation then threw, nothing released it. The build recorded the
    // double-execute half of that lease trade-off; this failure half — which fires on ORDINARY
    // validation errors, not crashes — was recorded nowhere and is the more common path.
    // ---------------------------------------------------------------------------------------

    /**
     * The whole client-visible symptom, end to end through this class: a 422 form violation,
     * then a corrected retry on the SAME key. Without the release the retry never reaches the
     * operation at all — {@code begin} sees the still-claimed row and throws
     * {@code IdempotencyConflictException}.
     */
    @Test
    void aClientErrorReleasesTheClaimSoACorrectedRetrySucceeds() {
        assertThatThrownBy(() -> support.execute("k1", "POST /cases", "{\"bad\":1}",
                () -> { throw new FormValidationException(
                        List.of(new FormValidationException.Violation("/x", "required"))); },
                s -> s, v -> v, 201))
                .isInstanceOf(FormValidationException.class);

        // The client symptom, asserted directly: the corrected retry must reach the operation.
        // Without the release this line throws IdempotencyConflictException("already used with
        // a different payload") — the fake models begin()'s real three branches, so the wedge
        // reproduces here rather than being forgiven by an over-permissive stub.
        var retry = support.execute("k1", "POST /cases", "{\"good\":1}",
                () -> "created", s -> s, v -> v, 201);

        assertThat(retry.value()).isEqualTo("created");
        assertThat(retry.replayed()).isFalse();
        assertThat(repo.releasedKeys).containsExactly("k1");
    }

    /**
     * Each of the client-error types {@code ProblemDetailHandler} maps to a 4xx releases, plus
     * Spring's own {@link org.springframework.web.ErrorResponse}-carrying 4xx. Enumerated rather
     * than sampled: this list IS the contract, and a type silently dropped from
     * {@code releasesClaim} would otherwise go unnoticed.
     */
    @Test
    void everyClientErrorShapeReleasesTheClaim() {
        List<RuntimeException> clientErrors = List.of(
                new NotFoundException("Case", "c-1"),
                new CaseConflictException("illegal-state", "no", List.of()),
                new OptimisticLockException("Case", "c-1", 3L),
                new PreconditionRequiredException(),
                new FormValidationException(List.of()),
                new InvalidCaseDefinitionException("k", "bad"),
                new MalformedETagException("bad etag", new IllegalArgumentException("x")),
                new PreconditionFailedException("gone"),
                new InvalidRequestException("bad enum"),
                new ForbiddenException("Case c-1 is not visible"),
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "spring's own"));

        for (RuntimeException error : clientErrors) {
            FakeIdempotencyRepository fresh = new FakeIdempotencyRepository();
            assertThatThrownBy(() -> new IdempotencySupport(fresh)
                    .execute("k", "POST /cases", "{}", () -> { throw error; }, s -> s, v -> v, 201))
                    .isSameAs(error);
            assertThat(fresh.releasedKeys)
                    .as(error.getClass().getSimpleName() + " must release the claim")
                    .containsExactly("k");
            assertThat(fresh.completedKeys).isEmpty();
        }
    }

    /**
     * The deliberate asymmetry, and the reason this is not simply "release on any exception".
     * A server fault says nothing about whether the operation's side effects landed — an engine
     * call may have gone out, an outbox row may have committed — so the claim is left to expire
     * on the repository's lease instead of letting a retry repeat them. Also the fail-closed
     * proof: an exception type {@code releasesClaim} does not recognise keeps the claim.
     */
    @Test
    void aServerErrorKeepsTheClaimSoARetryCannotRepeatUnknownSideEffects() {
        List<RuntimeException> serverErrors = List.of(
                new PlanModelLoopException("eng-a:1", 20),
                new IllegalStateException("something unrecognised blew up"),
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "spring's own 500"));

        for (RuntimeException error : serverErrors) {
            FakeIdempotencyRepository fresh = new FakeIdempotencyRepository();
            assertThatThrownBy(() -> new IdempotencySupport(fresh)
                    .execute("k", "POST /cases", "{}", () -> { throw error; }, s -> s, v -> v, 201))
                    .isSameAs(error);
            assertThat(fresh.releasedKeys)
                    .as(error.getClass().getSimpleName() + " must NOT release the claim")
                    .isEmpty();
        }
    }

    /**
     * A failing operation must never record a response either — a 4xx that stored a body would
     * make every later retry replay the failure as though it had succeeded.
     */
    @Test
    void aFailedOperationStoresNoResponse() {
        assertThatThrownBy(() -> support.execute("k1", "POST /cases", "{}",
                () -> { throw new NotFoundException("Case", "c-1"); }, s -> s, v -> v, 201))
                .isInstanceOf(NotFoundException.class);

        assertThat(repo.completedKeys).isEmpty();
        assertThat(repo.stored).isEmpty();
    }

    /**
     * Corrective round: {@code execute} discarded {@code complete()}'s boolean, so a stale claim
     * could look successful to the caller even though the response store refused it.
     *
     * <p>Attribution: the fake reports the lost race by returning false from {@code complete}
     * exactly as the guarded SQL does, and the assertion pins the message to this condition's own
     * wording — a generic {@code IdempotencyConflictException} could equally come from
     * {@code begin}, which is a different condition entirely.
     *
     * See {@code IdempotencySupport}'s Javadoc.
     */
    @Test
    void aLostClaimIsReportedRatherThanSilentlyReturningOK() {
        FakeIdempotencyRepository reclaimed = new FakeIdempotencyRepository() {
            @Override
            public boolean complete(String key, String scope, String claimToken, int status,
                                    String responseJson) {
                super.complete(key, scope, claimToken, status, responseJson);
                return false;   // someone else finalised this row first
            }
        };

        assertThatThrownBy(() -> new IdempotencySupport(reclaimed)
                .execute("k1", "POST /cases", "{}", () -> "created", s -> s, v -> v, 201))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("is no longer owned by this request");
    }

    /**
     * Negative control for the whole block: a SUCCESSFUL operation must not release anything.
     * Without this, "the claim was released" is satisfiable by releasing unconditionally, which
     * would disable idempotency altogether while every test above still passed.
     */
    @Test
    void aSuccessfulOperationReleasesNothingAndStillReplays() {
        support.execute("k1", "POST /cases", "{}", () -> "created", s -> s, v -> v, 201);

        assertThat(repo.releasedKeys).isEmpty();

        var replay = support.execute("k1", "POST /cases", "{}",
                () -> { throw new AssertionError("operation must not run on replay"); },
                s -> s, v -> v, 201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo("created");
    }
}
