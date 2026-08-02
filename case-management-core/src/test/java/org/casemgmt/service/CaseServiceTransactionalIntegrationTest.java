package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 15 review, Important 1: {@link CaseServiceTest} proves {@link CaseService}'s behaviour
 * against a plain {@code new CaseService(...)} — the same way every other repository/service
 * test in this module runs — which never puts the class behind a Spring AOP proxy, so its
 * {@code @Transactional} annotations are inert there. The review's own probe made this concrete:
 * an {@link EngineGateway} that throws deep inside {@code create()}'s post-insert
 * {@code reevaluate()} — after the case row, the participant, three plan items, the
 * {@code case.created} event and the audit row had all already executed — left CM_CASE=1,
 * CM_PARTICIPANT=1, CM_PLAN_ITEM=3, CM_EVENT=2, CM_AUDIT_LOG=1 committed anyway under plain
 * {@code new}, versus all-zero once {@code CaseService} is registered as a real Spring bean
 * through {@code TransactionManagerConfig} (via {@link OracleTestBase#springContext}, the same
 * pattern Task 13's {@code OutboxTransactionalIntegrationTest} and Task 14's
 * {@code EventOutboxTransactionalIntegrationTest} use).
 *
 * <p>Unlike those two, this test puts the REAL {@code CaseService} bean under test — not a
 * stand-in {@code @Component} that merely reuses the same collaborators — because what's being
 * proved is specifically "CaseService's own {@code @Transactional} annotations are load-bearing",
 * not "{@code @Transactional} works in general" (already proved by Task 13's
 * {@code TransactionManagerTest}).
 *
 * <p><b>Mutation-tested manually</b>: temporarily removing {@code @Transactional} from
 * {@code create} and from {@code close} and re-running this class makes both tests below fail
 * with the row counts the review's probe reported (verified during development — see the task
 * report for the exact failure output). That confirms these tests actually exercise the
 * annotation rather than passing for some unrelated reason — a rollback test that stays green
 * without the annotation would prove nothing.
 *
 * <p><b>Round-2 review fix — CM_TASK/CM_WEBHOOK_DELIVERY coverage was vacuous.</b> The first
 * cut injected the engine failure on the ONLY {@code createHumanTask} call each transaction
 * made, which fires inside {@code TransitionApplier.createHumanTask} before {@code tasks.insert}
 * runs — so no CM_TASK row was EVER written in either scenario, committed or not, and the
 * CM_TASK assertion could not fail no matter what {@code @Transactional} did. CM_WEBHOOK_DELIVERY
 * wasn't asserted at all, and no subscription was even seeded, so {@code EventPublisher.publish}
 * never reached {@code enqueueDelivery}. Both definitions below now activate TWO human tasks
 * where one gates on the other's completion but both fire in the SAME evaluator round: the
 * {@link FailingGateway} lets the first succeed — writing a real CM_TASK row and, via a webhook
 * subscription seeded in each test, a real CM_WEBHOOK_DELIVERY row — before failing on the
 * second, so both tables genuinely have something to roll back before the assertions run.
 */
class CaseServiceTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private CaseService cases;
    private FailingGateway gateway;
    private PlanItemRepository planItems;
    private final Actor alice = new Actor("alice", List.of("handlers"));

    @BeforeEach
    void setUp() throws Exception {
        deploy("/definitions/txn-create-demo.json");
        deploy("/definitions/txn-close-demo.json");

        ctx = springContext(CaseServiceTestConfig.class);
        cases = ctx.getBean(CaseService.class);
        gateway = ctx.getBean(FailingGateway.class);
        planItems = new PlanItemRepository(jdbc());

        // Matches every event for tenant t1, so any event genuinely published mid-transaction
        // (case.created, task.created, plan-item transitions...) enqueues a real
        // CM_WEBHOOK_DELIVERY row that rollback then has to actually remove.
        new WebhookRepository(jdbc()).insert(CaseIds.newId(), "t1", "http://localhost/hook",
                List.of("*"), "hash", 5);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private void deploy(String resourcePath) throws Exception {
        String json = new String(getClass().getResourceAsStream(resourcePath).readAllBytes(),
                StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");
    }

    @Test
    void createRollsBackEverythingOnAMidCreateEngineFailure() {
        // "taskA" and "taskB" are both ungated and top-level, so create()'s reevaluate() admits
        // both AVAILABLE -> ACTIVE in the SAME evaluator round; TransitionApplier then processes
        // them in order. Letting taskA succeed first — a real CM_TASK row, a real
        // CM_WEBHOOK_DELIVERY row via the subscription seeded in setUp() — before failing on
        // taskB is what makes the CM_TASK/CM_WEBHOOK_DELIVERY assertions below capable of
        // failing at all, unlike failing on the very first (and, before this fix, only) engine
        // call this test made.
        gateway.failOnTaskNamed("taskB");

        assertThatThrownBy(() -> cases.create("txn-create-demo", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAll("CM_CASE")).isZero();
        assertThat(countAll("CM_PARTICIPANT")).isZero();
        assertThat(countAll("CM_PLAN_ITEM")).isZero();
        assertThat(countAll("CM_EVENT")).isZero();
        assertThat(countAll("CM_AUDIT_LOG")).isZero();
        assertThat(countAll("CM_TASK")).isZero();
        assertThat(countAll("CM_WEBHOOK_DELIVERY")).isZero();
    }

    @Test
    void closeRollsBackEverythingOnAMidCloseEngineFailure() {
        // Let create() succeed first (only "gate" activates, and the gateway isn't failing yet),
        // then complete "gate" directly so close() is legally allowed to proceed. Closing then
        // re-evaluates the plan model (Important 2's fix): "followupA" and "followupB" both
        // become eligible in the same round. The gateway lets followupA succeed — a real
        // CM_TASK row, a real CM_WEBHOOK_DELIVERY row — before failing on followupB, so this
        // transaction genuinely has rows in both tables to roll back, not just plan-item state.
        gateway.neverFail();
        CaseInstance created = cases.create("txn-close-demo", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        PlanItem gate = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("gate")).findFirst().orElseThrow();
        planItems.updateState(gate.withState(PlanItemState.COMPLETED), gate.version());
        int taskCountBeforeClose = countAll("CM_TASK");
        int eventCountBeforeClose = countAll("CM_EVENT");
        int deliveryCountBeforeClose = countAll("CM_WEBHOOK_DELIVERY");

        gateway.failOnTaskNamed("followupB");
        long versionBeforeClose = cases.get(created.id()).version();

        assertThatThrownBy(() -> cases.close(created.id(), versionBeforeClose, "approved", alice))
                .isInstanceOf(IllegalStateException.class);

        CaseInstance reloaded = cases.get(created.id());
        assertThat(reloaded.state()).isEqualTo(CaseState.ACTIVE);
        assertThat(reloaded.outcome()).isNull();
        assertThat(reloaded.closedAt()).isNull();
        assertThat(reloaded.version()).isEqualTo(versionBeforeClose);

        // Nothing close()'s reevaluate()/sweep did — followupA's plan-item transition, its
        // CM_TASK row, its CM_WEBHOOK_DELIVERY row, the case.closed event, the case.close audit
        // row — survives the rollback.
        assertThat(countAll("CM_TASK")).isEqualTo(taskCountBeforeClose);
        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBeforeClose);
        assertThat(countAll("CM_WEBHOOK_DELIVERY")).isEqualTo(deliveryCountBeforeClose);
        assertThat(countWhere("CM_AUDIT_LOG", "ACTION_ = 'case.close'")).isZero();

        PlanItem followupA = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("followupA")).findFirst().orElseThrow();
        PlanItem followupB = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("followupB")).findFirst().orElseThrow();
        assertThat(followupA.state()).isEqualTo(PlanItemState.AVAILABLE);
        assertThat(followupB.state()).isEqualTo(PlanItemState.AVAILABLE);
    }

    private int countAll(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private int countWhere(String table, String predicate) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)
                .query(Integer.class).single();
    }

    /**
     * Registers the REAL {@link CaseService} as a Spring {@code @Bean} so
     * {@code TransactionManagerConfig}'s auto-proxy creator wraps it — the only way its
     * {@code @Transactional} annotations do anything. Delegates construction to
     * {@link TestServices#caseService} so this wiring never drifts from what
     * {@link CaseServiceTest} already uses; only the {@link EngineGateway} differs (a
     * controllable {@link FailingGateway} instead of the recording one).
     */
    @Configuration
    static class CaseServiceTestConfig {
        @Bean
        FailingGateway failingGateway() {
            return new FailingGateway();
        }

        @Bean
        CaseService caseService(DataSource dataSource, FailingGateway gateway) {
            return TestServices.caseService(dataSource, gateway);
        }
    }

    /** An {@link EngineGateway} whose {@code createHumanTask} failure is controlled per test. */
    static class FailingGateway implements EngineGateway {
        private volatile Predicate<String> failOn = name -> false;

        void neverFail() { failOn = name -> false; }
        void failAlways() { failOn = name -> true; }
        void failOnTaskNamed(String name) { failOn = name::equals; }

        @Override
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            if (failOn.test(r.name())) {
                throw new IllegalStateException("simulated engine failure creating task '" + r.name() + "'");
            }
            return new EngineTaskRef("engine-" + UUID.randomUUID(), r.name(), r.assignee(), r.caseId(), null);
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
}
