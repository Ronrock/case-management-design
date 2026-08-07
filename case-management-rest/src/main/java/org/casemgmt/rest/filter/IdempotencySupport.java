package org.casemgmt.rest.filter;

import org.casemgmt.repo.IdempotencyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
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
        Optional<IdempotencyRepository.StoredResponse> stored = repo.begin(key, scope, hash);
        if (stored.isPresent()) {
            return new Result<>(deserializer.apply(stored.get().body()), stored.get().status(), true);
        }
        T value = operation.get();
        repo.complete(key, scope, successStatus, serializer.apply(value));
        return new Result<>(value, successStatus, false);
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
