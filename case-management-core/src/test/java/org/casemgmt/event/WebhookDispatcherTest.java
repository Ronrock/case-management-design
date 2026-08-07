package org.casemgmt.event;

import com.sun.net.httpserver.HttpServer;
import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDispatcherTest extends OracleTestBase {

    record Received(String body, String signature) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int responseCode = 200;
    private EventPublisher publisher;
    private WebhookRepository webhooks;

    /**
     * Releases the deliberately-hung {@code /hang} handler at teardown. Without it the handler
     * thread would still be parked when {@link HttpServer#stop} runs.
     */
    private final CountDownLatch releaseHungHandler = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_WEBHOOK_SUB", "CM_EVENT")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        received.clear();
        responseCode = 200;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-Case-Signature")));
            exchange.sendResponseHeaders(responseCode, 0);
            try (OutputStream os = exchange.getResponseBody()) { os.write(new byte[0]); }
        });
        // A subscriber that accepts the connection and then never answers — the hung-server case
        // WebhookDispatcher's Javadoc claims the per-request response timeout defends against.
        server.createContext("/hang", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-Case-Signature")));
            try {
                releaseHungHandler.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();

        webhooks = new WebhookRepository(jdbc());
        publisher = new EventPublisher(new EventRepository(jdbc()), new AuditRepository(jdbc()),
                webhooks, "org.example.cm", "eng-a");
    }

    @AfterEach
    void tearDown() {
        releaseHungHandler.countDown();
        server.stop(0);
    }

    private String hookUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    private String hangUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/hang";
    }

    private void subscribe(String secretHash) {
        webhooks.insert("w-1", "t1", hookUrl(), List.of("*"), secretHash, 5);
    }

    private void publishOne() {
        publisher.publish(new CaseEvent(CaseIds.newId(), "eng-a", "case.created", "eng-a:1",
                "t1", OffsetDateTime.now(), Map.of("state", "ACTIVE")));
    }

    @Test
    void deliversTheCloudEventEnvelopeAndMarksTheRowDelivered() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();

        int processed = dispatcher("s3cret").drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).body()).contains("\"specversion\":\"1.0\"");
        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY").query(String.class).single())
                .isEqualTo("DELIVERED");
    }

    @Test
    void signsThePayloadWithTheSubscriptionSecret() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();

        dispatcher("s3cret").drainOnce();

        String signature = received.get(0).signature();
        assertThat(signature).startsWith("sha256=");
        assertThat(HmacSigner.sign("s3cret", received.get(0).body())).isEqualTo(signature);
    }

    @Test
    void failedDeliveriesRetryThenLandInTheDeadLetterQueue() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();
        responseCode = 500;

        var dispatcher = dispatcher("s3cret");
        for (int i = 0; i < 6; i++) {
            makeDue();
            dispatcher.drainOnce();
        }

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY").query(String.class).single())
                .isEqualTo("DEAD");
        assertThat(webhooks.deadLetters("w-1")).hasSize(1);
        // The HTTP-failure path DOES record a status code — the counterpart to the transport
        // failures below, which must leave this column SQL NULL.
        assertThat(lastStatusCode()).isEqualTo(500);
    }

    /**
     * Replaces a test that asserted only {@code received.isEmpty()} after publishing an event no
     * subscription matched (review round 1): no delivery row was ever enqueued, so the dispatcher
     * claimed nothing and that assertion held even if the entire HTTP block were deleted. The
     * mechanism actually worth pinning here is the ENQUEUE side — {@code EventPublisher} must
     * write no {@code CM_WEBHOOK_DELIVERY} row at all for a non-matching subscription — plus
     * {@code drainOnce} reporting zero claimed. Both can fail; the old assertion could not.
     */
    @Test
    void anEventNoSubscriptionMatchesEnqueuesNoDeliveryRowAndDrainsNothing() {
        webhooks.insert("w-2", "t1", hookUrl(), List.of("org.example.cm.case.closed"),
                HmacSigner.hash("s3cret"), 5);
        publishOne();   // case.created — does not match case.closed

        assertThat(deliveryRowCount()).isZero();
        assertThat(dispatcher("s3cret").drainOnce()).isZero();
        assertThat(received).isEmpty();
    }

    /**
     * Critical finding, review round 1: everything before the HTTP call used to sit outside
     * {@code deliver()}'s try. The realistic trigger is this class's own documented PoC shortcut —
     * the in-memory secret map does not survive a process restart, so {@code secretResolver}
     * returns {@code null}, {@code HmacSigner.sign} rewraps the NPE as {@code IllegalStateException},
     * and it escaped {@code drainOnce} entirely: no row marked, the ladder never advanced, the
     * batch reclaimed on lease expiry and thrown again forever.
     */
    @Test
    void aRowWhoseSecretCannotBeResolvedCostsItselfAnAttemptAndNotTheRestOfTheBatch() {
        webhooks.insert("w-1", "t1", hookUrl(), List.of("*"), HmacSigner.hash("s3cret"), 5);
        webhooks.insert("w-2", "t1", hookUrl(), List.of("*"), HmacSigner.hash("s3cret"), 5);
        publishOne();   // fans out to both subscriptions

        Function<String, String> restartedResolver = id -> "w-1".equals(id) ? null : "s3cret";
        int processed = new WebhookDispatcher(webhooks, new EventRepository(jdbc()),
                restartedResolver).drainOnce();

        assertThat(processed).isEqualTo(2);
        assertThat(received).hasSize(1);                        // w-2 was not collateral damage
        assertThat(statusOf("w-2")).isEqualTo("DELIVERED");
        assertThat(statusOf("w-1")).isEqualTo("RETRYING");      // ladder advanced for the bad row
        assertThat(attemptsOf("w-1")).isEqualTo(1);
        assertThat(lastErrorOf("w-1")).contains("Could not sign webhook payload");
    }

    /**
     * Final whole-branch review, Important 6: the restart composite, and the half of it this
     * class can actually fix.
     *
     * <p>{@code drainOnce}'s generic handler used to call {@code fail(delivery, null, ...)}, so
     * a failure raised BEFORE the HTTP call — which is exactly what a restart produces, since
     * the in-memory secret map is empty and {@code HmacSigner.sign} throws — fell back to
     * {@code BACKOFF.size()} (5) instead of reading the subscription's own {@code MAX_RETRIES_}.
     * The same dead-config defect review round 1 fixed for the HTTP path and left in place on
     * this one. The catch now carries whatever subscription {@code deliver} had loaded.
     *
     * <p>Attribution: {@code MAX_RETRIES_} is 1, and {@code fail} dead-letters once the
     * PRE-increment attempt count has reached it — so with the fix the row is DEAD after the
     * second pre-HTTP failure, and without it the same row is still RETRYING (it would take
     * five). Both the status and the attempt count are pinned, and {@code lastError} confirms
     * the failure is the signing one this finding is about, not some other throw that happened
     * to reach the same handler.
     */
    @Test
    void aPreHttpFailureUsesTheSubscriptionsOwnMaxRetriesNotTheLaddersLength() {
        webhooks.insert("w-1", "t1", hookUrl(), List.of("*"), HmacSigner.hash("s3cret"), 1);
        publishOne();

        // The post-restart resolver: the plaintext secret is simply gone.
        var restarted = new WebhookDispatcher(webhooks, new EventRepository(jdbc()), id -> null);

        assertThat(restarted.drainOnce()).isEqualTo(1);
        assertThat(statusOf("w-1")).isEqualTo("RETRYING");
        assertThat(attemptsOf("w-1")).isEqualTo(1);

        makeDue();
        assertThat(restarted.drainOnce()).isEqualTo(1);

        assertThat(received).isEmpty();
        assertThat(lastErrorOf("w-1")).contains("Could not sign webhook payload");
        assertThat(attemptsOf("w-1")).isEqualTo(2);
        assertThat(statusOf("w-1")).isEqualTo("DEAD");   // RETRYING if the ladder's 5 were used
    }

    /**
     * The observability half of the same finding: {@code deadLetters} now reports WHY a delivery
     * died, not merely that it did. Before this, {@code WebhookRepository.Delivery} carried only
     * the dispatcher's claim/mark fields, so a dead-letter listing could not distinguish a 500
     * from a signing failure from a hung endpoint — which is the whole question a restart
     * raises. Also pins the SQL-NULL status code: a pre-HTTP failure produced no HTTP status,
     * and {@code getInt} would silently render that as 0, a code that does not exist.
     */
    @Test
    void aDeadLetterReportsWhyItDiedIncludingAnAbsentHttpStatus() {
        webhooks.insert("w-1", "t1", hookUrl(), List.of("*"), HmacSigner.hash("s3cret"), 1);
        publishOne();
        var restarted = new WebhookDispatcher(webhooks, new EventRepository(jdbc()), id -> null);
        restarted.drainOnce();
        makeDue();
        restarted.drainOnce();

        List<WebhookRepository.DeadLetter> dead = webhooks.deadLetters("w-1");

        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).attempts()).isEqualTo(2);
        assertThat(dead.get(0).lastError()).contains("Could not sign webhook payload");
        assertThat(dead.get(0).lastStatusCode())
                .as("a failure raised before the request went out has no HTTP status at all — "
                        + "it must be null, never 0")
                .isNull();
        // failedAt exists because openapi-specs.md:1245 requires it and no column carried it
        // (corrective round). It is the field an operator needs to answer "did these all die when
        // we restarted?", so it must actually be stamped, not left null by markDead.
        assertThat(dead.get(0).failedAt()).isNotNull();

        // Negative control: a real HTTP failure DOES carry its status through the same field,
        // so the null above is discriminating on the absent-status case, not on the column
        // never being populated.
        webhooks.insert("w-2", "t1", hookUrl(), List.of("*"), HmacSigner.hash("s3cret"), 1);
        publishOne();
        responseCode = 500;
        var http = dispatcher("s3cret");
        http.drainOnce();
        makeDue();
        http.drainOnce();

        assertThat(webhooks.deadLetters("w-2"))
                .anySatisfy(d -> assertThat(d.lastStatusCode()).isEqualTo(500));
    }

    /**
     * The class's headline timeout claim, previously unverified (review round 1): every {@code
     * fail()} in this suite came from the HTTP-500 path, where a status code exists. Drives a
     * genuinely hung subscriber through an injected short response timeout — the injection point
     * exists so this can be covered in milliseconds rather than the production 10s.
     */
    @Test
    void aHungSubscriberTimesOutAndIsRetriedWithASqlNullStatusCode() {
        webhooks.insert("w-1", "t1", hangUrl(), List.of("*"), HmacSigner.hash("s3cret"), 5);
        publishOne();

        var dispatcher = new WebhookDispatcher(webhooks, new EventRepository(jdbc()),
                id -> "s3cret", Duration.ofMillis(500));

        assertThat(dispatcher.drainOnce()).isEqualTo(1);

        assertThat(received).hasSize(1);                        // the request did reach the server
        assertThat(statusOf("w-1")).isEqualTo("RETRYING");
        assertThat(attemptsOf("w-1")).isEqualTo(1);
        assertThat(lastErrorOf("w-1")).contains("HttpTimeoutException");
        assertThat(rowsWithNullStatusCode()).isEqualTo(1);      // really SQL NULL, not 0
    }

    /**
     * The other {@code markDead(id, token, null, ...)} caller — the event-not-found branch, also
     * untested before review round 1. Same null-{@code Integer} bind, on the DEAD statement.
     *
     * <p>Driven through a stubbed {@link EventRepository} rather than a genuinely orphaned row
     * because the schema makes one impossible to create: {@code FK_CM_WHD_EVENT} (no {@code ON
     * DELETE CASCADE}) rejects a delivery pointing at a missing {@code CM_EVENT} row AND rejects
     * deleting an event a delivery still references — confirmed by ORA-02291 when this test first
     * tried the direct route. The branch is defensive, but its SQL is not: a null {@code Integer}
     * bound to {@code LAST_STATUS_CODE_} either works or throws from inside a failure handler,
     * which is exactly the compounding failure worth ruling out.
     */
    @Test
    void aDeliveryWhoseEventIsMissingIsDeadLetteredWithASqlNullStatusCode() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();

        EventRepository noEvents = new EventRepository(jdbc()) {
            @Override
            public List<StoredEvent> after(long cursor, int limit) {
                return List.of();
            }
        };
        assertThat(new WebhookDispatcher(webhooks, noEvents, id -> "s3cret").drainOnce())
                .isEqualTo(1);

        assertThat(received).isEmpty();
        assertThat(statusOf("w-1")).isEqualTo("DEAD");
        assertThat(lastErrorOf("w-1")).contains("not found");
        assertThat(rowsWithNullStatusCode()).isEqualTo(1);
        assertThat(webhooks.deadLetters("w-1")).hasSize(1);
    }

    /**
     * Review round 1: {@code MAX_RETRIES_} was written per subscription and never read — the
     * dispatcher used the shared ladder's length (5) for every subscription. A subscription
     * configured with 2 must dead-letter on its own third failure, not on the fifth.
     */
    @Test
    void deadLettersAtTheSubscriptionsOwnMaxRetriesNotTheLaddersLength() {
        webhooks.insert("w-1", "t1", hookUrl(), List.of("*"), HmacSigner.hash("s3cret"), 2);
        publishOne();
        responseCode = 500;

        var dispatcher = dispatcher("s3cret");
        for (int i = 0; i < 2; i++) {
            makeDue();
            dispatcher.drainOnce();
        }
        assertThat(statusOf("w-1")).isEqualTo("RETRYING");
        assertThat(attemptsOf("w-1")).isEqualTo(2);

        makeDue();
        dispatcher.drainOnce();

        assertThat(statusOf("w-1")).isEqualTo("DEAD");   // would still be RETRYING on the ladder's 5
        assertThat(attemptsOf("w-1")).isEqualTo(3);
        assertThat(webhooks.deadLetters("w-1")).hasSize(1);
    }

    /**
     * Ruled by the human partner in review round 1: {@code ACTIVE_} governs FAN-OUT, not the
     * outbox. Deactivating a subscription stops future deliveries being enqueued; a delivery
     * already committed to {@code CM_WEBHOOK_DELIVERY} still goes out. Pins the deliberate
     * asymmetry between {@code WebhookRepository.require} and {@code WebhookRepository.active}.
     */
    @Test
    void aDeliveryAlreadyEnqueuedStillGoesOutAfterItsSubscriptionIsDeactivated() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();   // fan-out happened while the subscription was still active
        jdbc().sql("UPDATE CM_WEBHOOK_SUB SET ACTIVE_ = 0 WHERE ID_ = 'w-1'").update();

        assertThat(dispatcher("s3cret").drainOnce()).isEqualTo(1);

        assertThat(received).hasSize(1);
        assertThat(statusOf("w-1")).isEqualTo("DELIVERED");
    }

    private WebhookDispatcher dispatcher(String plaintextSecret) {
        return new WebhookDispatcher(webhooks, new EventRepository(jdbc()),
                id -> plaintextSecret);
    }

    /** Back-dates every row's next attempt so the following drain claims it again. */
    private void makeDue() {
        jdbc().sql("UPDATE CM_WEBHOOK_DELIVERY SET NEXT_ATTEMPT_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR")
                .update();
    }

    private String statusOf(String webhookId) {
        return jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY WHERE WEBHOOK_ID_ = :w")
                .param("w", webhookId).query(String.class).single();
    }

    private int attemptsOf(String webhookId) {
        return jdbc().sql("SELECT ATTEMPTS_ FROM CM_WEBHOOK_DELIVERY WHERE WEBHOOK_ID_ = :w")
                .param("w", webhookId).query(Integer.class).single();
    }

    private String lastErrorOf(String webhookId) {
        return jdbc().sql("SELECT LAST_ERROR_ FROM CM_WEBHOOK_DELIVERY WHERE WEBHOOK_ID_ = :w")
                .param("w", webhookId).query(String.class).single();
    }

    private int lastStatusCode() {
        return jdbc().sql("SELECT LAST_STATUS_CODE_ FROM CM_WEBHOOK_DELIVERY")
                .query(Integer.class).single();
    }

    /** Asserted as a COUNT so it is a genuine {@code IS NULL} check, not a mapped Java null. */
    private int rowsWithNullStatusCode() {
        return jdbc().sql("SELECT COUNT(*) FROM CM_WEBHOOK_DELIVERY WHERE LAST_STATUS_CODE_ IS NULL")
                .query(Integer.class).single();
    }

    private int deliveryRowCount() {
        return jdbc().sql("SELECT COUNT(*) FROM CM_WEBHOOK_DELIVERY").query(Integer.class).single();
    }
}
