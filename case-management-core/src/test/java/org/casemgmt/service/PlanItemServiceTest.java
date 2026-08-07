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
}
