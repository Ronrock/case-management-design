package org.casemgmt.rest.filter;

import org.casemgmt.error.PreconditionRequiredException;
import org.casemgmt.rest.error.MalformedETagException;
import org.casemgmt.rest.error.PreconditionFailedException;

import java.util.OptionalLong;
import java.util.function.Supplier;

public final class ETagSupport {

    private ETagSupport() {}

    /** ETag is the row's VERSION_ rendered as a strong tag (spec §6.3). */
    public static String format(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Parses a single strong/weak numeric entity-tag, e.g. {@code "17"} or {@code W/"17"}.
     * Narrower than {@link #parseIfMatch(String)}: it has no notion of the RFC 7232 §3.1
     * wildcard ({@code *}) or a comma-separated tag list, and throws on either. Kept for
     * callers that already know they hold exactly one strong numeric tag; anything reading
     * a real {@code If-Match} header off the wire should use {@link #parseIfMatch(String)}.
     */
    public static long parse(String ifMatchHeader) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new PreconditionRequiredException();
        }
        return parseTag(ifMatchHeader.trim());
    }

    /**
     * Parses a full {@code If-Match} header per RFC 7232 §3.1, including the two forms a
     * generic HTTP client or proxy may legitimately send that {@link #parse(String)} rejects:
     * the wildcard and a comma-separated list of entity-tags (e.g. {@code "5", "7"}).
     *
     * @return {@link OptionalLong#empty()} for {@code If-Match: *} — "matches any current
     *         representation" (RFC 7232 §3.1), meaning the caller should proceed without a
     *         version check; otherwise the first listed tag's version, {@link OptionalLong#of}.
     *         Every other tag in a multi-valued list is still validated (not just the first)
     *         so a malformed list fails loudly rather than silently accepting on the strength
     *         of its first well-formed entry.
     */
    public static OptionalLong parseIfMatch(String ifMatchHeader) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String trimmed = ifMatchHeader.trim();
        if (trimmed.equals("*")) {
            return OptionalLong.empty();
        }
        String[] tags = trimmed.split(",");
        long first = parseTag(tags[0].trim());
        for (int i = 1; i < tags.length; i++) {
            parseTag(tags[i].trim());
        }
        return OptionalLong.of(first);
    }

    /**
     * Evaluates a full {@code If-Match} header against a target and answers the version an
     * update should assert — the second half of RFC 7232 §3.1, which {@link #parseIfMatch}
     * deliberately leaves to its caller (carried finding C4).
     *
     * <p>For a concrete entity-tag that is simply the tag's version, and the ordinary
     * optimistic-lock check downstream decides whether it still matches. For the wildcard
     * ({@code If-Match: *} — "any current representation") the rule has two halves and only
     * one of them lives in {@code parseIfMatch}: the request proceeds <em>if a representation
     * exists</em>, and the precondition is false — 412, not 404 and not a successful update —
     * if it does not. This method is where that second half is enforced, and it is the only
     * way a caller should turn a wildcard into a version.
     *
     * @param target        human-readable identification of the resource, for the 412 detail
     * @param currentVersion looks up the target's current version; {@link OptionalLong#empty()}
     *                       means the target has no current representation
     * @throws org.casemgmt.error.PreconditionRequiredException if the header is absent (428)
     * @throws org.casemgmt.rest.error.PreconditionFailedException on {@code *} with no
     *         current representation (412)
     */
    public static long expectedVersion(String ifMatchHeader, String target,
                                       Supplier<OptionalLong> currentVersion) {
        OptionalLong requested = parseIfMatch(ifMatchHeader);
        if (requested.isPresent()) {
            return requested.getAsLong();
        }
        OptionalLong current = currentVersion.get();
        if (current.isEmpty()) {
            throw new PreconditionFailedException(
                    "If-Match: * requires a current representation of " + target + ", which does not exist");
        }
        return current.getAsLong();
    }

    private static long parseTag(String rawTag) {
        String value = rawTag;
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new MalformedETagException(rawTag, e);
        }
    }
}
