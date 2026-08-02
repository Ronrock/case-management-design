package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Companion to {@code CaseServiceTransactionalIntegrationTest} / {@code
 * PlanItemServiceTransactionalIntegrationTest} for {@link CaseTaskService}: proves its {@code
 * @Transactional} annotations on {@code claim}/{@code complete} are load-bearing, not inert.
 *
 * <p>{@link CaseTaskServiceTest} — like every other repository/service test in this module —
 * builds {@link CaseTaskService} with a plain {@code new}, which never puts it behind the Spring
 * AOP proxy that actually opens/commits/rolls back a transaction. This test puts the REAL {@link
 * CaseTaskService} bean under test via {@link OracleTestBase#springContext}.
 *
 * <p><b>Why the two tests fail at different points, deliberately:</b>
 * <ul>
 *   <li>{@code complete()} calls {@code engine.completeTask} BEFORE its own {@code CM_TASK}
 *   write, then — via {@code PlanItemService.complete}'s {@code reevaluate()} — can cascade into
 *   a SECOND, later engine call ({@code createHumanTask} for whatever plan item the completion
 *   just unblocked). Failing THAT second call, after the first task's own completion write has
 *   already happened in the same transaction, is what gives {@code @Transactional} something
 *   genuine to undo — the same technique {@code
 *   CaseServiceTransactionalIntegrationTest.closeRollsBackEverythingOnAMidCloseEngineFailure}
 *   uses. Failing the FIRST engine call instead (as an earlier draft of this test did) proves
 *   nothing: that call happens before any write, so the assertions would hold identically with
 *   or without {@code @Transactional} — exactly the "vacuous test" trap this suite's own review
 *   note warns about, just for a transactional test instead of a validation one.</li>
 *   <li>{@code claim()} has no such cascade: it makes exactly one engine call, {@code
 *   engine.claimTask}, and it too precedes the only {@code CM_TASK} write the method makes.
 *   There is no way to fail the engine call itself and have anything left to roll back. What
 *   {@code @Transactional} actually buys {@code claim()} is atomicity between that write and the
 *   {@code CM_EVENT}/{@code CM_AUDIT_LOG} writes {@code publish}/{@code audit} make right after
 *   it — so this test proves THAT instead, injecting a failure into the audit write (a genuine
 *   downstream step, not the engine) via {@link FailingAuditEventPublisher} once the claim write
 *   has already happened.</li>
 * </ul>
 *
 * <p><b>Mutation-tested manually</b>: temporarily removing {@code @Transactional} from {@code
 * claim} makes {@code claimRollsBackWhenAuditingFails} fail (the task is left {@code CLAIMED}
 * with its version bumped); removing it from {@code complete} makes {@code
 * completeRollsBackWhenACascadingEngineCallFails} fail (the review task is left {@code
 * COMPLETED}) — confirmed by running both with the annotations stripped during development.
 */
class CaseTaskServiceTransactionalIntegrationTest extends OracleTestBase {

    private static final String CASCADE_DEF = """
            {"key":"txn-task-cascade","name":"Task Cascade","tenantId":"t1",
             "planItems":[
               {"defKey":"review","type":"HUMAN_TASK","name":"review","required":true,"sortOrder":10},
               {"defKey":"followUp","type":"HUMAN_TASK","name":"followUp",
                "entryCriteria":["${items.review.state == 'COMPLETED'}"],"sortOrder":20}
             ]}""";

    private AnnotationConfigApplicationContext ctx;
    private CaseTaskService taskService;
    private CaseService cases;
    private FailingGateway gateway;
    private FailingAuditEventPublisher publisher;
    private CaseTaskRepository tasks;
    private PlanItemRepository planItems;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;
    private CaseTask reviewTask;

    @BeforeEach
    void setUp() throws Exception {
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(CASCADE_DEF, "system");

        ctx = springContext(CaseTaskServiceTestConfig.class);
        taskService = ctx.getBean(CaseTaskService.class);
        cases = ctx.getBean(CaseService.class);
        gateway = ctx.getBean(FailingGateway.class);
        publisher = ctx.getBean(FailingAuditEventPublisher.class);
        tasks = new CaseTaskRepository(jdbc());
        planItems = new PlanItemRepository(jdbc());

        gateway.neverFail();
        caseId = cases.create("txn-task-cascade", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
        reviewTask = tasks.findByCase(caseId).stream()
                .filter(t -> t.name().equals("review")).findFirst().orElseThrow();
        assertThat(reviewTask.state()).isEqualTo(TaskState.OPEN);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void claimRollsBackWhenAuditingFails() {
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        publisher.failNextAudit();

        assertThatThrownBy(() -> taskService.claim(reviewTask.id(), reviewTask.version(), alice))
                .isInstanceOf(IllegalStateException.class);

        CaseTask reloaded = tasks.require(reviewTask.id());
        assertThat(reloaded.state()).isEqualTo(TaskState.OPEN);
        assertThat(reloaded.assignee()).isNull();
        assertThat(reloaded.version()).isEqualTo(reviewTask.version());

        // The CM_EVENT row publish() wrote right before the failing audit() call must also be
        // gone: proof this is a whole-transaction rollback, not just the CM_TASK write escaping.
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    @Test
    void completeRollsBackWhenACascadingEngineCallFails() {
        CaseTask claimed = taskService.claim(reviewTask.id(), reviewTask.version(), alice);
        PlanItem reviewItemBefore = planItems.require(claimed.planItemId());
        int taskCountBefore = countAll("CM_TASK");
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        // review's own completion write happens BEFORE this fires: reevaluate() only reaches
        // followUp's createHumanTask after review is already persisted COMPLETED in this
        // transaction. Failing here is what gives @Transactional something real to undo.
        gateway.failOnTaskNamed("followUp");

        assertThatThrownBy(() -> taskService.complete(claimed.id(), claimed.version(), Map.of(), alice))
                .isInstanceOf(IllegalStateException.class);

        CaseTask reloaded = tasks.require(claimed.id());
        assertThat(reloaded.state()).isEqualTo(TaskState.CLAIMED);
        assertThat(reloaded.version()).isEqualTo(claimed.version());

        PlanItem reviewItemAfter = planItems.require(claimed.planItemId());
        assertThat(reviewItemAfter.state()).isEqualTo(reviewItemBefore.state());
        assertThat(reviewItemAfter.version()).isEqualTo(reviewItemBefore.version());

        // No followUp CM_TASK row survives either, even though its creation is what triggered
        // the failure in the first place.
        assertThat(countAll("CM_TASK")).isEqualTo(taskCountBefore);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    private int countAll(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /**
     * Registers the REAL {@link CaseTaskService} (and the {@link CaseService}/{@link
     * PlanItemService} it needs) as Spring {@code @Bean}s so {@code TransactionManagerConfig}'s
     * auto-proxy creator wraps them. {@link CaseTaskService} is wired by hand rather than via
     * {@link TestServices#taskService} because this class needs to substitute {@link
     * FailingAuditEventPublisher} for the plain {@link EventPublisher} that factory method
     * builds internally and does not expose as a swappable argument.
     */
    @Configuration
    static class CaseTaskServiceTestConfig {
        @Bean
        FailingGateway failingGateway() {
            return new FailingGateway();
        }

        @Bean
        FailingAuditEventPublisher failingAuditEventPublisher(DataSource dataSource) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new FailingAuditEventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                    new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        }

        @Bean
        CaseService caseService(DataSource dataSource, FailingGateway gateway) {
            return TestServices.caseService(dataSource, gateway);
        }

        @Bean
        PlanItemService planItemService(DataSource dataSource, FailingGateway gateway) {
            return TestServices.planItemService(dataSource, gateway);
        }

        @Bean
        CaseTaskService taskService(DataSource dataSource, FailingGateway gateway,
                                    PlanItemService planItemService, FailingAuditEventPublisher publisher) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new CaseTaskService(new CaseTaskRepository(jdbc), new CaseRepository(jdbc),
                    new CaseDefinitionRepository(dataSource), gateway, new FormValidator(),
                    planItemService, new PlanItemRepository(jdbc), publisher);
        }
    }

    /**
     * An {@link EngineGateway} whose {@code createHumanTask} failure is controlled per test —
     * copies {@code CaseServiceTransactionalIntegrationTest.FailingGateway}'s shape rather than
     * reusing it directly, since this class also needs {@code claimTask}/{@code completeTask} to
     * stay reliable no-ops (the fault this suite injects for {@code claim}/{@code complete} is
     * downstream of the engine call, not the call itself — see the class Javadoc).
     */
    static class FailingGateway implements EngineGateway {
        private volatile java.util.function.Predicate<String> failOn = name -> false;

        void neverFail() { failOn = name -> false; }
        void failOnTaskNamed(String name) { failOn = name::equals; }

        @Override
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            if (failOn.test(r.name())) {
                throw new IllegalStateException("simulated engine failure creating task '" + r.name() + "'");
            }
            return new EngineTaskRef("engine-" + java.util.UUID.randomUUID(), r.name(), r.assignee(), r.caseId(), null);
        }

        @Override public void claimTask(String id, String user) {}
        @Override public void completeTask(String id, Map<String, Object> v) {}

        @Override
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-1", r.processDefinitionKey());
        }

        @Override public void cancelProcess(String id, String reason) {}
        @Override public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }

    /**
     * An {@link EventPublisher} whose {@code audit} call can be told to fail once, so a test can
     * prove {@link CaseTaskService#claim} rolls back its own {@code CM_TASK}/{@code CM_EVENT}
     * writes when the {@code CM_AUDIT_LOG} write that follows them, in the same transaction,
     * fails — a genuine downstream failure {@code claim()} has no engine call left to simulate
     * (see class Javadoc).
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
                throw new IllegalStateException("simulated audit failure recording '" + action + "'");
            }
            super.audit(caseId, tenantId, actor, action, resourceType, resourceId, before, after);
        }
    }
}
