package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.DatabaseWebhookSecretStore;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSecretStoreTest extends OracleTestBase {

    private WebhookRepository webhooks;
    private WebhookSecretCodec codec;

    @BeforeEach
    void setUp() {
        webhooks = new WebhookRepository(jdbc());
        codec = new AesGcmWebhookSecretCodec("test",
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        webhooks.insert("w-1", "t1", "http://localhost/hook", List.of("*"), "hash", 5);
    }

    @Test
    void databaseSecretStorePersistsEncryptedSigningMaterialAcrossStoreInstances() {
        new DatabaseWebhookSecretStore(webhooks, codec).save("w-1", "s3cret");

        String ciphertext = jdbc().sql("""
                SELECT SECRET_CIPHERTEXT_ FROM CM_WEBHOOK_SUB WHERE ID_ = 'w-1'""")
            .query(String.class).single();
        String keyId = jdbc().sql("""
                SELECT SECRET_KEY_ID_ FROM CM_WEBHOOK_SUB WHERE ID_ = 'w-1'""")
            .query(String.class).single();

        assertThat(keyId).isEqualTo("test");
        assertThat(ciphertext).isNotBlank().doesNotContain("s3cret");

        var restartedStore = new DatabaseWebhookSecretStore(new WebhookRepository(jdbc()), codec);
        assertThat(restartedStore.find("w-1")).contains("s3cret");
    }
}
