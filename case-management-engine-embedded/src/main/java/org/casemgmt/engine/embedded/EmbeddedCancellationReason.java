package org.casemgmt.engine.embedded;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Versioned, one-to-one encoding of case cancellation intent into Operaton's delete reason. */
final class EmbeddedCancellationReason {

    private static final String PREFIX = "__casemgmt_cancel_v1__:";
    private static final String NULL = PREFIX + "N";
    private static final String STRING = PREFIX + "S";

    private EmbeddedCancellationReason() { }

    static String encode(String reason) {
        if (reason == null) {
            return NULL;
        }
        return STRING + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(reason.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns null when the delete reason was not produced by this versioned gateway format. */
    static Decoded decode(String encoded) {
        if (NULL.equals(encoded)) {
            return new Decoded(null);
        }
        if (encoded == null || !encoded.startsWith(STRING)) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded.substring(STRING.length()));
            return new Decoded(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    record Decoded(String reason) { }
}
