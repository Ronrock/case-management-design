package org.casemgmt.rest.filter;

import org.casemgmt.error.PreconditionRequiredException;

public final class ETagSupport {

    private ETagSupport() {}

    /** ETag is the row's VERSION_ rendered as a strong tag (spec §6.3). */
    public static String format(long version) {
        return "\"" + version + "\"";
    }

    public static long parse(String ifMatchHeader) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String value = ifMatchHeader.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed If-Match header: " + ifMatchHeader, e);
        }
    }
}
