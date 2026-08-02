package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CaseServiceTest extends OracleTestBase {

    private CaseService cases;
    private PlanItemRepository planItems;
    private RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("handlers"));

    // No manual DELETEs here: OracleTestBase already wipes every CM_ table before each test
    // method via its own @BeforeEach (see CaseDefinitionServiceTest for the same convention).
    @BeforeEach
    void setUp() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

        gateway = new RecordingGateway();
        cases = TestServices.caseService(dataSource(), gateway);
        planItems = new PlanItemRepository(jdbc());
    }

    @Test
    void createStartsTheCaseAndInstantiatesThePlanModel() {
        CaseInstance created = cases.create("widget-review", "t1", "BK-1", "First",
                CasePriority.HIGH, Map.of("amount", 10), alice);

        assertThat(created.id()).startsWith("eng-test:");
        assertThat(created.state()).isEqualTo(CaseState.ACTIVE);
        assertThat(planItems.findByCase(created.id())).hasSize(3);
    }

    @Test
    void createActivatesUngatedItemsAndCreatesTheirEngineTasks() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        assertThat(planItems.findByCase(created.id()))
                .filteredOn(i -> i.name().equals("review"))
                .singleElement()
                .extracting(PlanItem::state).isEqualTo(PlanItemState.ACTIVE);

        assertThat(gateway.created).hasSize(1);
        assertThat(gateway.created.get(0).caseId()).isEqualTo(created.id());
    }

    @Test
    void createEmitsACaseCreatedEventAndAnAuditRow() {
        cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice);

        List<String> types = jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_")
                .query(String.class).list();
        assertThat(types).first().asString().endsWith("case.created");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG").query(Integer.class).single())
                .isGreaterThan(0);
    }

    @Test
    void closeIsRejectedWhileARequiredItemIsOpen() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        assertThatThrownBy(() -> cases.close(created.id(), created.version(), "done", alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("review");
    }

    @Test
    void closeSucceedsOnceRequiredItemsHaveEnded() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);
        PlanItem review = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("review")).findFirst().orElseThrow();
        planItems.updateState(review.withState(PlanItemState.COMPLETED), review.version());

        CaseInstance reloaded = cases.get(created.id());
        CaseInstance closed = cases.close(reloaded.id(), reloaded.version(), "approved", alice);

        assertThat(closed.state()).isEqualTo(CaseState.CLOSED);
        assertThat(closed.outcome()).isEqualTo("approved");
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void cancelFromAnyLiveStateIsAllowedAndTerminatesOpenPlanItems() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        CaseInstance cancelled = cases.cancel(created.id(), created.version(), "duplicate", alice);

        assertThat(cancelled.state()).isEqualTo(CaseState.CANCELLED);
        assertThat(cancelled.closedAt()).isNotNull();
        assertThat(planItems.findByCase(created.id()))
                .allMatch(i -> i.state().isEnded());
    }

    @Test
    void closingAnAlreadyClosedCaseConflicts() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);
        PlanItem review = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("review")).findFirst().orElseThrow();
        planItems.updateState(review.withState(PlanItemState.COMPLETED), review.version());
        CaseInstance closed = cases.close(created.id(), cases.get(created.id()).version(), "x", alice);

        assertThatThrownBy(() -> cases.close(closed.id(), closed.version(), "again", alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("CLOSED");
    }

    /**
     * Proves the repetition guard in {@code CaseService.reevaluate}. A repeatable MILESTONE is
     * the sharpest case: it completes the instant it enters, so its (here, empty — always
     * satisfied) entry criteria hold again immediately, and every {@code reevaluate} call —
     * including one triggered by a no-op {@link CaseService#update} — mints one more instance.
     * The first two assertions demonstrate that real (if unwanted) growth; the last one fast
     * -forwards past the cap without 500 real round trips and proves the guard actually stops it.
     */
    @Test
    void repetitionIsBoundedAgainstRunawayGrowth() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/repeatable-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

        CaseInstance created = cases.create("repeat-demo", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        // create()'s single reevaluate() call already: (1) completes the milestone on entry,
        // then (2) notices it just ended with criteria still holding and mints a second,
        // AVAILABLE instance — all within one call.
        List<PlanItem> afterCreate = planItems.findByCase(created.id());
        assertThat(afterCreate).hasSize(2);
        assertThat(afterCreate).allMatch(i -> i.name().equals("ping"));

        // A no-op update — no title, no variables, nothing business-meaningful — still forces
        // a reevaluate() cycle: the AVAILABLE instance completes and a third is minted. This is
        // exactly the "unbounded via ordinary API traffic" risk the guard exists for.
        cases.update(created.id(), cases.get(created.id()).version(), Map.of(), alice);
        assertThat(planItems.findByCase(created.id())).hasSize(3);

        // Fast-forward to the cap: replace the live AVAILABLE instance with one already at
        // MAX_REPETITIONS_PER_ITEM, ended, with criteria still satisfied — exactly the state a
        // 500th real no-op update would have produced.
        PlanItem atCap = planItems.findByCase(created.id()).stream()
                .max(Comparator.comparingInt(PlanItem::repetitionNo)).orElseThrow();
        planItems.updateState(atCap.withState(PlanItemState.TERMINATED), atCap.version());
        PlanItemDefinition pingDef = cases.snapshot(created.id()).definition().planItem("ping");
        OffsetDateTime now = OffsetDateTime.now();
        planItems.insert(new PlanItem(CaseIds.newId(), created.id(), pingDef.id(), PlanItemType.MILESTONE,
                "ping", PlanItemState.COMPLETED, null, false, CaseService.MAX_REPETITIONS_PER_ITEM,
                null, null, null, 0L, now, now, now));

        int countBefore = planItems.findByCase(created.id()).size();
        cases.reevaluate(created.id(), alice);
        int countAfter = planItems.findByCase(created.id()).size();

        assertThat(countAfter).isEqualTo(countBefore);
    }

    static class RecordingGateway implements EngineGateway {
        final List<HumanTaskRequest> created = new java.util.ArrayList<>();
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            created.add(r);
            return new EngineTaskRef("engine-" + created.size(), r.name(), r.assignee(), r.caseId(), null);
        }
        public void claimTask(String id, String user) {}
        public void completeTask(String id, Map<String, Object> v) {}
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-1", r.processDefinitionKey());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }
}
