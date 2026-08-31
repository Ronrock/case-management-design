package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Companion to {@code CaseServiceTransactionalIntegrationTest} / {@code
 * CaseTaskServiceTransactionalIntegrationTest} for the three Task 18 collaboration services:
 * proves their {@code @Transactional} annotations are load-bearing, not inert.
 *
 * <p>{@link CollaborationServicesTest} — like every other repository/service test in this module —
 * builds {@link CommentService}, {@link LinkedProcessService} and {@link MilestoneService} with a
 * plain {@code new} via {@link TestServices}, which never puts a bean behind the Spring AOP proxy
 * that actually opens/commits/rolls back a transaction (Task 15). This class puts the REAL beans
 * under test via {@link OracleTestBase#springContext}.
 *
 * <p>All three mutating methods share the same shape — a domain write, then {@code
 * publisher.publish}, then {@code publisher.audit} — so, like {@code
 * CaseTaskServiceTransactionalIntegrationTest.claimRollsBackWhenAuditingFails}, each rollback test
 * here injects the failure into the audit call via {@link FailingAuditEventPublisher}: it is a
 * genuine downstream write, not a synthetic hook, and by the time it fires the domain write and
 * the CM_EVENT row are already sitting in the transaction, real, waiting to be undone.
 *
 * <p><b>Mutation-tested manually</b>: temporarily removing {@code @Transactional} from {@code
 * CommentService.add}, {@code LinkedProcessService.start} and {@code MilestoneService.achieve} in
 * turn and re-running this class makes the corresponding rollback test fail (the CM_COMMENT /
 * CM_LINKED_PROCESS / CM_MILESTONE+CM_EVENT rows the failing run wrote stay committed instead of
 * disappearing) — confirmed during development, one annotation at a time.
 */
class CollaborationServicesTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private CommentService comments;
    private LinkedProcessService processes;
    private MilestoneService milestones;
    private FailingAuditEventPublisher publisher;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;
    private String reviewedPlanItemId;

    @BeforeEach
    void setUp() throws Exception {
        CaseDefinition definition = TestServices.deployBpmnDefinition(
                dataSource(), "widget-review", "t1");

        ctx = springContext(CollaborationServicesTestConfig.class);
        comments = ctx.getBean(CommentService.class);
        processes = ctx.getBean(LinkedProcessService.class);
        milestones = ctx.getBean(MilestoneService.class);
        publisher = ctx.getBean(FailingAuditEventPublisher.class);

        caseId = TestServices.insertBpmnCase(dataSource(), definition, "T", alice.userId()).id();
        reviewedPlanItemId = CaseIds.newId();
        new PlanItemRepository(jdbc()).insert(new PlanItem(reviewedPlanItemId, caseId, null,
                PlanItemType.MILESTONE, "Reviewed", PlanItemState.ACTIVE, null, false, 1,
                null, null, null, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void addCommentRollsBackEverythingWhenAuditingFails() {
        int commentCountBefore = countAll("CM_COMMENT");
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        publisher.failNextAudit();

        assertThatThrownBy(() -> comments.add(caseId, "note", "internal", alice))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAll("CM_COMMENT")).isEqualTo(commentCountBefore);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    @Test
    void startProcessRollsBackEverythingWhenAuditingFails() {
        int processCountBefore = countAll("CM_LINKED_PROCESS");
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        publisher.failNextAudit();

        assertThatThrownBy(() -> processes.start(caseId, null, "letter-process", Map.of(), alice))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAll("CM_LINKED_PROCESS")).isEqualTo(processCountBefore);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    @Test
    void achieveMilestoneRollsBackEverythingWhenAuditingFails() {
        MilestoneRepository milestoneRepo = new MilestoneRepository(jdbc());
        String milestoneId = CaseIds.newId();
        milestoneRepo.insert(milestoneId, caseId, reviewedPlanItemId, "Reviewed");
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        publisher.failNextAudit();

        assertThatThrownBy(() -> milestones.achieve(caseId, milestoneId, alice))
                .isInstanceOf(IllegalStateException.class);

        // The ACHIEVED_ = 1 write the UPDATE made before the failing audit call must be gone too
        // — proof this is a whole-transaction rollback, not just the CM_EVENT row escaping.
        MilestoneRepository.MilestoneRow reloaded = milestoneRepo.findByCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst().orElseThrow();
        assertThat(reloaded.achieved()).isFalse();
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    /**
     * Genuine two-thread race, the same technique {@code
     * EngineCommandClaimSafetyTest.concurrentClaimsNeverAssignTheSameCommandToBothCallers} uses:
     * two callers hit {@link MilestoneService#achieve} for the SAME milestone at (as near as a
     * {@link CountDownLatch} can arrange) the same instant. {@link MilestoneRepository#achieve}'s
     * {@code UPDATE ... WHERE ACHIEVED_ = 0} — and {@link MilestoneService#achieve} basing its
     * "did I just achieve it" decision on that UPDATE's own affected-row count rather than an
     * earlier, racy read — is what has to hold for exactly one caller to succeed and exactly one
     * {@code case.milestone.achieved} event to exist afterward. Reintroducing the TOCTOU version
     * (deciding from the pre-UPDATE read instead) reproducibly makes both callers "succeed" and
     * writes two events — verified manually during development by reverting {@link
     * MilestoneService#achieve} to that shape and rerunning this test.
     */
    @Test
    void concurrentDoubleAchieveProducesExactlyOneEvent() throws Exception {
        MilestoneRepository milestoneRepo = new MilestoneRepository(jdbc());
        String milestoneId = CaseIds.newId();
        milestoneRepo.insert(milestoneId, caseId, reviewedPlanItemId, "Reviewed");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        List<MilestoneRepository.MilestoneRow> successes;
        try {
            Future<MilestoneRepository.MilestoneRow> a = pool.submit(() -> attempt(milestoneId, start, conflicts));
            Future<MilestoneRepository.MilestoneRow> b = pool.submit(() -> attempt(milestoneId, start, conflicts));
            start.countDown();

            successes = java.util.stream.Stream.of(a, b)
                    .map(this::get)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } finally {
            pool.shutdownNow();
        }

        // Exactly one of the two racing callers actually achieved it, and the other got a real
        // conflict rather than silently believing it succeeded too — that pairing is only
        // possible because MilestoneService.achieve trusts MilestoneRepository.achieve's own
        // affected-row count instead of an earlier, racy read (see class Javadoc).
        assertThat(successes).hasSize(1);
        assertThat(conflicts.get()).isEqualTo(1);

        MilestoneRepository.MilestoneRow reloaded = milestoneRepo.findByCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst().orElseThrow();
        assertThat(reloaded.achieved()).isTrue();

        List<String> milestoneEventIds = jdbc().sql(
                "SELECT ID_ FROM CM_EVENT WHERE TYPE_ LIKE '%case.milestone.achieved' AND SUBJECT_ = :caseId")
                .param("caseId", caseId)
                .query(String.class).list();
        assertThat(milestoneEventIds).hasSize(1);
    }

    private MilestoneRepository.MilestoneRow attempt(String milestoneId, CountDownLatch start,
                                                      AtomicInteger conflicts) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        try {
            return milestones.achieve(caseId, milestoneId, alice);
        } catch (CaseConflictException e) {
            conflicts.incrementAndGet();
            return null;
        }
    }

    private <T> T get(Future<T> f) {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int countAll(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /**
     * Registers the REAL {@link CommentService}, {@link LinkedProcessService} and {@link
     * MilestoneService} beans so {@code
     * TransactionManagerConfig}'s auto-proxy creator wraps them. {@link CaseService} is wired via
     * Fixture setup inserts a BPMN case directly because case creation/orchestration is outside
     * the scope of these collaboration-service transaction tests.
     */
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

        @Bean
        MilestoneService milestoneService(DataSource dataSource, FailingAuditEventPublisher publisher) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new MilestoneService(new MilestoneRepository(jdbc), new CaseRepository(jdbc), publisher);
        }
    }

    /**
     * An {@link EventPublisher} whose {@code audit} call can be told to fail once — copies {@code
     * CaseTaskServiceTransactionalIntegrationTest.FailingAuditEventPublisher}'s shape rather than
     * reusing it directly (that one is package-private to its own file, and this class needs the
     * SAME instance shared across three different services, which a private nested class in a
     * different test class cannot cleanly provide).
     */
    static class FailingAuditEventPublisher extends EventPublisher {
        private volatile boolean failAudit = false;

        FailingAuditEventPublisher(EventRepository events, AuditRepository audit, WebhookRepository webhooks,
                                   String typePrefix, String engineId) {
            super(events, audit, webhooks, typePrefix, engineId);
        }

        void failNextAudit() { failAudit = true; }

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
