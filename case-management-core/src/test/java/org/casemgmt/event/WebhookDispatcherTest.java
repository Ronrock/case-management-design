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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDispatcherTest extends OracleTestBase {

    record Received(String body, String signature) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int responseCode = 200;
    private EventPublisher publisher;
    private WebhookRepository webhooks;

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
        server.start();

        webhooks = new WebhookRepository(jdbc());
        publisher = new EventPublisher(new EventRepository(jdbc()), new AuditRepository(jdbc()),
                webhooks, "org.example.cm", "eng-a");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String hookUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
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
            jdbc().sql("UPDATE CM_WEBHOOK_DELIVERY SET NEXT_ATTEMPT_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR")
                    .update();
            dispatcher.drainOnce();
        }

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY").query(String.class).single())
                .isEqualTo("DEAD");
        assertThat(webhooks.deadLetters("w-1")).hasSize(1);
    }

    @Test
    void subscriptionsOnlyReceiveTheirSubscribedTypes() {
        webhooks.insert("w-2", "t1", hookUrl(), List.of("org.example.cm.case.closed"),
                HmacSigner.hash("s3cret"), 5);
        publishOne();   // case.created

        dispatcher("s3cret").drainOnce();

        assertThat(received).isEmpty();
    }

    private WebhookDispatcher dispatcher(String plaintextSecret) {
        return new WebhookDispatcher(webhooks, new EventRepository(jdbc()),
                id -> plaintextSecret);
    }
}
