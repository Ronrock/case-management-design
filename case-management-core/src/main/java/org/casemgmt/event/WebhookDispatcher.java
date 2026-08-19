package org.casemgmt.event;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.repo.WebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 * the shared {@link HttpClient} carries a {@link #CONNECT_TIMEOUT} connect timeout, and every
 * individual request additionally carries a {@link #RESPONSE_TIMEOUT} response timeout via
 * {@link HttpRequest.Builder#timeout}. A subscriber endpoint that accepts the TCP connection and
 * then never responds (an equally hung server, just past the connect stage) is caught by the
 * per-request timeout, throws {@link java.net.http.HttpTimeoutException}, and is retried with
 * backoff exactly like a connection failure — it can never block {@link #drainOnce} indefinitely
 * or starve the rest of the claimed batch. Covered by {@code
 * WebhookDispatcherTest.aHungSubscriberTimesOutAndIsRetriedWithASqlNullStatusCode}, which drives a
 * genuinely hung {@code HttpServer} through an injected short {@link #RESPONSE_TIMEOUT}.
 *
 * <p><b>Batch size vs. claim lease (review round 1):</b> one pass claims at most
 * {@link WebhookRepository#MAX_CLAIM_BATCH} rows and each costs at most
 * {@code CONNECT_TIMEOUT + RESPONSE_TIMEOUT}, so a whole pass is bounded — and the constructor
 * REFUSES to build a dispatcher whose bound would reach {@link WebhookRepository#CLAIM_LEASE}.
 * Without that check a hung subscriber makes mid-batch lease expiry, and therefore duplicate
 * delivery of the batch's tail to a second dispatcher, the expected path rather than a
 * crash-only one.
 *
 * <p><b>Per-row failure isolation (review round 1):</b> everything a single delivery does —
 * subscription lookup, event read, signing, and the HTTP call — runs inside {@link #drainOnce}'s
 * per-row {@code try}, the same shape {@code EngineCommandDispatcher.drainOnce} uses. Previously
 * only the HTTP call was guarded, so e.g. a misconfigured {@code secretResolver} returning
 * {@code null}
 * threw out of {@code drainOnce}, marked NOTHING, and left the whole batch to be reclaimed and
 * re-thrown on the next pass forever: an unbounded claim/expire/throw loop in which the retry
 * ladder never advanced and no row ever dead-lettered. A bad row must cost itself an attempt,
 * not the batch.
 *
 * <p><b>Secret plaintext:</b> {@code SECRET_HASH_} on {@code CM_WEBHOOK_SUB} deliberately stores
 * only a one-way SHA-256 hash (see {@link HmacSigner#hash}) — signing, however, needs the
 * plaintext secret, which the hash cannot recover. This class therefore takes a
 * {@code secretResolver} (subscription id -&gt; plaintext) supplied by the application assembly.
 * The starter wires it to {@link WebhookSecretStore}, which persists encrypted signing material
 * and can resolve the same subscription after a process restart. A resolver that still returns
 * {@code null} is treated as a per-delivery failure and moves through retry/DLQ like any other
 * pre-HTTP fault.
 */
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** Connect-phase bound, shared by every request this dispatcher makes. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Per-request response bound (time to full response, not just to connect). Overridable per
     * instance ONLY so a test can drive the hung-subscriber path without a real 10s sleep — see
     * {@link #WebhookDispatcher(WebhookRepository, EventRepository, Function, Duration)}.
     */
    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookRepository webhooks;
    private final EventRepository events;
    private final Function<String, String> secretResolver;
    private final Duration responseTimeout;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT).build();

    public WebhookDispatcher(WebhookRepository webhooks, EventRepository events,
                             Function<String, String> secretResolver) {
        this(webhooks, events, secretResolver, RESPONSE_TIMEOUT);
    }

    /**
     * @param responseTimeout per-request response bound; must be small enough that a full batch
     *                        ({@link WebhookRepository#MAX_CLAIM_BATCH} deliveries, each costing
     *                        at most {@link #CONNECT_TIMEOUT} + this) still finishes inside
     *                        {@link WebhookRepository#CLAIM_LEASE}. Enforced here rather than
     *                        left as a comment, because the relationship between the three
     *                        numbers is exactly what review round 1 found had silently drifted:
     *                        50 rows x 10s = 500s against a 300s lease meant a second dispatcher
     *                        reclaimed and re-delivered the tail of a batch the first was still
     *                        working through.
     * @throws IllegalArgumentException if the resulting worst-case batch would reach the lease
     */
    public WebhookDispatcher(WebhookRepository webhooks, EventRepository events,
                             Function<String, String> secretResolver, Duration responseTimeout) {
        Duration worstCaseBatch = CONNECT_TIMEOUT.plus(responseTimeout)
                .multipliedBy(WebhookRepository.MAX_CLAIM_BATCH);
        if (worstCaseBatch.compareTo(WebhookRepository.CLAIM_LEASE) >= 0) {
            throw new IllegalArgumentException(
                    "worst-case batch " + worstCaseBatch + " (" + WebhookRepository.MAX_CLAIM_BATCH
                    + " deliveries x connect " + CONNECT_TIMEOUT + " + response " + responseTimeout
                    + ") must stay under CLAIM_LEASE " + WebhookRepository.CLAIM_LEASE
                    + ", or a second dispatcher reclaims and re-delivers rows this one still holds");
        }
        this.webhooks = webhooks;
        this.events = events;
        this.secretResolver = secretResolver;
        this.responseTimeout = responseTimeout;
    }

    /**
     * Claims one batch and delivers it. Every per-row failure — including one thrown before the
     * HTTP call is even reached — costs that row an attempt on the retry ladder and nothing more;
     * the rest of the batch is unaffected. Same structure as
     * {@code EngineCommandDispatcher.drainOnce}.
     *
     * @return how many rows were claimed (not how many succeeded)
     */
    public int drainOnce() {
        List<WebhookRepository.Delivery> due =
                webhooks.claimDueDeliveries(WebhookRepository.MAX_CLAIM_BATCH);
        for (WebhookRepository.Delivery delivery : due) {
            // Holds whatever subscription `deliver` managed to load before it threw, so a
            // failure raised BEFORE the HTTP call — most importantly HmacSigner.sign throwing
            // because secretResolver returned null — still gets the subscription's own
            // MAX_RETRIES_ rather than fail()'s no-subscription fallback. See the composite
            // note in this class's Javadoc for why that mattered so much.
            var sub = new java.util.concurrent.atomic.AtomicReference<WebhookRepository.Subscription>();
            try {
                deliver(delivery, sub);
            } catch (InterruptedException e) {
                fail(delivery, sub.get(), null, describe(e));
                Thread.currentThread().interrupt();
                return due.size();
            } catch (Exception e) {
                fail(delivery, sub.get(), null, describe(e));
            }
        }
        return due.size();
    }

    private void deliver(WebhookRepository.Delivery delivery,
                         java.util.concurrent.atomic.AtomicReference<WebhookRepository.Subscription> loaded)
            throws Exception {
        WebhookRepository.Subscription sub = webhooks.require(delivery.webhookId());
        loaded.set(sub);
        EventRepository.StoredEvent stored = events.after(delivery.eventSeq() - 1, 1).stream()
                .filter(e -> e.seq() == delivery.eventSeq()).findFirst().orElse(null);
        if (stored == null) {
            marked(delivery, webhooks.markDead(delivery.id(), delivery.claimToken(), null,
                    "event " + delivery.eventSeq() + " not found"));
            return;
        }

        String body = JsonCodec.toJson(stored.event().toCloudEvent());
        String signature = HmacSigner.sign(secretResolver.apply(sub.id()), body);

        HttpResponse<Void> response;
        try {
            response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(sub.url()))
                            .timeout(responseTimeout)
                            .header("Content-Type", "application/cloudevents+json")
                            .header("X-Case-Signature", signature)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // Transport-level: no HTTP status was ever produced, so LAST_STATUS_CODE_ stays NULL.
            // HttpTimeoutException (the hung-subscriber case) is an IOException.
            fail(delivery, sub, null, describe(e));
            return;
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            marked(delivery, webhooks.markDelivered(delivery.id(), delivery.claimToken(),
                    response.statusCode()));
        } else {
            fail(delivery, sub, response.statusCode(), "HTTP " + response.statusCode());
        }
    }

    /**
     * Retry-or-dead-letter decision. The BACKOFF DELAYS come from the shared
     * {@link EngineCommand#BACKOFF} ladder, but the EXHAUSTION THRESHOLD comes from the
     * subscription's own {@code MAX_RETRIES_} (review round 1: the column was written by
     * {@code WebhookService.subscribe}, mapped onto {@code Subscription}, and then never read —
     * dead config that both the schema comment and any reader would assume worked). The ladder's
     * length is the fallback for the cases where no subscription could be loaded — the failure
     * happened before or during {@link WebhookRepository#require}, or the row's subscription is
     * gone — and for a non-positive configured value, which would otherwise dead-letter on the
     * very first attempt.
     *
     * @param sub the delivery's subscription, or {@code null} if it could not be loaded
     */
    private void fail(WebhookRepository.Delivery delivery, WebhookRepository.Subscription sub,
                      Integer statusCode, String error) {
        int maxRetries = sub != null
                ? sub.maxRetries()
                : EngineCommand.BACKOFF.size();

        if (delivery.attempts() >= maxRetries) {
            marked(delivery, webhooks.markDead(delivery.id(), delivery.claimToken(), statusCode, error));
        } else {
            marked(delivery, webhooks.markRetry(delivery.id(), delivery.claimToken(), statusCode,
                    error, EngineCommand.nextAttempt(delivery.attempts())));
        }
    }

    /**
     * A {@code false} here means this dispatcher's claim had already been reclaimed by another
     * one, which then owns the row's outcome — the mark wrote nothing rather than overwriting it
     * (see {@link WebhookRepository#markDelivered}). It should not happen at all given the lease
     * arithmetic in this class's constructor, so it is worth a warning rather than silence.
     */
    private void marked(WebhookRepository.Delivery delivery, boolean applied) {
        if (!applied) {
            log.warn("Webhook delivery {} lost its claim before it could be marked; another "
                    + "dispatcher now owns this row's outcome", delivery.id());
        }
    }

    /** {@code NullPointerException} and friends carry a null message; the type is the clue. */
    private static String describe(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
