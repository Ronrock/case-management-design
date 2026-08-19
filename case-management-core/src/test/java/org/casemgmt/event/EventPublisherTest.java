package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPublisherTest extends OracleTestBase {

    private EventPublisher publisher;
    private EventRepository events;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_WEBHOOK_DELIVERY").update();
        jdbc().sql("DELETE FROM CM_WEBHOOK_SUB").update();
        jdbc().sql("DELETE FROM CM_EVENT").update();
        jdbc().sql("DELETE FROM CM_AUDIT_LOG").update();
        events = new EventRepository(jdbc());
        publisher = new EventPublisher(events, new AuditRepository(jdbc()),
                new WebhookRepository(jdbc()), "org.example.cm", "eng-a");
    }

    private CaseEvent event(String type, String subject) {
        return new CaseEvent(org.casemgmt.domain.CaseIds.newId(), "eng-a", type, subject,
                "t1", OffsetDateTime.now(), Map.of("state", "ACTIVE"));
    }

    @Test
    void appendsEventsWithAMonotonicCursor() {
        publisher.publish(event("case.created", "eng-a:1"));
        publisher.publish(event("case.updated", "eng-a:1"));
        List<EventRepository.StoredEvent> stored = events.after(0, 10);

        assertThat(stored.get(1).seq()).isGreaterThan(stored.get(0).seq());
        assertThat(stored).hasSize(2);
        assertThat(events.after(stored.get(0).seq(), 10)).hasSize(1);
    }

    @Test
    void prependsTheConfiguredTypePrefix() {
        publisher.publish(event("case.created", "eng-a:1"));

        assertThat(events.after(0, 10)).singleElement()
                .satisfies(e -> assertThat(e.event().type()).isEqualTo("org.example.cm.case.created"));
    }

    @Test
    void publishStampsSourceFromTheConfiguredEngineIdEvenWhenTheCallerSuppliesAWrongOne() {
        // Spec §6.2: source = casemgmt.engine-id is a hard rule. A caller passing a wrong or
        // stale source must not be able to make it into the persisted envelope — publish()
        // stamps source the same way it stamps type, discarding whatever the caller supplied.
        CaseEvent wrongSource = new CaseEvent(org.casemgmt.domain.CaseIds.newId(),
                "some-other-engine", "case.created", "eng-a:1", "t1", OffsetDateTime.now(),
                Map.of("state", "ACTIVE"));

        publisher.publish(wrongSource);

        assertThat(events.after(0, 10)).singleElement()
                .satisfies(e -> assertThat(e.event().source()).isEqualTo("eng-a"));
    }

    @Test
    void filtersPerCaseEventLogsBySubject() {
        publisher.publish(event("case.created", "eng-a:1"));
        publisher.publish(event("case.created", "eng-a:2"));

        assertThat(events.forCase("eng-a:2", 0, 10)).hasSize(1);
    }

    @Test
    void fansOutOneDeliveryPerMatchingSubscription() {
        new WebhookRepository(jdbc()).insert("w-1", "t1", "http://localhost/hook",
                List.of("org.example.cm.case.created"), "hash", 8);
        new WebhookRepository(jdbc()).insert("w-2", "t1", "http://localhost/other",
                List.of("org.example.cm.case.closed"), "hash", 8);

        publisher.publish(event("case.created", "eng-a:1"));

        Integer deliveries = jdbc().sql("SELECT COUNT(*) FROM CM_WEBHOOK_DELIVERY")
                .query(Integer.class).single();
        assertThat(deliveries).isEqualTo(1);
    }

    @Test
    void wildcardSubscriptionsMatchEveryType() {
        new WebhookRepository(jdbc()).insert("w-3", "t1", "http://localhost/all",
                List.of("*"), "hash", 8);

        publisher.publish(event("case.created", "eng-a:1"));
        publisher.publish(event("case.closed", "eng-a:1"));

        Integer deliveries = jdbc().sql("SELECT COUNT(*) FROM CM_WEBHOOK_DELIVERY")
                .query(Integer.class).single();
        assertThat(deliveries).isEqualTo(2);
    }

    @Test
    void writesAuditRowsWithBeforeAndAfterImages() {
        publisher.audit("eng-a:1", "t1", "alice", "case.close", "Case", "eng-a:1",
                Map.of("state", "ACTIVE"), Map.of("state", "CLOSED"));

        String after = jdbc().sql("SELECT AFTER_JSON_ FROM CM_AUDIT_LOG").query(String.class).single();
        assertThat(after).contains("CLOSED");
    }

    @Test
    void rejectsABlankTypePrefixInsteadOfDefaultingToAPlaceholder() {
        // casemgmt.events.type-prefix has deliberately NO default: a placeholder namespace
        // shipped into someone's real broker is unfixable later, so this must fail fast at
        // construction rather than silently publish under a bogus prefix.
        assertThatThrownBy(() -> new EventPublisher(events, new AuditRepository(jdbc()),
                new WebhookRepository(jdbc()), "  ", "eng-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventPublisher(events, new AuditRepository(jdbc()),
                new WebhookRepository(jdbc()), null, "eng-a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cloudEventEnvelopeCarriesTheRequiredAttributes() {
        Map<String, Object> envelope = event("case.created", "eng-a:1").toCloudEvent();

        assertThat(envelope).containsKeys("specversion", "id", "source", "type", "subject", "time", "data");
        assertThat(envelope.get("specversion")).isEqualTo("1.0");
    }
}
