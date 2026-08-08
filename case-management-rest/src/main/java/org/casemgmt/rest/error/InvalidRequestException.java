package org.casemgmt.rest.error;

import java.util.Collection;

/**
 * A malformed request value the controller parsed itself, mapped to 400 {@code invalid-request}.
 *
 * <p><b>Why this exists</b> (Task 24 fix round 1, review finding I5). The first cut called
 * {@code CasePriority.valueOf(request.priority())} directly, so {@code {"priority":"URGENT"}}
 * raised a bare {@code IllegalArgumentException} — which {@code ProblemDetailHandler}
 * deliberately does not map (core throws that type from several non-client-shaped sites; see
 * {@link MalformedETagException}) — and shipped as an opaque 500. That is precisely the defect
 * shape carried finding C2 was raised to fix, reintroduced one layer up.
 *
 * <p>{@link #enumValue} is the standard way to parse an enum at this boundary: it names the
 * offending value AND the legal ones, so a client can correct the request without reading the
 * schema.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    /**
     * Parses {@code raw} as a value of {@code type}, or throws a mapped 400 naming every legal
     * value. Returns {@code fallback} when {@code raw} is absent, so an optional field stays
     * optional.
     */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String field, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Invalid value '" + raw + "' for " + field
                    + "; legal values are " + names(type));
        }
    }

    private static <E extends Enum<E>> Collection<String> names(Class<E> type) {
        return java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }
}
