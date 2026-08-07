package org.casemgmt.rest.filter;

import org.casemgmt.repo.IdempotencyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class IdempotencySupport {

    public record Result<T>(T value, boolean replayed) {}

    private final IdempotencyRepository repo;

    public IdempotencySupport(IdempotencyRepository repo) {
        this.repo = repo;
    }

    /**
     * Runs the operation once per (key, scope). A retry with the same payload replays
     * the original response; the same key with a different payload is a client bug (409).
     */
    public <T> Result<T> execute(String key, String scope, String rawBody,
                                 Supplier<T> operation,
                                 Function<String, T> deserializer,
                                 Function<T, String> serializer,
                                 int successStatus) {
        if (key == null || key.isBlank()) {
            return new Result<>(operation.get(), false);
        }
        String hash = sha256(rawBody);
        Optional<IdempotencyRepository.StoredResponse> stored = repo.begin(key, scope, hash);
        if (stored.isPresent()) {
            return new Result<>(deserializer.apply(stored.get().body()), true);
        }
        T value = operation.get();
        repo.complete(key, scope, successStatus, serializer.apply(value));
        return new Result<>(value, false);
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
