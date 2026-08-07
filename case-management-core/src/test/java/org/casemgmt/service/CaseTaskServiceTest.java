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
}
