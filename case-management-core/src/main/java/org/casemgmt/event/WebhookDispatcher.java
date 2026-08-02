package org.casemgmt.event;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.repo.WebhookRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Reads {@code CM_WEBHOOK_DELIVERY} and pushes to subscriber URLs. Never called from a request
 * thread: {@link EventPublisher} enqueues delivery rows inside the caller's transaction; this
 * class delivers them afterwards, out of band, at-least-once, with the same backoff schedule as
 * the engine command outbox ({@link EngineCommand#BACKOFF}).
 *
 * <p><b>HTTP timeouts (Task 12's review flagged the remote engine {@code RestClient} for having
 * none — a hung server otherwise blocks the caller forever with no exception to retry on):</b>
 * the shared {@link HttpClient} carries a 5s connect timeout, and every individual request
 * additionally carries a 10s response timeout via {@link HttpRequest.Builder#timeout}. Together
 * these bound the worst case per delivery to 10s: a subscriber endpoint that accepts the TCP
 * connection and then never responds (an equally hung server, just past the connect stage) is
 * caught by the per-request timeout, throws {@link java.net.http.HttpTimeoutException}, and is
 * retried with backoff exactly like a connection failure — it can never block {@link #drainOnce}
 * indefinitely or starve the rest of the claimed batch.
 *
 * <p><b>Secret plaintext (known PoC shortcut, not a solved problem):</b> {@code SECRET_HASH_} on
 * {@code CM_WEBHOOK_SUB} deliberately stores only a one-way SHA-256 hash (see {@link
 * HmacSigner#hash}) — signing, however, needs the plaintext secret, which the hash cannot
 * recover. This class therefore takes a {@code secretResolver} (subscription id -&gt; plaintext)
 * supplied by the caller instead of reading a plaintext column that does not exist. The wiring
 * task (Task 26) is expected to populate an in-memory map from {@link
 * org.casemgmt.service.WebhookService#subscribe}, which is the only place the plaintext is ever
 * held — it is generated there, returned to the caller once, and never persisted. That in-memory
 * map does not survive a process restart, so every subscription would need to be re-created after
 * one; a real deployment needs either reversible encryption of the secret (with the decryption
 * key held outside the case-management database) or per-subscription signing keys held in an
 * external secret store (e.g. Vault, KMS) that this class's {@code secretResolver} would call
 * out to instead. Do not "solve" this by adding a plaintext column to {@code CM_WEBHOOK_SUB}.
 */
public class WebhookDispatcher {

    private final WebhookRepository webhooks;
    private final EventRepository events;
    private final Function<String, String> secretResolver;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookDispatcher(WebhookRepository webhooks, EventRepository events,
                             Function<String, String> secretResolver) {
        this.webhooks = webhooks;
        this.events = events;
        this.secretResolver = secretResolver;
    }

    public int drainOnce() {
        List<WebhookRepository.Delivery> due = webhooks.claimDueDeliveries(50);
        for (WebhookRepository.Delivery delivery : due) {
            deliver(delivery);
        }
        return due.size();
    }

    private void deliver(WebhookRepository.Delivery delivery) {
        WebhookRepository.Subscription sub = webhooks.require(delivery.webhookId());
        EventRepository.StoredEvent stored = events.after(delivery.eventSeq() - 1, 1).stream()
                .filter(e -> e.seq() == delivery.eventSeq()).findFirst().orElse(null);
        if (stored == null) {
            webhooks.markDead(delivery.id(), null, "event " + delivery.eventSeq() + " not found");
            return;
        }

        String body = JsonCodec.toJson(stored.event().toCloudEvent());
        String signature = HmacSigner.sign(secretResolver.apply(sub.id()), body);

        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(sub.url()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/cloudevents+json")
                            .header("X-Case-Signature", signature)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                webhooks.markDelivered(delivery.id(), response.statusCode());
            } else {
                fail(delivery, response.statusCode(), "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            fail(delivery, null, e.getMessage());
        }
    }

    private void fail(WebhookRepository.Delivery delivery, Integer statusCode, String error) {
        if (EngineCommand.exhausted(delivery.attempts())) {
            webhooks.markDead(delivery.id(), statusCode, error);
        } else {
            webhooks.markRetry(delivery.id(), statusCode, error,
                    EngineCommand.nextAttempt(delivery.attempts()));
        }
    }
}
