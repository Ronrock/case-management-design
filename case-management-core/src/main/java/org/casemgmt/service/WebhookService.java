package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.engine.EngineCommand;
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

    /**
     * Default {@code MAX_RETRIES_} for a new subscription: the length of the shared backoff
     * ladder, i.e. every rung once. Written as the ladder's own size rather than a literal 5 so
     * the two cannot drift — {@code WebhookDispatcher.fail} reads this column back as the
     * dead-letter threshold (review round 1; it used to be written here and never read).
     */
    public static final int DEFAULT_MAX_RETRIES = EngineCommand.BACKOFF.size();

    @Transactional
    public CreatedSubscription subscribe(String tenantId, String url, List<String> eventTypes, Actor actor) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);

        String id = CaseIds.newId();
        webhooks.insert(id, tenantId, url, eventTypes, HmacSigner.hash(secret), DEFAULT_MAX_RETRIES);
        return new CreatedSubscription(id, url, eventTypes, secret);
    }

    public List<WebhookRepository.Subscription> list() {
        return webhooks.all();
    }

    /**
     * The tenant-scoped listing the API exposes. {@link #list()} stays for callers that
     * genuinely operate across tenants (the dispatcher's own tooling); nothing reachable from
     * HTTP may use it — see {@code WebhookRepository.allForTenant}.
     */
    public List<WebhookRepository.Subscription> list(String tenantId) {
        return webhooks.allForTenant(tenantId);
    }

    /**
     * The dead-letter queue for one subscription. Until Task 27 this was reachable only from
     * tests — see {@code WebhookDispatcher}'s Javadoc on what a restart costs, and why a
     * dead-letter queue nothing can read is half a mechanism. {@code EventController} now
     * exposes it at {@code GET /webhooks/{id}/dead-letters}.
     *
     * <p>Takes no tenant: the caller must already have established that this subscription is
     * theirs. {@code EventController} does that by resolving the id through
     * {@link #list(String)}, which is tenant-scoped, so another tenant's id is simply not found.
     */
    public List<WebhookRepository.DeadLetter> deadLetters(String webhookId) {
        return webhooks.deadLetters(webhookId);
    }
}
