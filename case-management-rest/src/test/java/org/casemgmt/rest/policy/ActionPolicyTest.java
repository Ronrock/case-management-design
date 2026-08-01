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
