package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.FormValidationException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CaseTaskServiceTest extends OracleTestBase {

    private CaseService cases;
    private CaseTaskService taskService;
    private CaseTaskRepository tasks;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;

    // No manual DELETEs here: OracleTestBase already wipes every CM_ table before each test
    // method via its own @BeforeEach (see CaseServiceTest/CaseDefinitionServiceTest for the
    // same convention).
    @BeforeEach
    void setUp() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(dataSource(), gateway);
        taskService = TestServices.taskService(dataSource(), gateway);
        tasks = new CaseTaskRepository(jdbc());
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    private CaseTask task() {
        return tasks.findByCase(caseId).get(0);
    }

    @Test
    void worklistReturnsCandidateGroupTasks() {
        assertThat(taskService.worklist(null, alice, 20)).extracting(CaseTask::name).contains("Review");
    }

    @Test
    void claimAssignsTheTaskAndCallsTheEngine() {
        CaseTask t = task();

        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        assertThat(claimed.state()).isEqualTo(TaskState.CLAIMED);
        assertThat(claimed.assignee()).isEqualTo("alice");
    }

    @Test
    void claimingAnAlreadyClaimedTaskConflicts() {
        CaseTask t = task();
        taskService.claim(t.id(), t.version(), alice);
        CaseTask claimed = tasks.require(t.id());

        assertThatThrownBy(() -> taskService.claim(claimed.id(), claimed.version(),
                new Actor("bob", List.of("reviewers"))))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("alice");
    }

    @Test
    void completingAnUnclaimedTaskConflicts() {
        CaseTask t = task();

        assertThatThrownBy(() -> taskService.complete(t.id(), t.version(),
                Map.of("outcome", "approve"), alice))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void completeValidatesAgainstTheFormSchema() {
        CaseTask t = task();
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        assertThatThrownBy(() -> taskService.complete(claimed.id(), claimed.version(),
                Map.of("outcome", "not-a-valid-option"), alice))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.pointer()).isEqualTo("/outcome")));

        // The rejected payload must not have moved the task past CLAIMED: proves the
        // FormValidationException above came from schema validation, not a partial write.
        assertThat(tasks.require(claimed.id()).state()).isEqualTo(TaskState.CLAIMED);
    }

    @Test
    void completeEndsTheTaskAndItsPlanItem() {
        CaseTask t = task();
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        CaseTask completed = taskService.complete(claimed.id(), claimed.version(),
                Map.of("outcome", "approve"), alice);

        assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(completed.outcome()).isEqualTo("approve");
        assertThat(new PlanItemRepository(jdbc()).require(completed.planItemId()).state())
                .isEqualTo(PlanItemState.COMPLETED);
    }

    @Test
    void completingATaskAdvancesTheModelToTheNextItem() {
        CaseTask t = task();
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);
        taskService.complete(claimed.id(), claimed.version(), Map.of("outcome", "approve"), alice);

        // 'reviewed' milestone has entry criterion items.review.state == 'COMPLETED'
        assertThat(new MilestoneRepository(jdbc()).findByCase(caseId))
                .anySatisfy(m -> assertThat(m.achieved()).isTrue());
    }

    /**
     * Review fix (Important 2): {@code variables.get("outcome")} returns Java {@code null} for
     * a missing key, and {@code String.valueOf(null)} used to turn that into the literal
     * four-character string {@code "null"} instead of a real {@code null} — silently defeating
     * any later {@code WHERE OUTCOME_ IS NULL} query. Uses a formKey-less plan item so this
     * reaches {@code complete}'s outcome-extraction step directly, with no form schema in the
     * way to reject the payload first (that would be the "wrong reason" trap: this test needs
     * to prove the persisted value, not that validation runs).
     */
    @Test
    void completingWithoutAnOutcomeKeyPersistsARealNullNotTheStringNull() throws Exception {
        String json = """
                {"key":"no-form-review","name":"No Form Review","tenantId":"t1",
                 "planItems":[
                   {"defKey":"plain","type":"HUMAN_TASK","name":"plain","sortOrder":10}]}""";
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");
        String noFormCaseId = cases.create("no-form-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice).id();
        CaseTask plainTask = tasks.findByCase(noFormCaseId).get(0);
        assertThat(plainTask.formKey()).isNull();
        CaseTask claimed = taskService.claim(plainTask.id(), plainTask.version(), alice);

        CaseTask completed = taskService.complete(claimed.id(), claimed.version(), Map.of(), alice);

        assertThat(completed.outcome()).isNull();
        assertThat(tasks.require(completed.id()).outcome()).isNull();
    }

    /**
     * Final whole-branch review, Important 1, half one: <b>version drift</b>.
     *
     * <p>{@code complete} used to resolve the form schema through
     * {@code CaseDefinitionRepository.formSchema(caseDefKey, formKey)}, which picks the highest
     * {@code VERSION_NO_} row for the key at the moment of the call. So deploying v2 with a new
     * {@code required} field silently re-validated every ALREADY-RUNNING v1 case's task
     * completion against v2 — the exact failure versioned case definitions exist to prevent.
     * The case row pins its definition in {@code CASE_DEF_ID_}; the fix resolves through it.
     *
     * <p>Attribution, not just outcome: the v2 schema requires a field name that appears
     * NOWHERE in the payload or in v1, so the only way this completion can fail on the form
     * schema is by having consulted v2. And the assertion is not merely "no exception" — it
     * pins the task to COMPLETED and the outcome value that only a successful write produces.
     */
    @Test
    void completeValidatesAgainstTheCaseSPinnedDefinitionVersionNotTheLatest() {
        deploy("""
                {"key":"drift","name":"Drift","tenantId":"t1",
                 "forms":{"f":{"type":"object","required":["alpha"],
                               "properties":{"alpha":{"type":"string"}}}},
                 "planItems":[
                   {"defKey":"t","type":"HUMAN_TASK","name":"T","formKey":"f","sortOrder":10}]}""");
        String v1CaseId = cases.create("drift", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice).id();

        // v2 lands AFTER the case above is already in flight, and demands a different field.
        deploy("""
                {"key":"drift","name":"Drift","tenantId":"t1",
                 "forms":{"f":{"type":"object","required":["beta"],
                               "properties":{"beta":{"type":"string"}}}},
                 "planItems":[
                   {"defKey":"t","type":"HUMAN_TASK","name":"T","formKey":"f","sortOrder":10}]}""");

        CaseTask t = tasks.findByCase(v1CaseId).get(0);
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        CaseTask completed = taskService.complete(claimed.id(), claimed.version(),
                Map.of("alpha", "value", "outcome", "ok"), alice);

        assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(completed.outcome()).isEqualTo("ok");
        assertThat(tasks.require(completed.id()).state()).isEqualTo(TaskState.COMPLETED);
    }

    /**
     * Final whole-branch review, Important 1, half two: <b>cross-tenant</b>.
     *
     * <p>The old lookup carried no tenant predicate at all, so with tenant t1 at v2 and tenant
     * t2 at v1 for the same key, a t2 case's task was validated against t1's schema. t1's v2
     * here requires a field t2's schema does not even declare, so consulting the wrong tenant
     * is the only way this completion can fail — and t1 is deliberately at the HIGHER version,
     * which is what made it win the old {@code ORDER BY VERSION_NO_ DESC}.
     */
    @Test
    void completeValidatesAgainstTheCaseSOwnTenantsSchemaNotAnotherTenantsHigherVersion() {
        // t2: version 1, requires "t2field".
        deployFor("t2", """
                {"key":"shared","name":"Shared",
                 "forms":{"f":{"type":"object","required":["t2field"],
                               "properties":{"t2field":{"type":"string"}}}},
                 "planItems":[
                   {"defKey":"t","type":"HUMAN_TASK","name":"T","formKey":"f","sortOrder":10}]}""");
        // t1: versions 1 and 2 of the SAME key, requiring a field t2's schema never declares.
        for (int i = 0; i < 2; i++) {
            deployFor("t1", """
                    {"key":"shared","name":"Shared",
                     "forms":{"f":{"type":"object","required":["t1field"],
                                   "properties":{"t1field":{"type":"string"}}}},
                     "planItems":[
                       {"defKey":"t","type":"HUMAN_TASK","name":"T","formKey":"f","sortOrder":10}]}""");
        }

        String t2CaseId = cases.create("shared", "t2", null, "T",
                CasePriority.MEDIUM, Map.of(), alice).id();
        CaseTask t = tasks.findByCase(t2CaseId).get(0);
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        CaseTask completed = taskService.complete(claimed.id(), claimed.version(),
                Map.of("t2field", "value", "outcome", "ok"), alice);

        assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(tasks.require(completed.id()).state()).isEqualTo(TaskState.COMPLETED);
    }

    /**
     * The negative control for the two tests above: the pinned lookup must still REJECT a
     * payload that genuinely violates the case's own schema. Without this, both tests above are
     * satisfied by a "resolve nothing, validate nothing" regression — {@code
     * formSchemaOfDefinition} returning empty would make them pass and disable validation
     * entirely (it throws {@code InvalidCaseDefinitionException} rather than silently skipping,
     * but only for a formKey the definition does not declare, which is not what those two
     * exercise).
     */
    @Test
    void thePinnedLookupStillRejectsAPayloadThatViolatesTheCaseSOwnSchema() {
        deploy("""
                {"key":"drift","name":"Drift","tenantId":"t1",
                 "forms":{"f":{"type":"object","required":["alpha"],
                               "properties":{"alpha":{"type":"string"}}}},
                 "planItems":[
                   {"defKey":"t","type":"HUMAN_TASK","name":"T","formKey":"f","sortOrder":10}]}""");
        String driftCaseId = cases.create("drift", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice).id();
        CaseTask t = tasks.findByCase(driftCaseId).get(0);
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        assertThatThrownBy(() -> taskService.complete(claimed.id(), claimed.version(),
                Map.of("outcome", "ok"), alice))
                .isInstanceOf(FormValidationException.class);
        assertThat(tasks.require(claimed.id()).state()).isEqualTo(TaskState.CLAIMED);
    }

    private void deploy(String json) {
        deployFor("t1", json);
    }

    private void deployFor(String tenantId, String json) {
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource()))
                .deploy(json, "system", tenantId);
    }
}
