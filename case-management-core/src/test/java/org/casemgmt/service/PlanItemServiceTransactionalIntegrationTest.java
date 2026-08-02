package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Companion to {@code CaseServiceTransactionalIntegrationTest} for {@link PlanItemService}:
 * proves its {@code @Transactional} annotations are load-bearing, not inert.
 *
 * <p>{@link PlanItemServiceTest} — like every other repository/service test in this module —
 * builds its services with a plain {@code new}, which never puts the bean behind the Spring AOP
 * proxy that actually opens/commits/rolls back a transaction (see {@code TransactionManagerConfig}).
 * A plain-{@code new} test can prove {@code start()}'s SQL is correct; it cannot prove that a
 * failure partway through {@code start()} — after the plan item's state has already been written,
 * but before its side effects (engine task, event, audit) complete — leaves nothing committed.
 * This test puts the REAL {@link PlanItemService} bean under test via {@link
 * OracleTestBase#springContext}, the same pattern {@code CaseServiceTransactionalIntegrationTest}
 * uses, and fails the engine call {@code start()}'s side effects make so there is something
 * genuine to roll back: the plan item's ENABLED-&gt;ACTIVE write, which happens before the
 * failing call.
 *
 * <p><b>Mutation-tested manually</b>: temporarily removing {@code @Transactional} from {@code
 * start()} and re-running this class makes the assertions below fail — the plan item is left
 * ACTIVE and the version bumped even though the action as a whole "failed" — confirming this test
 * actually exercises the annotation.
 */
class PlanItemServiceTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private PlanItemService planItemService;
    private CaseService cases;
    private CaseServiceTransactionalIntegrationTest.FailingGateway gateway;
    private PlanItemRepository planItems;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;
    private PlanItem manual;

    @BeforeEach
    void setUp() throws Exception {
        String json = """
                {"key":"txn-manual-model","name":"Manual","tenantId":"t1",
                 "planItems":[
                   {"defKey":"manual","type":"HUMAN_TASK","name":"manual","manualActivation":true,"sortOrder":10}]}""";
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

        ctx = springContext(PlanItemServiceTestConfig.class);
        planItemService = ctx.getBean(PlanItemService.class);
        cases = ctx.getBean(CaseService.class);
        gateway = ctx.getBean(CaseServiceTransactionalIntegrationTest.FailingGateway.class);
        planItems = new PlanItemRepository(jdbc());

        // Matches every event for tenant t1, so a genuinely-published start() event enqueues a
        // real CM_WEBHOOK_DELIVERY row that rollback then has to actually remove.
        new WebhookRepository(jdbc()).insert(CaseIds.newId(), "t1", "http://localhost/hook",
                List.of("*"), "hash", 5);

        gateway.neverFail();
        caseId = cases.create("txn-manual-model", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
        manual = planItems.findByCase(caseId).stream()
                .filter(i -> i.name().equals("manual")).findFirst().orElseThrow();
        assertThat(manual.state()).isEqualTo(PlanItemState.ENABLED);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void startRollsBackTheStateWriteWhenTheEngineTaskCreationFails() {
        int eventCountBefore = countAll("CM_EVENT");
        int auditCountBefore = countAll("CM_AUDIT_LOG");
        int taskCountBefore = countAll("CM_TASK");
        int deliveryCountBefore = countAll("CM_WEBHOOK_DELIVERY");

        gateway.failAlways();

        assertThatThrownBy(() -> planItemService.start(caseId, manual.id(), manual.version(), alice))
                .isInstanceOf(IllegalStateException.class);

        PlanItem reloaded = planItems.require(manual.id());
        assertThat(reloaded.state()).isEqualTo(PlanItemState.ENABLED);
        assertThat(reloaded.version()).isEqualTo(manual.version());

        assertThat(countAll("CM_EVENT")).isEqualTo(eventCountBefore);
        assertThat(countAll("CM_AUDIT_LOG")).isEqualTo(auditCountBefore);
        assertThat(countAll("CM_TASK")).isEqualTo(taskCountBefore);
        assertThat(countAll("CM_WEBHOOK_DELIVERY")).isEqualTo(deliveryCountBefore);
    }

    private int countAll(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /**
     * Registers the REAL {@link PlanItemService} (and the {@link CaseService} it depends on) as
     * Spring {@code @Bean}s so {@code TransactionManagerConfig}'s auto-proxy creator wraps them —
     * the only way their {@code @Transactional} annotations do anything. Delegates construction
     * to {@link TestServices} so this wiring never drifts from what {@link PlanItemServiceTest}
     * already uses; only the {@link EngineGateway} differs (a controllable {@link FailingGateway}
     * instead of the recording one).
     */
    @Configuration
    static class PlanItemServiceTestConfig {
        @Bean
        CaseServiceTransactionalIntegrationTest.FailingGateway failingGateway() {
            return new CaseServiceTransactionalIntegrationTest.FailingGateway();
        }

        @Bean
        CaseService caseService(DataSource dataSource, CaseServiceTransactionalIntegrationTest.FailingGateway gateway) {
            return TestServices.caseService(dataSource, gateway);
        }

        @Bean
        PlanItemService planItemService(DataSource dataSource, CaseServiceTransactionalIntegrationTest.FailingGateway gateway) {
            return TestServices.planItemService(dataSource, gateway);
        }
    }
}
