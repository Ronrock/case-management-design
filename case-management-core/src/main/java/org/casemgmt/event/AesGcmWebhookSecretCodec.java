package org.casemgmt.event;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class AesGcmWebhookSecretCodec implements WebhookSecretCodec {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String keyId;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmWebhookSecretCodec(String keyId, byte[] rawKey) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("webhook secret key id must be set");
        }
        if (rawKey == null || (rawKey.length != 16 && rawKey.length != 24 && rawKey.length != 32)) {
            throw new IllegalArgumentException("webhook secret encryption key must be 128, 192 or 256 bits");
        }
        this.keyId = keyId;
        this.key = new SecretKeySpec(rawKey.clone(), "AES");
    }

    public static AesGcmWebhookSecretCodec fromBase64(String keyId, String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("casemgmt.webhooks.secret-encryption-key must be set");
        }
        return new AesGcmWebhookSecretCodec(keyId, Base64.getDecoder().decode(base64Key));
    }

    @Override
    public EncodedSecret encrypt(String plaintextSecret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintextSecret.getBytes(StandardCharsets.UTF_8));
            return new EncodedSecret(keyId, Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                            .put(iv).put(encrypted).array()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt webhook secret", e);
        }
    }

    @Override
    public String decrypt(EncodedSecret encodedSecret) {
        try {
            byte[] payload = Base64.getDecoder().decode(encodedSecret.ciphertext());
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("encrypted webhook secret payload is too short");
            }
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt webhook secret " + encodedSecret.keyId(), e);
        }
    }
}
