package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transactional rollback coverage for the remaining mutable collaboration services. */
class CollaborationServicesTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext context;
    private CommentService comments;
    private LinkedProcessService processes;
    private FailingAuditEventPublisher publisher;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        CaseDefinition definition = TestServices.deployBpmnDefinition(dataSource(), "widget-review", "t1");
        context = springContext(CollaborationServicesTestConfig.class);
        comments = context.getBean(CommentService.class);
        processes = context.getBean(LinkedProcessService.class);
        publisher = context.getBean(FailingAuditEventPublisher.class);
        caseId = TestServices.insertBpmnCase(dataSource(), definition, "T", alice.userId()).id();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void addCommentRollsBackEverythingWhenAuditingFails() {
        int commentsBefore = countAll("CM_COMMENT");
        int eventsBefore = countAll("CM_EVENT");
        int auditBefore = countAll("CM_AUDIT_LOG");
        publisher.failNextAudit();

        assertThatThrownBy(() -> comments.add(caseId, "note", "internal", alice))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAll("CM_COMMENT")).isEqualTo(commentsBefore);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventsBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditBefore);
    }

    @Test
    void startProcessRollsBackEverythingWhenAuditingFails() {
        int processesBefore = countAll("CM_LINKED_PROCESS");
        int eventsBefore = countAll("CM_EVENT");
        int auditBefore = countAll("CM_AUDIT_LOG");
        publisher.failNextAudit();

        assertThatThrownBy(() -> processes.start(caseId, null, "letter-process", Map.of(), alice))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAll("CM_LINKED_PROCESS")).isEqualTo(processesBefore);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventsBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditBefore);
    }

    private int countAll(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @Configuration
    static class CollaborationServicesTestConfig {
        @Bean
        CaseServiceTransactionalIntegrationTest.FailingGateway failingGateway() {
            return new CaseServiceTransactionalIntegrationTest.FailingGateway();
        }

        @Bean
        FailingAuditEventPublisher failingAuditEventPublisher(DataSource dataSource) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new FailingAuditEventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                    new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        }

        @Bean
        CommentService commentService(DataSource dataSource, FailingAuditEventPublisher publisher) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new CommentService(new CommentRepository(jdbc), new CaseRepository(jdbc), publisher);
        }

        @Bean
        LinkedProcessService linkedProcessService(DataSource dataSource,
                                                   CaseServiceTransactionalIntegrationTest.FailingGateway gateway,
                                                   FailingAuditEventPublisher publisher) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new LinkedProcessService(new LinkedProcessRepository(jdbc), new CaseRepository(jdbc),
                    gateway, publisher);
        }
    }

    static class FailingAuditEventPublisher extends EventPublisher {
        private volatile boolean failAudit;

        FailingAuditEventPublisher(EventRepository events, AuditRepository audit, WebhookRepository webhooks,
                                   String typePrefix, String engineId) {
            super(events, audit, webhooks, typePrefix, engineId);
        }

        void failNextAudit() {
            failAudit = true;
        }

        @Override
        public void audit(String caseId, String tenantId, String actor, String action,
                          String resourceType, String resourceId, Object before, Object after) {
            if (failAudit) {
                failAudit = false;
                throw new IllegalStateException("simulated audit failure recording '" + action + "'");
            }
            super.audit(caseId, tenantId, actor, action, resourceType, resourceId, before, after);
        }
    }
}
