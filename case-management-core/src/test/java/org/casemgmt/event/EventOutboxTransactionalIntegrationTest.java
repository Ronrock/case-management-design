package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the property spec §6.1 actually depends on: the case mutation, the CM_EVENT row, the
 * CM_AUDIT_LOG row and the CM_WEBHOOK_DELIVERY fan-out row(s) it produces are ONE local
 * transaction — "no event for a rolled-back change, no lost events". {@link EventPublisherTest}
 * (Task 14's brief) only proves {@link EventPublisher} appends events and fans out deliveries
 * when called directly against an autocommitted connection; it never puts a failure between the
 * case write and the outbox writes, so it cannot distinguish a real outbox from four independent
 * autocommits that merely happen to run back-to-back. This test does, following the same
 * pattern {@code OutboxTransactionalIntegrationTest} (Task 13) used to pin the engine-command
 * outbox: a genuine {@code @Transactional} bean method that does real work and then either
 * throws or returns, checked from both sides.
 */
class EventOutboxTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private TransactionalCaseChange caseChange;

    @BeforeEach
    void setUp() {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('widget-review:1', 'widget-review', 1, 'Widget review')""").update();
        jdbc().sql("""
                INSERT INTO CM_WEBHOOK_SUB (ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, ACTIVE_,
                    SECRET_HASH_, MAX_RETRIES_, VERSION_)
                VALUES ('w-outbox', 't1', 'http://localhost/hook', '["org.example.cm.case.updated"]',
                    1, 'hash', 8, 0)""").update();

        ctx = springContext(TransactionalCaseChange.class);
        caseChange = ctx.getBean(TransactionalCaseChange.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private void seedCase(String caseId) {
        jdbc().sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_,
                    CASE_DEF_VER_, TITLE_, STATE_)
                VALUES (:id, 'eng-a', 't1', 'widget-review:1', 'widget-review', 1, 'Original title', 'ACTIVE')""")
            .param("id", caseId).update();
    }

    @Test
    void aFailureAfterTheCaseMutationLeavesNoEventNoAuditRowAndNoDeliveryRow() {
        seedCase("eng-a:outbox-fail");

        assertThatThrownBy(() -> caseChange.mutateCaseAndPublish("eng-a:outbox-fail", true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(caseTitle("eng-a:outbox-fail")).isEqualTo("Original title");
        assertThat(countWhere("CM_EVENT", "SUBJECT_ = 'eng-a:outbox-fail'")).isZero();
        assertThat(countWhere("CM_AUDIT_LOG", "RESOURCE_ID_ = 'eng-a:outbox-fail'")).isZero();
        assertThat(countWhere("CM_WEBHOOK_DELIVERY", "1=1")).isZero();
    }

    @Test
    void aSuccessfulMutationCommitsTheCaseTheEventTheAuditRowAndTheDeliveryRowTogether() {
        seedCase("eng-a:outbox-ok");

        long seq = caseChange.mutateCaseAndPublish("eng-a:outbox-ok", false);

        assertThat(caseTitle("eng-a:outbox-ok")).isEqualTo("Updated by outbox test");
        assertThat(countWhere("CM_EVENT", "SUBJECT_ = 'eng-a:outbox-ok'")).isEqualTo(1);
        assertThat(countWhere("CM_AUDIT_LOG", "RESOURCE_ID_ = 'eng-a:outbox-ok'")).isEqualTo(1);

        // The delivery row is consistent with the event it fans out from, not just present.
        Long deliveredSeq = jdbc().sql("SELECT EVENT_SEQ_ FROM CM_WEBHOOK_DELIVERY WHERE WEBHOOK_ID_ = 'w-outbox'")
                .query(Long.class).single();
        assertThat(deliveredSeq).isEqualTo(seq);
    }

    private String caseTitle(String id) {
        return jdbc().sql("SELECT TITLE_ FROM CM_CASE WHERE ID_ = :id").param("id", id)
                .query(String.class).single();
    }

    private int countWhere(String table, String predicate) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)
                .query(Integer.class).single();
    }

    /**
     * Stands in for a future case-mutating service (Tasks 15/18/19): one {@code @Transactional}
     * method that changes CM_CASE, calls {@link EventPublisher#publish}, and calls
     * {@link EventPublisher#audit} — exactly the four writes spec §6.1 requires to commit or roll
     * back as a unit. {@code EventPublisher} is plain (not itself {@code @Transactional}) so this
     * only proves the guarantee if the AOP proxy around THIS bean is what supplies the
     * transaction — which is the point: nothing here is transactional except this method.
     */
    @Component
    static class TransactionalCaseChange {
        private final JdbcClient jdbc;
        private final EventPublisher publisher;

        TransactionalCaseChange(DataSource dataSource) {
            JdbcClient client = JdbcClient.create(dataSource);
            this.jdbc = client;
            this.publisher = new EventPublisher(new EventRepository(client), new AuditRepository(client),
                    new WebhookRepository(client), "org.example.cm", "eng-a");
        }

        @Transactional
        long mutateCaseAndPublish(String caseId, boolean thenFail) {
            jdbc.sql("UPDATE CM_CASE SET TITLE_ = :title, VERSION_ = VERSION_ + 1 WHERE ID_ = :id")
                .param("title", "Updated by outbox test").param("id", caseId).update();

            long seq = publisher.publish(new CaseEvent(
                    org.casemgmt.domain.CaseIds.newId(), "eng-a", "case.updated", caseId, "t1",
                    OffsetDateTime.now(), Map.of("title", "Updated by outbox test")));

            publisher.audit(caseId, "t1", "alice", "case.update", "Case", caseId,
                    Map.of("title", "Original title"), Map.of("title", "Updated by outbox test"));

            if (thenFail) {
                throw new IllegalStateException("simulated failure after case mutation and outbox writes");
            }
            return seq;
        }
    }
}
