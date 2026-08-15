package org.casemgmt.repo;

import org.casemgmt.event.WebhookSecretCodec;
import org.casemgmt.event.WebhookSecretStore;

import java.util.Optional;

public class DatabaseWebhookSecretStore implements WebhookSecretStore {

    private final WebhookRepository webhooks;
    private final WebhookSecretCodec codec;

    public DatabaseWebhookSecretStore(WebhookRepository webhooks, WebhookSecretCodec codec) {
        this.webhooks = webhooks;
        this.codec = codec;
    }

    @Override
    public void save(String webhookId, String plaintextSecret) {
        WebhookSecretCodec.EncodedSecret encoded = codec.encrypt(plaintextSecret);
        webhooks.storeSecret(webhookId, encoded.keyId(), encoded.ciphertext());
    }

    @Override
    public Optional<String> find(String webhookId) {
        return webhooks.secret(webhookId)
                .map(secret -> codec.decrypt(new WebhookSecretCodec.EncodedSecret(
                        secret.keyId(), secret.ciphertext())));
    }
}
