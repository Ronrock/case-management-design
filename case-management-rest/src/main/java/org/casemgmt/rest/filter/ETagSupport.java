package org.casemgmt.rest.filter;

import org.casemgmt.error.PreconditionRequiredException;
import org.casemgmt.rest.error.MalformedETagException;

import java.util.OptionalLong;

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
