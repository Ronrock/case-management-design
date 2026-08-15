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
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

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
    void createActivatesProcessTaskAndStartsLinkedProcess() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/process-task-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

        CaseInstance created = cases.create("process-task-demo", "t1", null, "T",
                CasePriority.MEDIUM, Map.of("customerId", "C-1"), alice);

        PlanItem processTask = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("generate-letter"))
                .findFirst().orElseThrow();
        assertThat(processTask.state()).isEqualTo(PlanItemState.ACTIVE);

        assertThat(gateway.startedProcesses).containsExactly("generic-process");

        List<LinkedProcessRepository.LinkedProcessRow> linkedProcesses =
                new LinkedProcessRepository(jdbc()).findByCase(created.id());
        assertThat(linkedProcesses).singleElement().satisfies(row -> {
            assertThat(row.planItemId()).isEqualTo(processTask.id());
            assertThat(row.processDefinitionKey()).isEqualTo("generic-process");
            assertThat(row.processInstanceId()).isEqualTo("proc-1");
            assertThat(row.engineSync()).isEqualTo(CaseTask.EngineSync.SYNCED);
        });

        assertThat(jdbc().sql("""
                SELECT DATA_JSON_ FROM CM_EVENT
                WHERE SUBJECT_ = :caseId AND TYPE_ LIKE '%case.process.started'""")
                .param("caseId", created.id())
                .query(String.class)
                .list()
                .stream()
                .map(JsonCodec::toMap))
                .anySatisfy(data -> {
                    assertThat(data.get("planItemId")).isEqualTo(processTask.id());
                    assertThat(data.get("processDefinitionKey")).isEqualTo("generic-process");
                });
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

    /**
     * Task 15 review, Important 2: close() must bring the plan model into line the way cancel()
     * already does. Completing "review" satisfies "reviewed"'s entry criterion
     * (${items.review.state == 'COMPLETED'}) but nothing evaluates it before close() runs this
     * fix — so before the fix, "reviewed" was left AVAILABLE forever under a CLOSED case with no
     * CM_MILESTONE row. Now it must be properly achieved (not merely swept to TERMINATED, which
     * would misrecord a genuinely-satisfied milestone as if it had been aborted), and no plan
     * item may be left AVAILABLE or ACTIVE.
     */
    @Test
    void closeResolvesTheMilestoneAndLeavesNoOpenPlanItem() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);
        PlanItem review = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("review")).findFirst().orElseThrow();
        planItems.updateState(review.withState(PlanItemState.COMPLETED), review.version());

        cases.close(created.id(), cases.get(created.id()).version(), "approved", alice);

        List<PlanItem> items = planItems.findByCase(created.id());
        assertThat(items).allMatch(i -> i.state().isEnded());

        PlanItem reviewed = items.stream()
                .filter(i -> i.name().equals("reviewed")).findFirst().orElseThrow();
        assertThat(reviewed.state()).isEqualTo(PlanItemState.COMPLETED);

        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_MILESTONE WHERE CASE_ID_ = :id")
                .param("id", created.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT TYPE_ FROM CM_EVENT WHERE SUBJECT_ = :id")
                .param("id", created.id()).query(String.class).list())
                .anyMatch(t -> t.endsWith("case.milestone.achieved"));
    }

    /**
     * Task 15 review round 2, Important 1: a swept termination — a plan item close() (or
     * cancel()) ends outright rather than via a "real" evaluator transition — must still
     * produce the same {@code case.planitem.transitioned} event every other transition does.
     * The first cut routed the sweep through a raw {@code planItems.updateState(...)}, which is
     * a plain UPDATE with no event and no audit row: a consumer replaying case history from the
     * event stream (the federation contract, spec §6.2) would see "followup" simply vanish with
     * no explanation. Uses close-sweep-demo.json (gate required; followup entry-gated on gate
     * COMPLETED) so close()'s own reevaluate() activates "followup" — making it a live ACTIVE
     * item the sweep, not the evaluator, has to terminate.
     */
    @Test
    void closeSweepsALeftoverActiveItemAndEmitsATransitionedEventForIt() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/close-sweep-demo.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

        CaseInstance created = cases.create("close-sweep-demo", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);
        PlanItem gate = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("gate")).findFirst().orElseThrow();
        planItems.updateState(gate.withState(PlanItemState.COMPLETED), gate.version());

        cases.close(created.id(), cases.get(created.id()).version(), "approved", alice);

        // close()'s own reevaluate() activates "followup" (its entry criterion now holds); the
        // sweep then terminates it because close() cannot leave a live plan item behind.
        PlanItem followup = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("followup")).findFirst().orElseThrow();
        assertThat(followup.state()).isEqualTo(PlanItemState.TERMINATED);

        List<Map<String, Object>> transitionEvents = jdbc().sql("""
                SELECT DATA_JSON_ FROM CM_EVENT
                WHERE SUBJECT_ = :id AND TYPE_ LIKE '%case.planitem.transitioned' ORDER BY SEQ_""")
                .param("id", created.id())
                .query(String.class).list().stream()
                .map(JsonCodec::toMap)
                .toList();

        assertThat(transitionEvents).anySatisfy(data -> {
            assertThat(data.get("planItemId")).isEqualTo(followup.id());
            assertThat(data.get("to")).isEqualTo("TERMINATED");
            assertThat(data.get("reason")).isEqualTo("case closed with plan item still open");
        });
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

    /**
     * Companion to {@link #closeSweepsALeftoverActiveItemAndEmitsATransitionedEventForIt} for
     * {@link CaseService#cancel}'s own (pre-existing) sweep. Straight after create(), "setupStage"
     * and "review" are ACTIVE and "reviewed" is AVAILABLE — none ended — so cancel() sweeps all
     * three; every one of them must produce its own transitioned event with reason
     * "case cancelled", not just the single case-level case.cancelled event.
     */
    @Test
    void cancelEmitsATransitionedEventForEverySweptPlanItem() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        cases.cancel(created.id(), created.version(), "duplicate", alice);

        List<PlanItem> items = planItems.findByCase(created.id());
        assertThat(items).allMatch(i -> i.state().isEnded());

        List<Map<String, Object>> transitionEvents = jdbc().sql("""
                SELECT DATA_JSON_ FROM CM_EVENT
                WHERE SUBJECT_ = :id AND TYPE_ LIKE '%case.planitem.transitioned' ORDER BY SEQ_""")
                .param("id", created.id())
                .query(String.class).list().stream()
                .map(JsonCodec::toMap)
                .toList();

        for (PlanItem item : items) {
            assertThat(transitionEvents).anySatisfy(data -> {
                assertThat(data.get("planItemId")).isEqualTo(item.id());
                assertThat(data.get("to")).isEqualTo("TERMINATED");
                assertThat(data.get("reason")).isEqualTo("case cancelled");
            });
        }
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
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

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
        record CompletedTask(String engineTaskId, Map<String, Object> variables) {}

        final List<HumanTaskRequest> created = new java.util.ArrayList<>();
        final List<String> startedProcesses = new java.util.ArrayList<>();
        final List<CompletedTask> completedTasks = new java.util.ArrayList<>();
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            created.add(r);
            return new EngineTaskRef("engine-" + created.size(), r.name(), r.assignee(), r.caseId(), null);
        }
        public void claimTask(String id, String user) {}
        public void completeTask(String id, Map<String, Object> v) {
            completedTasks.add(new CompletedTask(id, v == null ? null : Map.copyOf(v)));
        }
        public EngineProcessRef startProcess(StartProcessRequest r) {
            startedProcesses.add(r.processDefinitionKey());
            return new EngineProcessRef("proc-1", r.processDefinitionKey(), r.caseId());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }
}
