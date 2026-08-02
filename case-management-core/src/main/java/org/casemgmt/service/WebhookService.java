package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.HmacSigner;
import org.casemgmt.repo.WebhookRepository;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Creates and lists webhook subscriptions (spec §6.1). {@code CM_WEBHOOK_SUB.SECRET_HASH_} is a
 * one-way hash (see {@link HmacSigner#hash}); the plaintext secret this class generates is
 * returned to the caller exactly once, in {@link CreatedSubscription}, and is never written to
 * the database — see {@link org.casemgmt.event.WebhookDispatcher}'s Javadoc for how (and how
 * incompletely, for this PoC) the plaintext gets from here to the dispatcher that needs it again.
 */
public class WebhookService {

    /** The plaintext secret is returned exactly once, at creation. */
    public record CreatedSubscription(String id, String url, List<String> eventTypes, String secret) {}

    private final WebhookRepository webhooks;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(WebhookRepository webhooks) {
        this.webhooks = webhooks;
    }

    @Transactional
    public CreatedSubscription subscribe(String tenantId, String url, List<String> eventTypes, Actor actor) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);

        String id = CaseIds.newId();
        webhooks.insert(id, tenantId, url, eventTypes, HmacSigner.hash(secret), 5);
        return new CreatedSubscription(id, url, eventTypes, secret);
    }

    public List<WebhookRepository.Subscription> list() {
        return webhooks.all();
    }

    public List<WebhookRepository.Delivery> deadLetters(String webhookId) {
        return webhooks.deadLetters(webhookId);
    }
}
