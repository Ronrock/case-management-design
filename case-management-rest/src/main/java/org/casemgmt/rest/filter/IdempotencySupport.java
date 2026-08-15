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
import org.springframework.web.ErrorResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Transaction boundary (review finding, Important — I5): {@code execute} deliberately does
 * NOT wrap {@code repo.begin}, {@code operation.get()} and {@code repo.complete} in one
 * enclosing {@code @Transactional} (this class carries no such annotation, and callers must
 * not add one around the whole call). {@link IdempotencyRepository#begin} exists to be a
 * cross-request mutex: its INSERT must commit and become visible to a concurrent duplicate
 * request BEFORE {@code operation.get()} runs, or a second caller racing the same key would
 * never see the in-progress row and would attempt the work too — exactly what this class
 * exists to prevent. Each of {@code begin}/{@code complete} auto-commits as its own
 * statement as long as neither this method nor its caller opens a surrounding transaction
 * across the whole call; {@code operation.get()} is free to run its own, separate
 * {@code @Transactional} business logic in between (that is the expected shape).
 *
 * <p><b>Failure releases the claim, for client errors only</b> (final whole-branch review,
 * Important 4). With {@code begin}'s row already committed, an operation
 * that threw left the key claimed with nothing to clean it up, so an ordinary 400/404/422
 * wedged that {@code Idempotency-Key} for the whole {@code LEASE_MINUTES} window — a retry with
 * a corrected payload got 409 "already used with a different payload", and a retry with the
 * original got 409 "still in progress". See {@link #releasesClaim} for exactly which failures
 * release and — just as deliberately — which do not.
 *
 * <p><b>A lost claim on {@code complete} is reported, not ignored</b> (corrective round).
 * {@code repo.complete} returns whether it was the call that actually stored the response;
 * discarding that boolean left a stale owner-token or already-finalised row silently unobserved.
 * A caller would return its own success while the replay store held a different outcome or no
 * longer accepted that caller's claim.
 */
public class IdempotencySupport {

    public record Result<T>(T value, int status, boolean replayed) {}

    private final IdempotencyRepository repo;

    public IdempotencySupport(IdempotencyRepository repo) {
        this.repo = repo;
    }

    /**
     * Runs the operation once per (key, scope). A retry with the same payload replays
     * the original response (status AND body — review fix, I1: the original draft read the
     * stored status back from the database in {@code IdempotencyRepository.replay} and then
     * discarded it, always reporting the fresh-call {@code successStatus} even on replay);
     * the same key with a different payload is a client bug (409).
     *
     * <p>Header replay (review finding, I1) — deliberately NOT done here: {@code
     * CM_IDEMPOTENCY_KEY} stores only a status and a JSON body, no header map, and adding
     * one is a schema change this task does not make. For the header that matters most on a
     * replayed create, {@code Location}, a controller can already reconstruct it from the
     * replayed value's own identity (e.g. its {@code id} field) — the value itself round-trips
     * via {@code deserializer}, so nothing is actually lost for that case. {@code ETag} is
     * likewise reconstructable from the replayed value's own {@code version}, when the
     * resource carries one. Full, generic header-list replay (arbitrary headers a handler
     * set outside the body) is out of scope until a concrete caller needs it.
     */
    public <T> Result<T> execute(String key, String scope, String rawBody,
                                 Supplier<T> operation,
                                 Function<String, T> deserializer,
                                 Function<T, String> serializer,
                                 int successStatus) {
        if (key == null || key.isBlank()) {
            return new Result<>(operation.get(), successStatus, false);
        }
        String hash = sha256(rawBody);
        IdempotencyRepository.BeginResult begin = repo.begin(key, scope, hash);
        if (begin.isReplay()) {
            IdempotencyRepository.StoredResponse stored = begin.replay().orElseThrow();
            T replayed = stored.body() == null ? null : deserializer.apply(stored.body());
            return new Result<>(replayed, stored.status(), true);
        }
        String claimToken = begin.claim().token();
        T value;
        try {
            value = operation.get();
        } catch (RuntimeException e) {
            if (releasesClaim(e)) {
                repo.release(key, scope, claimToken);
            }
            throw e;
        }
        if (!repo.complete(key, scope, claimToken, successStatus, serializer.apply(value))) {
            // The guard on complete()'s UPDATE is only half the fix; this is the other half
            // (corrective round). A false here means the row was already finalised, so the stored
            // response — which every subsequent retry of this key replays — came from a different
            // execution than this one: two callers, two results, one key, and, before this, no
            // Reported as a conflict rather than swallowed: the work DID happen (the operation
            // returned), so this is not a failure the client can retry into a clean state — it is
            // a genuine collision the client has to know about, which is what 409 means here and
            // what it already means for every other idempotency conflict this class raises.
            throw new IdempotencyConflictException(
                    "Idempotency key " + key + " is no longer owned by this request; "
                            + "the operation returned but its response was not stored");
        }
        return new Result<>(value, successStatus, false);
    }

    /**
     * Whether a failed operation should hand its idempotency claim straight back (final
     * whole-branch review, Important 4).
     *
     * <p><b>Yes for client errors.</b> Every type listed here is one {@code ProblemDetailHandler}
     * maps to a 4xx, plus Spring's own {@link ErrorResponse}-carrying exceptions with a 4xx
     * status (which covers {@code ResponseStatusException} and the handler-resolved 4xx shapes).
     * The defining property is not the status number but what it implies: the request was
     * refused, the operation's own {@code @Transactional} boundary rolled back, and NOTHING was
     * written. Holding the claim after that is pure harm — the client corrects its payload,
     * retries with the same {@code Idempotency-Key}, and is told the key "was already used with
     * a different payload"; retrying the original payload is told it "is still in progress" for
     * the whole {@code LEASE_MINUTES} window. That fires on ordinary validation errors, which
     * makes it the common path.
     *
     * <p><b>No for anything else.</b> A server fault, or a type this method does not recognise,
     * says nothing about whether the operation's side effects landed — an engine call may have
     * gone out, an outbox row may have committed. Releasing the claim there would let a retry
     * repeat those effects, which is the precise thing an idempotency key is for. Such a claim
     * is instead left claimed until operational recovery or retention cleanup. This is a
     * deliberate asymmetry, not an omission.
     *
     * <p>Fails CLOSED by construction: an unrecognised exception keeps the claim. Adding a type
     * here is a decision that its throw site never leaves side effects behind.
     */
    private static boolean releasesClaim(RuntimeException e) {
        if (e instanceof NotFoundException
                || e instanceof CaseConflictException
                || e instanceof OptimisticLockException
                || e instanceof PreconditionRequiredException
                || e instanceof FormValidationException
                || e instanceof InvalidCaseDefinitionException
                || e instanceof MalformedETagException
                || e instanceof PreconditionFailedException
                || e instanceof InvalidRequestException
                || e instanceof ForbiddenException) {
            return true;
        }
        // Deliberately NOT IdempotencyConflictException: that one is thrown by repo.begin above,
        // before this try block is even entered, and if it ever reached here it would mean some
        // OTHER caller owns the claim — releasing it would be exactly wrong.
        return e instanceof ErrorResponse response
                && response.getStatusCode().is4xxClientError();
    }

    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash request body", e);
        }
    }
}
