package org.casemgmt.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Known-answer tests for the wire format subscribers verify against.
 *
 * <p>Review round 1: the only signing coverage compared {@code HmacSigner.sign(...)} against a
 * header that {@code HmacSigner.sign(...)} had itself produced, so it was self-consistent by
 * construction — switching to base64, to uppercase hex, or to a different digest would have kept
 * it green while silently breaking every subscriber's signature verification. Pinning the output
 * to a published vector instead means the format is anchored to the standard, not to itself.
 */
class HmacSignerTest {

    /**
     * RFC 4231 §4.3, test case 2: key {@code "Jefe"}, data
     * {@code "what do ya want for nothing?"} — the one vector in that RFC whose key and data are
     * both plain ASCII, so it exercises this API's {@code String} arguments directly with no
     * hex-decoding step of the test's own to get wrong.
     */
    @Test
    void signMatchesTheRfc4231HmacSha256Vector() {
        assertThat(HmacSigner.sign("Jefe", "what do ya want for nothing?"))
                .isEqualTo("sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    /**
     * The stored form of a subscription secret is equally a format that must not drift: it is
     * written to {@code CM_WEBHOOK_SUB.SECRET_HASH_} once and compared against later. FIPS 180-4's
     * one-block SHA-256 example ({@code "abc"}).
     */
    @Test
    void hashMatchesThePublishedSha256VectorForAbc() {
        assertThat(HmacSigner.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** Lowercase hex, and the {@code sha256=} prefix spec §6.1 requires, are both part of it. */
    @Test
    void signIsLowercaseHexBehindAnSha256Prefix() {
        String signature = HmacSigner.sign("s3cret", "{\"specversion\":\"1.0\"}");

        assertThat(signature).startsWith("sha256=");
        assertThat(signature.substring("sha256=".length())).matches("[0-9a-f]{64}");
    }
}
