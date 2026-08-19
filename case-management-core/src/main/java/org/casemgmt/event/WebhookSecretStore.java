package org.casemgmt.event;

import java.util.Optional;

public interface WebhookSecretStore {

    void save(String webhookId, String plaintextSecret);

    Optional<String> find(String webhookId);
}
