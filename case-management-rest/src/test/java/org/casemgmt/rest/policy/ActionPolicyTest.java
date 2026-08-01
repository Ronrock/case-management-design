package org.casemgmt.rest.policy;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.PlanModelFixtures;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.casemgmt.rules.PlanModelFixtures.*;

class ActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    private CaseSnapshot activeCaseWithOpenRequiredItem() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, false, true, false, List.of(), List.of(), 10));
        return snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.ACTIVE)), Map.of());
    }

    private CaseSnapshot activeCaseFullyDone() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, false, true, false, List.of(), List.of(), 10));
        return snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.COMPLETED)), Map.of());
    }

    @Test
    void ownerOfAnActiveCaseMaySeeCloseOnlyWhenNothingBlocks() {
        assertThat(policy.listForCase(activeCaseWithOpenRequiredItem(), Set.of("owner")))
                .extracting(AvailableAction::action).doesNotContain("close");

        assertThat(policy.listForCase(activeCaseFullyDone(), Set.of("owner")))
                .extracting(AvailableAction::action).contains("close");
    }

    @Test
    void watchersGetNoMutatingActions() {
        assertThat(policy.listForCase(activeCaseFullyDone(), Set.of("watcher")))
                .extracting(AvailableAction::action)
                .doesNotContain("close", "cancel", "update");
    }

    @Test
    void actionsCarryEnoughToInvokeThemWithoutASecondCall() {
        assertThat(policy.listForCase(activeCaseFullyDone(), Set.of("owner")))
                .allSatisfy(a -> {
                    assertThat(a.href()).isNotBlank();
                    assertThat(a.method()).isIn("GET", "POST", "PATCH", "DELETE");
                });
    }

    @Test
    void assertAllowedRejectsAndNamesTheAlternatives() {
        assertThatThrownBy(() ->
                policy.assertAllowed(activeCaseWithOpenRequiredItem(), Set.of("owner"), "close"))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("close");
    }

    @Test
    void assertAllowedAndListAgree() {
        CaseSnapshot snapshot = activeCaseFullyDone();
        Set<String> roles = Set.of("owner");

        for (AvailableAction action : policy.listForCase(snapshot, roles)) {
            assertThatNoException().isThrownBy(() ->
                    policy.assertAllowed(snapshot, roles, action.action()));
        }

        // Negative half: "reopen" is never a case-level action in any state, so it must
        // be rejected -- a policy that permitted everything would pass the loop above
        // alone without this check.
        assertThatThrownBy(() -> policy.assertAllowed(snapshot, roles, "reopen"))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("reopen");
    }

    @Test
    void assertAllowedOnPlanItemAndListAgree() {
        CaseSnapshot snapshot = activeCaseWithOpenRequiredItem();
        PlanItem active = snapshot.planItems().get(0);
        Set<String> roles = Set.of("handler");

        for (AvailableAction action : policy.listForPlanItem(snapshot, active, roles)) {
            assertThatNoException().isThrownBy(() ->
                    policy.assertAllowedOnPlanItem(snapshot, active, roles, action.action()));
        }

        // Negative half: this item is ACTIVE, which offers complete/terminate but never
        // "enable" (that's the AVAILABLE-state action) -- confirm it is actually rejected,
        // not just absent from the list.
        assertThatThrownBy(() -> policy.assertAllowedOnPlanItem(snapshot, active, roles, "enable"))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("enable");
    }

    @Test
    void assertAllowedOnTaskAndListAgree() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);
        Set<String> roles = Set.of("handler");

        for (AvailableAction action : policy.listForTask(claimed, "alice", roles)) {
            assertThatNoException().isThrownBy(() ->
                    policy.assertAllowedOnTask(claimed, "alice", roles, action.action()));
        }

        // Negative half: "complete" is listed for alice (the assignee) but must NOT be
        // enforceable by bob -- this exercises the caller-specific gating, not just task
        // state, which is exactly where a hand-rolled enforcement check could drift from
        // listForTask's caller-aware rule.
        assertThatThrownBy(() -> policy.assertAllowedOnTask(claimed, "bob", roles, "complete"))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("complete");
    }

    @Test
    void planItemActionsFollowTheStateMachine() {
        CaseSnapshot snapshot = activeCaseWithOpenRequiredItem();
        PlanItem active = snapshot.planItems().get(0);

        assertThat(policy.listForPlanItem(snapshot, active, Set.of("handler")))
                .extracting(AvailableAction::action)
                .containsExactlyInAnyOrder("complete", "terminate");
    }

    @Test
    void unsyncedTasksDoNotOfferClaim() {
        CaseTask pending = new CaseTask("t-1", "eng-a:1", "pi-1", null, "T", null,
                TaskState.OPEN, null, null, List.of("g"), null, 50, null, null,
                CaseTask.EngineSync.PENDING, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(pending, "alice", Set.of("handler")))
                .extracting(AvailableAction::action).doesNotContain("claim");
    }

    @Test
    void claimedTasksOfferCompleteToTheirAssigneeOnly() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(claimed, "alice", Set.of("handler")))
                .extracting(AvailableAction::action).contains("complete");
        assertThat(policy.listForTask(claimed, "bob", Set.of("handler")))
                .extracting(AvailableAction::action).doesNotContain("complete");
    }

    @Test
    void formKeyRidesAlongSoARendererKnowsWhichSchemaToFetch() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(claimed, "alice", Set.of("handler")))
                .filteredOn(a -> a.action().equals("complete"))
                .singleElement()
                .extracting(AvailableAction::formKey).isEqualTo("reviewForm");
    }
}
