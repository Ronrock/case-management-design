package org.casemgmt.event;

public interface WebhookSecretCodec {

    record EncodedSecret(String keyId, String ciphertext) {}

    EncodedSecret encrypt(String plaintextSecret);

    String decrypt(EncodedSecret encodedSecret);
}
