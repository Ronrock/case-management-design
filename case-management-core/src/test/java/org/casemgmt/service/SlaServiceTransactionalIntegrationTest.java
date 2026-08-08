package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.*;
import org.casemgmt.sla.SlaRecord;
import org.casemgmt.sla.SlaService;
import org.casemgmt.sla.SlaSweeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Companion to {@code CaseServiceTransactionalIntegrationTest} / {@code
 * CollaborationServicesTransactionalIntegrationTest} for {@code SlaService}/{@code SlaSweeper}
 * (Task 21 fix round 1, review finding I8): {@link TestServices#slaService} and {@link
 * TestServices#slaSweeper} — like every other factory in that class — build their beans with a
 * plain {@code new}, which never puts them behind the Spring AOP proxy that makes {@code
 * @Transactional} genuine (Task 15). This class puts the REAL beans under test via {@link
 * OracleTestBase#springContext}.
 *
 * <p><b>Mutation-tested manually</b>: temporarily removing {@code @Transactional} from {@code
 * SlaService.pause} and re-running {@link #pauseRollsBackEverythingWhenAuditingFails} makes it
 * fail — the CM_SLA_RECORD row the failing run wrote PAUSED_AT_ for stays committed instead of
 * reverting to RUNNING. Same for {@code SlaSweeper.sweep} against {@link
 * #sweepRollsBackTheRecordWriteWhenPublishingFails}. Restored afterward; see the task report for
 * the exact failure output both times.
 */
class SlaServiceTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private CaseService cases;
    private SlaService sla;
    private SlaSweeper sweeper;
    private SlaRepository slaRepo;
    private FailingPublisher publisher;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

        slaRepo = new SlaRepository(jdbc());
        slaRepo.insertCalendar("cal-nl", Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "TUESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "WEDNESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "THURSDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "FRIDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SATURDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SUNDAY", List.of(Map.of("from", "00:00", "to", "23:59"))),
                "holidays", List.of()));
        slaRepo.insertPolicy("pol-1", "Standard", null, "cal-nl");
        slaRepo.insertTarget("tgt-first", "pol-1", "firstResponse", "First response",
                "PT4H", "PT3H", List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));

        ctx = springContext(SlaServiceTestConfig.class);
        cases = ctx.getBean(CaseService.class);
        sla = ctx.getBean(SlaService.class);
        sweeper = ctx.getBean(SlaSweeper.class);
        publisher = ctx.getBean(FailingPublisher.class);

        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void pauseRollsBackEverythingWhenAuditingFails() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        publisher.failNextAudit();

        assertThatThrownBy(() ->
                sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice))
                .isInstanceOf(IllegalStateException.class);

        // The PAUSED_AT_/STATUS_ write the UPDATE made before the failing audit call must be
        // gone too — proof this is a whole-transaction rollback, not just the CM_EVENT row
        // escaping.
        SlaRecord reloaded = slaRepo.require(record.id());
        assertThat(reloaded.status()).isEqualTo("RUNNING");
        assertThat(reloaded.pausedAt()).isNull();
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    @Test
    void resumeRollsBackEverythingWhenAuditingFails() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        java.time.OffsetDateTime dueAtBeforeResume = paused.dueAt();
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        publisher.failNextAudit();

        assertThatThrownBy(() -> sla.resume(caseId, paused.id(), paused.version(), alice))
                .isInstanceOf(IllegalStateException.class);

        SlaRecord reloaded = slaRepo.require(paused.id());
        assertThat(reloaded.status()).isEqualTo("PAUSED");
        assertThat(reloaded.dueAt()).isEqualTo(dueAtBeforeResume);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    @Test
    void sweepRollsBackTheRecordWriteWhenPublishingFails() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE ID_ = :id")
                .param("id", record.id()).update();
        int eventCountBefore = countAll("CM_EVENT");

        publisher.failNextPublish();

        assertThatThrownBy(sweeper::sweep).isInstanceOf(IllegalStateException.class);

        // The BREACHED write sweep() made before the failing publish call must be gone too, and
        // the denormalised case status must never have been touched either.
        SlaRecord reloaded = slaRepo.require(record.id());
        assertThat(reloaded.status()).isEqualTo("RUNNING");
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(jdbc().sql("SELECT SLA_STATUS_ FROM CM_CASE WHERE ID_ = :id")
                .param("id", caseId).query(String.class).single()).isEqualTo("NONE");
    }

    /**
     * I2 proof: a genuine two-thread race (same technique as {@code
     * CollaborationServicesTransactionalIntegrationTest.concurrentDoubleAchieveProducesExactlyOneEvent})
     * where two REAL, separately-transactional {@code sweep()} calls contend for the SAME due
     * records. Neither call may throw to its caller — a per-record version conflict must be
     * absorbed internally, not abort that sweeper's whole batch — and every due record must end
     * up processed exactly once (BREACHED, with exactly one breach event), never twice and never
     * left behind.
     *
     * <p><b>Overlap is encouraged structurally, not fully guaranteed (fix round 2, honest
     * limitation).</b> {@code sweep()} is one {@code @Transactional} method: nothing it writes is
     * visible to the other thread until it COMMITS (returns). The isolation path is only actually
     * exercised if thread B's {@code dueRecords()} SELECT runs — and sees the records as still
     * due — before thread A's whole batch commits. Six due records (not one) widen that window:
     * thread A's transaction now costs six UPDATE round trips instead of one before it can
     * commit, giving thread B's SELECT — issued right after the same {@link CountDownLatch}
     * releases both threads onto a warm connection pool — much more time to land inside that
     * window. This is not a mathematical guarantee (a fully deterministic version would need
     * either a raw pre-acquired row lock held across both threads' start, or a test-only
     * synchronization hook inside {@code SlaSweeper} itself — both rejected as disproportionate
     * for what the reviewer flagged as a cheap item, and the second would put test-only code in
     * production). Empirically it has fired in 100% of runs performed during this fix round,
     * including every run in fix round 1's own development — see the {@code WARN
     * ... lost a concurrent version race} log line this test reliably produces.
     *
     * <p><b>Mutation-tested manually</b>: replacing the per-record {@code try/catch
     * (OptimisticLockException)} in {@code SlaSweeper.processOne} with an unguarded call
     * reproducibly makes one of the two {@code sweep()} futures fail with {@code
     * OptimisticLockException} instead of returning normally — confirmed during development,
     * restored afterward (see the task report for the exact failure).
     */
    @Test
    void concurrentSweepersIsolatePerRecordConflictsWithinABatch() throws Exception {
        List<String> caseIds = new ArrayList<>();
        caseIds.add(caseId);
        for (int i = 1; i < 6; i++) {
            caseIds.add(cases.create("widget-review", "t1", null, "T" + i,
                    CasePriority.MEDIUM, Map.of(), alice).id());
        }
        for (String id : caseIds) {
            sla.startClocks(id, "pol-1", alice);
        }
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> f1 = pool.submit(() -> { start.await(); return sweeper.sweep(); });
            Future<Integer> f2 = pool.submit(() -> { start.await(); return sweeper.sweep(); });
            start.countDown();

            // Both calls must return normally — proof neither aborted on the other's write.
            get(f1);
            get(f2);
        } finally {
            pool.shutdownNow();
        }

        for (String id : caseIds) {
            assertThat(slaRepo.findByCase(id).get(0).status()).isEqualTo("BREACHED");
            List<String> breachEvents = jdbc().sql(
                    "SELECT ID_ FROM CM_EVENT WHERE TYPE_ LIKE '%case.sla.breached' AND SUBJECT_ = :caseId")
                    .param("caseId", id).query(String.class).list();
            assertThat(breachEvents).hasSize(1);
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
     * Registers the REAL {@link SlaService} and {@link SlaSweeper} beans (plus the {@link
     * CaseService} fixture depends on) so {@code TransactionManagerConfig}'s auto-proxy creator
     * wraps them.
     */
    @Configuration
    static class SlaServiceTestConfig {
        @Bean
        CaseServiceTransactionalIntegrationTest.FailingGateway failingGateway() {
            return new CaseServiceTransactionalIntegrationTest.FailingGateway();
        }

        @Bean
        FailingPublisher failingPublisher(DataSource dataSource) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new FailingPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                    new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        }

        @Bean
        CaseService caseService(DataSource dataSource,
                                CaseServiceTransactionalIntegrationTest.FailingGateway gateway) {
            return TestServices.caseService(dataSource, gateway);
        }

        @Bean
        SlaService slaService(DataSource dataSource, FailingPublisher publisher) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new SlaService(new SlaRepository(jdbc), new CaseRepository(jdbc), publisher);
        }

        @Bean
        SlaSweeper slaSweeper(DataSource dataSource, FailingPublisher publisher) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new SlaSweeper(new SlaRepository(jdbc), new CaseRepository(jdbc), publisher);
        }
    }

    /**
     * An {@link EventPublisher} whose {@code publish} or {@code audit} call can each be told to
     * fail once — same shape as {@code CollaborationServicesTransactionalIntegrationTest
     * .FailingAuditEventPublisher}, extended with a publish-side failure since {@code
     * SlaSweeper.processOne} calls {@code publish} but never {@code audit}.
     */
    static class FailingPublisher extends EventPublisher {
        private volatile boolean failAudit = false;
        private volatile boolean failPublish = false;

        FailingPublisher(EventRepository events, AuditRepository audit, WebhookRepository webhooks,
                         String typePrefix, String engineId) {
            super(events, audit, webhooks, typePrefix, engineId);
        }

        void failNextAudit() { failAudit = true; }
        void failNextPublish() { failPublish = true; }

        @Override
        public long publish(CaseEvent event) {
            if (failPublish) {
                failPublish = false;
                throw new IllegalStateException("simulated publish failure for '" + event.type() + "'");
            }
            return super.publish(event);
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
