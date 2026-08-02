package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.PlanItemRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Companion to {@code CaseServiceTransactionalIntegrationTest} / {@code
 * PlanItemServiceTransactionalIntegrationTest} for {@link CaseTaskService}: proves its {@code
 * @Transactional} annotations on {@code claim}/{@code complete} are load-bearing, not inert.
 *
 * <p>{@link CaseTaskServiceTest} — like every other repository/service test in this module —
 * builds {@link CaseTaskService} with a plain {@code new}, which never puts it behind the Spring
 * AOP proxy that actually opens/commits/rolls back a transaction (see {@code
 * TransactionManagerConfig}). A plain-{@code new} test can prove {@code claim}/{@code complete}'s
 * SQL is correct; it cannot prove that a failing engine call partway through leaves nothing
 * committed. This test puts the REAL {@link CaseTaskService} bean under test via {@link
 * OracleTestBase#springContext}, the same pattern the other two use, and fails the engine calls
 * {@code claim}/{@code complete} make directly (not a side effect further downstream), since both
 * are called before any row for that action is written — exactly where a rollback matters most.
 *
 * <p><b>Mutation-tested manually</b>: temporarily removing {@code @Transactional} from {@code
 * claim} and from {@code complete} and re-running this class makes the corresponding assertions
 * fail — the CM_TASK row is left CLAIMED/COMPLETED and its version bumped even though the action
 * as a whole "failed" — confirming this test actually exercises the annotations.
 */
class CaseTaskServiceTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private CaseTaskService taskService;
    private CaseService cases;
    private FailingGateway gateway;
    private CaseTaskRepository tasks;
    private PlanItemRepository planItems;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;
    private CaseTask reviewTask;

    @BeforeEach
    void setUp() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

        ctx = springContext(CaseTaskServiceTestConfig.class);
        taskService = ctx.getBean(CaseTaskService.class);
        cases = ctx.getBean(CaseService.class);
        gateway = ctx.getBean(FailingGateway.class);
        tasks = new CaseTaskRepository(jdbc());
        planItems = new PlanItemRepository(jdbc());

        gateway.neverFail();
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
        reviewTask = tasks.findByCase(caseId).get(0);
        assertThat(reviewTask.state()).isEqualTo(TaskState.OPEN);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void claimRollsBackWhenTheEngineClaimFails() {
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");

        gateway.failOnClaim();

        assertThatThrownBy(() -> taskService.claim(reviewTask.id(), reviewTask.version(), alice))
                .isInstanceOf(IllegalStateException.class);

        CaseTask reloaded = tasks.require(reviewTask.id());
        assertThat(reloaded.state()).isEqualTo(TaskState.OPEN);
        assertThat(reloaded.assignee()).isNull();
        assertThat(reloaded.version()).isEqualTo(reviewTask.version());

        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
    }

    @Test
    void completeRollsBackWhenTheEngineCompleteFails() {
        CaseTask claimed = taskService.claim(reviewTask.id(), reviewTask.version(), alice);
        PlanItem planItemBefore = planItems.require(claimed.planItemId());
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");
        int milestoneCountBefore = countAll("CM_MILESTONE");

        gateway.failOnComplete();

        assertThatThrownBy(() -> taskService.complete(claimed.id(), claimed.version(),
                Map.of("outcome", "approve"), alice))
                .isInstanceOf(IllegalStateException.class);

        CaseTask reloaded = tasks.require(claimed.id());
        assertThat(reloaded.state()).isEqualTo(TaskState.CLAIMED);
        assertThat(reloaded.version()).isEqualTo(claimed.version());

        PlanItem planItemAfter = planItems.require(claimed.planItemId());
        assertThat(planItemAfter.state()).isEqualTo(planItemBefore.state());
        assertThat(planItemAfter.version()).isEqualTo(planItemBefore.version());

        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
        assertThat(countAll("CM_MILESTONE")).isEqualTo(milestoneCountBefore);
    }

    private int countAll(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /**
     * Registers the REAL {@link CaseTaskService} (and the {@link CaseService} it needs for
     * fixture setup) as Spring {@code @Bean}s so {@code TransactionManagerConfig}'s auto-proxy
     * creator wraps them. Delegates construction to {@link TestServices} so this wiring never
     * drifts from what {@link CaseTaskServiceTest} already uses; only the {@link EngineGateway}
     * differs (a controllable {@link FailingGateway} instead of the recording one).
     */
    @Configuration
    static class CaseTaskServiceTestConfig {
        @Bean
        FailingGateway failingGateway() {
            return new FailingGateway();
        }

        @Bean
        CaseService caseService(DataSource dataSource, FailingGateway gateway) {
            return TestServices.caseService(dataSource, gateway);
        }

        @Bean
        CaseTaskService taskService(DataSource dataSource, FailingGateway gateway) {
            return TestServices.taskService(dataSource, gateway);
        }
    }

    /**
     * An {@link EngineGateway} whose {@code claimTask}/{@code completeTask} failures are
     * controlled per test. {@code createHumanTask} always succeeds (fixture setup needs it),
     * unlike {@code CaseServiceTransactionalIntegrationTest.FailingGateway}, which controls
     * that call instead — this class targets the two engine calls {@link CaseTaskService} itself
     * makes directly.
     */
    static class FailingGateway implements EngineGateway {
        private volatile boolean failClaim = false;
        private volatile boolean failComplete = false;

        void neverFail() { failClaim = false; failComplete = false; }
        void failOnClaim() { failClaim = true; }
        void failOnComplete() { failComplete = true; }

        @Override
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            return new EngineTaskRef("engine-" + UUID.randomUUID(), r.name(), r.assignee(), r.caseId(), null);
        }

        @Override
        public void claimTask(String id, String user) {
            if (failClaim) {
                throw new IllegalStateException("simulated engine failure claiming task '" + id + "'");
            }
        }

        @Override
        public void completeTask(String id, Map<String, Object> variables) {
            if (failComplete) {
                throw new IllegalStateException("simulated engine failure completing task '" + id + "'");
            }
        }

        @Override
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-1", r.processDefinitionKey());
        }

        @Override public void cancelProcess(String id, String reason) {}
        @Override public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }
}
