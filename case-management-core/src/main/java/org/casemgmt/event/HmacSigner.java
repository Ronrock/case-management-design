package org.casemgmt.event;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing for outbound webhook payloads (spec §6.1) plus the SHA-256 hash used
 * to store a subscription's secret at rest. Signing needs the plaintext secret; storage never
 * does — see {@link WebhookDispatcher}'s Javadoc for how the plaintext is (and, for this PoC,
 * is not durably) made available to the dispatcher.
 */
public final class HmacSigner {

    private HmacSigner() {}

    /** Header value for X-Case-Signature (spec §6.1). */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign webhook payload", e);
        }
    }

    /** Subscriptions store only this; the plaintext is shown once at creation. */
    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash webhook secret", e);
        }
    }
}
