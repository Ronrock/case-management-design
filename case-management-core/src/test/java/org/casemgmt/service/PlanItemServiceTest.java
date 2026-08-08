package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PlanItemServiceTest extends OracleTestBase {

    private CaseService cases;
    private PlanItemService planItemService;
    private PlanItemRepository planItems;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    // No manual DELETEs here: OracleTestBase already wipes every CM_ table before each test
    // method via its own @BeforeEach (see CaseDefinitionServiceTest for the same convention).
    @BeforeEach
    void setUp() throws Exception {
        // A model with one manual-activation item, so enable/start are meaningful.
        String json = """
                {"key":"manual-model","name":"Manual","tenantId":"t1",
                 "planItems":[
                   {"defKey":"manual","type":"HUMAN_TASK","name":"Manual","manualActivation":true,"sortOrder":10},
                   {"defKey":"auto","type":"HUMAN_TASK","name":"Auto","sortOrder":20}]}""";
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(dataSource(), gateway);
        planItemService = TestServices.planItemService(dataSource(), gateway);
        planItems = new PlanItemRepository(jdbc());
        caseId = cases.create("manual-model", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    private PlanItem item(String defKey) {
        return planItems.findByCase(caseId).stream()
                .filter(i -> i.name().equals(defKey)).findFirst().orElseThrow();
    }

    @Test
    void manualItemsStartEnabledAndCanBeStarted() {
        PlanItem manual = item("manual");
        assertThat(manual.state()).isEqualTo(PlanItemState.ENABLED);

        PlanItem started = planItemService.start(caseId, manual.id(), manual.version(), alice);

        assertThat(started.state()).isEqualTo(PlanItemState.ACTIVE);
    }

    @Test
    void startingAnItemCreatesItsEngineTask() {
        PlanItem manual = item("manual");
        int before = gateway.created.size();

        planItemService.start(caseId, manual.id(), manual.version(), alice);

        assertThat(gateway.created).hasSize(before + 1);
    }

    @Test
    void startingAnAvailableItemConflicts() {
        // 'auto' is already ACTIVE — starting it again is illegal.
        PlanItem auto = item("auto");

        assertThatThrownBy(() -> planItemService.start(caseId, auto.id(), auto.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void completingAnActiveItemEndsIt() {
        PlanItem auto = item("auto");

        PlanItem completed = planItemService.complete(caseId, auto.id(), auto.version(), alice);

        assertThat(completed.state()).isEqualTo(PlanItemState.COMPLETED);
        assertThat(completed.endedAt()).isNotNull();
    }

    @Test
    void terminateWorksFromAnyLiveStateAndRecordsTheReason() {
        PlanItem manual = item("manual");

        PlanItem terminated = planItemService.terminate(caseId, manual.id(), manual.version(),
                "not needed", alice);

        assertThat(terminated.state()).isEqualTo(PlanItemState.TERMINATED);
        assertThat(terminated.terminationReason()).isEqualTo("not needed");
    }

    /**
     * Review fix: the original {@code staleVersionsAreRejected} (inherited from the task brief)
     * completed the item first, so by the time {@code terminate} ran the item was already
     * COMPLETED — an ENDED state, not in {@code TERMINABLE} — and the throw came from the
     * legal-state check ({@code code=illegal-transition}), never from the
     * {@code OptimisticLockException -> CaseConflictException("version-conflict")} mapping at
     * {@code PlanItemService.transition}. That mapping had no coverage at all: a rethrow that
     * dropped the code, or a raw {@code OptimisticLockException} leaking past the service
     * boundary, would have passed the old test.
     *
     * <p>This version keeps 'manual' in ENABLED — a state that IS still legal for {@code
     * terminate} — and bumps its version out from under the held reference via a direct
     * same-state {@code planItems.updateState} call (standing in for a concurrent writer), so
     * the {@code expectedVersion} the test then passes to {@code terminate} is stale while the
     * state check passes cleanly. That reaches the optimistic-lock catch block specifically, and
     * the assertion pins the resulting code to {@code "version-conflict"} rather than merely
     * {@code CaseConflictException} — which is the whole point of having two distinct codes.
     * Confirmed to fail (during development) if {@code PlanItemService}'s catch block is changed
     * to rethrow without the code, e.g. {@code new CaseConflictException(null, e.getMessage(),
     * List.of())} — the "illegal-transition" code from a same-state check would not fool this
     * assertion either, since it names the exact string.
     */
    @Test
    void staleVersionAgainstAStillLegalStateYieldsVersionConflict() {
        PlanItem manual = item("manual");
        assertThat(manual.state()).isEqualTo(PlanItemState.ENABLED);

        // Simulates a concurrent writer: same state, but the UPDATE always bumps VERSION_, so
        // the row now expects manual.version() + 1 while `manual` (and the version the test is
        // about to pass to terminate) still names the old one.
        planItems.updateState(manual, manual.version());

        assertThatThrownBy(() -> planItemService.terminate(caseId, manual.id(), manual.version(), "x", alice))
                .isInstanceOf(CaseConflictException.class)
                .extracting(e -> ((CaseConflictException) e).code())
                .isEqualTo("version-conflict");

        // And the row itself is untouched by the rejected call.
        assertThat(planItems.require(manual.id()).state()).isEqualTo(PlanItemState.ENABLED);
    }

    /**
     * Review fix: the original assertion ({@code after > before}) only proved SOME event was
     * appended — it would still pass if the wrong event type, or an event for the wrong plan
     * item, were emitted. This checks the actual {@code case.planitem.transitioned} event for
     * this specific manual action.
     *
     * <p>Filters on {@code reason == "manual action"} (the literal {@code transition()} stamps
     * on every manual transition), not just {@code planItemId}: 'manual' already has ONE
     * transitioned event from case creation (its own AVAILABLE -&gt; ENABLED entry-criterion
     * admission), so filtering on {@code planItemId} alone would see two events and the "exactly
     * one" assertion would be testing the wrong thing. Filtering on the manual-action reason
     * isolates precisely the event this test's own call produced.
     */
    @Test
    void startingAnItemEmitsExactlyOneTransitionedEventWithTheRightShape() {
        PlanItem manual = item("manual");

        planItemService.start(caseId, manual.id(), manual.version(), alice);

        List<Map<String, Object>> manualActionEvents = jdbc().sql("""
                SELECT DATA_JSON_ FROM CM_EVENT
                WHERE SUBJECT_ = :id AND TYPE_ LIKE '%case.planitem.transitioned' ORDER BY SEQ_""")
                .param("id", caseId)
                .query(String.class).list().stream()
                .map(JsonCodec::toMap)
                .filter(data -> manual.id().equals(data.get("planItemId")))
                .filter(data -> "manual action".equals(data.get("reason")))
                .toList();

        assertThat(manualActionEvents).hasSize(1);
        assertThat(manualActionEvents.get(0)).containsEntry("from", "ENABLED").containsEntry("to", "ACTIVE");
    }

    // ---------------------------------------------------------------------------------------
    // Final whole-branch review, Important 2: the manual plan-item path bypassed StageCompletion
    // entirely. The orphan invariant had dedicated StageCompletionTest cases, three review rounds
    // and prominent Javadoc — but every one of those tests exercised only the pure rules layer,
    // and the two tests above this block complete and terminate only LEAF items. No test anywhere
    // completed or terminated a STAGE with live children through the service. These do.
    // ---------------------------------------------------------------------------------------

    /**
     * A nested model with a live subtree, plus one child that can never enter on its own —
     * {@code late}'s entry criterion depends on {@code leaf} completing, which these tests never
     * do, so it sits at AVAILABLE. That item is what makes the wedge in
     * {@link #aCaseWhoseStageWasManuallyTerminatedCanStillClose} real: it is {@code required},
     * and {@code StageCompletion.isContained} is consulted ONLY on the AVAILABLE-&gt;entry edge,
     * so once its parent stops being ACTIVE it can never enter and never end.
     *
     * <p>Settles to: {@code stage} ACTIVE, {@code sub} ACTIVE, {@code leaf} ACTIVE,
     * {@code late} AVAILABLE — asserted in {@link #nestedCase()} so every test below starts from
     * a state it has actually verified rather than assumed.
     */
    private static final String NESTED_MODEL = """
            {"key":"nested-model","name":"Nested","tenantId":"t1",
             "planItems":[
               {"defKey":"stage","type":"STAGE","name":"stage","sortOrder":10},
               {"defKey":"sub","type":"STAGE","name":"sub","parentStageKey":"stage","sortOrder":20},
               {"defKey":"leaf","type":"HUMAN_TASK","name":"leaf","parentStageKey":"sub",
                "required":true,"sortOrder":30},
               {"defKey":"late","type":"HUMAN_TASK","name":"late","parentStageKey":"stage",
                "required":true,"entryCriteria":["${items.leaf.state == 'COMPLETED'}"],
                "sortOrder":40}]}""";

    private String nestedCase() throws Exception {
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource()))
                .deploy(NESTED_MODEL, "system", "t1");
        String id = cases.create("nested-model", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice).id();
        assertThat(statesOf(id)).containsOnly(
                entry("stage", PlanItemState.ACTIVE), entry("sub", PlanItemState.ACTIVE),
                entry("leaf", PlanItemState.ACTIVE), entry("late", PlanItemState.AVAILABLE));
        return id;
    }

    private Map<String, PlanItemState> statesOf(String id) {
        return planItems.findByCase(id).stream()
                .collect(java.util.stream.Collectors.toMap(PlanItem::name, PlanItem::state));
    }

    private PlanItem itemOf(String id, String name) {
        return planItems.findByCase(id).stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * Manually completing a STAGE that still has live descendants must be refused, not silently
     * produce a COMPLETED stage over ACTIVE children — the exact orphan shape
     * {@code StageCompletion.blockingItems} exists to prevent on the automatic path, which the
     * manual path never consulted.
     *
     * <p>Attribution, not just outcome: the assertion pins the {@code code} to
     * {@code blocking-items-open} (a legal-source-state refusal would be
     * {@code illegal-transition}, a stale version {@code version-conflict}), names the blocking
     * items in the message, AND checks that the whole subtree is untouched — a refusal that
     * happened AFTER a partial write would fail the second half.
     */
    @Test
    void completingAStageWithLiveDescendantsIsRefusedAndWritesNothing() throws Exception {
        String id = nestedCase();
        PlanItem stage = itemOf(id, "stage");

        assertThatThrownBy(() -> planItemService.complete(id, stage.id(), stage.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .extracting(e -> ((CaseConflictException) e).code())
                .isEqualTo("blocking-items-open");

        assertThat(statesOf(id)).containsOnly(
                entry("stage", PlanItemState.ACTIVE), entry("sub", PlanItemState.ACTIVE),
                entry("leaf", PlanItemState.ACTIVE), entry("late", PlanItemState.AVAILABLE));
    }

    /**
     * Manually terminating a STAGE must cascade to the WHOLE remaining subtree — grandchildren
     * included, which is where the automatic path's own first cut left an orphan (Task 9 second
     * re-review) — and must do it as real transitions, so every swept item appears in the event
     * stream that is the federation contract, not as a silent UPDATE.
     */
    @Test
    void terminatingAStageCascadeTerminatesEveryLiveDescendantAtEveryDepth() throws Exception {
        String id = nestedCase();
        PlanItem stage = itemOf(id, "stage");

        planItemService.terminate(id, stage.id(), stage.version(), "abandoned", alice);

        assertThat(statesOf(id)).containsOnly(
                entry("stage", PlanItemState.TERMINATED), entry("sub", PlanItemState.TERMINATED),
                entry("leaf", PlanItemState.TERMINATED), entry("late", PlanItemState.TERMINATED));

        // Not a raw UPDATE: every cascade-terminated descendant produced its own transitioned
        // event, the same as any other transition in this codebase.
        List<Map<String, Object>> cascade = jdbc().sql("""
                SELECT DATA_JSON_ FROM CM_EVENT
                WHERE SUBJECT_ = :id AND TYPE_ LIKE '%case.planitem.transitioned' ORDER BY SEQ_""")
                .param("id", id).query(String.class).list().stream()
                .map(JsonCodec::toMap)
                .filter(d -> "parent plan item terminated".equals(d.get("reason")))
                .toList();
        assertThat(cascade).extracting(d -> d.get("defKey"))
                .containsExactlyInAnyOrder("sub", "leaf", "late");
        assertThat(cascade).allSatisfy(d -> assertThat(d).containsEntry("to", "TERMINATED"));
    }

    /**
     * The consequence half of the finding, end to end: without the cascade above, {@code late}
     * — {@code required} and AVAILABLE under a now-TERMINATED parent — could never enter (its
     * containment gate is consulted only on the AVAILABLE-&gt;entry edge) and never end, so
     * {@code StageCompletion.caseBlockers} refused the close forever. The case was wedged with
     * no API path out. This asserts the way out exists.
     */
    @Test
    void aCaseWhoseStageWasManuallyTerminatedCanStillClose() throws Exception {
        String id = nestedCase();
        PlanItem stage = itemOf(id, "stage");
        planItemService.terminate(id, stage.id(), stage.version(), "abandoned", alice);

        long version = cases.get(id).version();
        assertThat(cases.close(id, version, "abandoned", alice).state()).isEqualTo(CaseState.CLOSED);
    }

    /**
     * {@code enable} never checked containment. The class Javadoc used to argue no check was
     * needed because an item could only reach ENABLED under an ACTIVE parent — true of the
     * evaluator's path, false once {@code enable} is manually invokable on ANY AVAILABLE item
     * regardless of its parent's state.
     *
     * <p>{@code mchild} here is AVAILABLE beneath a manual-activation stage sitting at ENABLED
     * — a state the model reaches on its own, with no test seam involved.
     */
    @Test
    void enablingAChildOfAStageThatIsNotActiveIsRefused() throws Exception {
        String id = manualStageCase();
        PlanItem mchild = itemOf(id, "mchild");
        assertThat(itemOf(id, "mstage").state()).isEqualTo(PlanItemState.ENABLED);

        assertThatThrownBy(() -> planItemService.enable(id, mchild.id(), mchild.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .extracting(e -> ((CaseConflictException) e).code())
                .isEqualTo("parent-stage-not-active");

        assertThat(itemOf(id, "mchild").state()).isEqualTo(PlanItemState.AVAILABLE);
    }

    /**
     * The same check on {@code start}. With {@code enable} now refusing, no ordinary API
     * sequence can reach ENABLED under a non-ACTIVE parent — so this drives the row there
     * directly, standing in for any OTHER writer of that state (a future action, a repair
     * script, a bug). That is the whole lesson of this finding: the invariant was well tested
     * on one path into the state and untested on a second, so the check has to hold regardless
     * of how the state was reached, and the test has to reach it the way the check's caller
     * cannot.
     */
    @Test
    void startingAChildOfAStageThatIsNotActiveIsRefusedHoweverItReachedEnabled() throws Exception {
        String id = manualStageCase();
        PlanItem mchild = itemOf(id, "mchild");
        planItems.updateState(mchild.withState(PlanItemState.ENABLED), mchild.version());
        PlanItem enabled = itemOf(id, "mchild");
        assertThat(enabled.state()).isEqualTo(PlanItemState.ENABLED);

        assertThatThrownBy(() -> planItemService.start(id, enabled.id(), enabled.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .extracting(e -> ((CaseConflictException) e).code())
                .isEqualTo("parent-stage-not-active");

        assertThat(itemOf(id, "mchild").state()).isEqualTo(PlanItemState.ENABLED);
    }

    /**
     * The negative control for the two containment tests: once the parent stage IS ACTIVE, the
     * same {@code start} succeeds. Without this, "refused" is satisfiable by a check that
     * refuses everything.
     *
     * <p>{@code mchild} is {@code required} for a reason found by writing this test: with it
     * optional, starting {@code mstage} made the evaluator immediately autocomplete the stage
     * (its only child was AVAILABLE, and {@code blockingItems} blocks on a non-required child
     * only while that child is ACTIVE) and sweep {@code mchild} straight to TERMINATED in the
     * same round — a live reproduction of the {@code PlanModelEvaluator}/{@code StageCompletion}
     * pre-round-snapshot ordering defect FINDINGS.md records as shipped-and-documented, not of
     * anything this test is about. Marking the child required keeps that defect out of the way
     * so this test measures containment and nothing else.
     */
    @Test
    void startingAChildIsAllowedOnceItsParentStageIsActive() throws Exception {
        String id = manualStageCase();
        PlanItem mstage = itemOf(id, "mstage");

        planItemService.start(id, mstage.id(), mstage.version(), alice);

        // Starting the stage makes the child contained, so reevaluate admits it automatically;
        // it is manualActivation, so it stops at ENABLED and needs an explicit start.
        PlanItem mchild = itemOf(id, "mchild");
        assertThat(mchild.state()).isEqualTo(PlanItemState.ENABLED);
        assertThat(planItemService.start(id, mchild.id(), mchild.version(), alice).state())
                .isEqualTo(PlanItemState.ACTIVE);
    }

    private String manualStageCase() throws Exception {
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy("""
                {"key":"manual-stage","name":"Manual stage","tenantId":"t1",
                 "planItems":[
                   {"defKey":"mstage","type":"STAGE","name":"mstage",
                    "manualActivation":true,"sortOrder":10},
                   {"defKey":"mchild","type":"HUMAN_TASK","name":"mchild",
                    "parentStageKey":"mstage","manualActivation":true,"required":true,
                    "sortOrder":20}]}""",
                "system", "t1");
        return cases.create("manual-stage", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice).id();
    }
}
