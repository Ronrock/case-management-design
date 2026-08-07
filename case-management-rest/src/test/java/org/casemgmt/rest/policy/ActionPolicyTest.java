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

    /**
     * The caller holds no identity groups. Named rather than written inline so it is obvious at
     * every call site which of the two role vocabularies is being supplied — participant roles
     * and identity groups are separate arguments on purpose (see ActionPolicy.mayActOnTask).
     */
    private static final Set<String> NO_GROUPS = Set.of();

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

        // Non-mutating role: everything this fully-done case would otherwise offer an
        // owner (including "close") must be refused to a watcher -- an agreement test
        // that only ever exercises a privileged role can never detect a missing
        // authorization check (review finding).
        Set<String> watcher = Set.of("watcher");
        assertThat(policy.listForCase(snapshot, watcher)).isEmpty();
        assertThatThrownBy(() -> policy.assertAllowed(snapshot, watcher, "close"))
                .isInstanceOf(CaseConflictException.class);
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

        // Non-mutating role: an ACTIVE item's "complete"/"terminate" must not be listed
        // or enforceable for a watcher, same reasoning as the case-level check above.
        Set<String> watcher = Set.of("watcher");
        assertThat(policy.listForPlanItem(snapshot, active, watcher)).isEmpty();
        assertThatThrownBy(() -> policy.assertAllowedOnPlanItem(snapshot, active, watcher, "complete"))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void assertAllowedOnTaskAndListAgree() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);
        Set<String> roles = Set.of("handler");

        for (AvailableAction action : policy.listForTask(claimed, "alice", roles, NO_GROUPS)) {
            assertThatNoException().isThrownBy(() ->
                    policy.assertAllowedOnTask(claimed, "alice", roles, NO_GROUPS, action.action()));
        }

        // Negative half: "complete" is listed for alice (the assignee) but must NOT be
        // enforceable by bob -- this exercises the caller-specific gating, not just task
        // state, which is exactly where a hand-rolled enforcement check could drift from
        // listForTask's caller-aware rule.
        assertThatThrownBy(() -> policy.assertAllowedOnTask(claimed, "bob", roles, NO_GROUPS, "complete"))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("complete");

        // Non-mutating role: alice IS the assignee here, so identity alone would pass --
        // this is exactly the hole the review found (Critical): listForTask must also
        // require a mutating role or candidate-group membership, not caller identity
        // alone. A watcher-roled alice must get nothing and must not be enforceable.
        Set<String> watcher = Set.of("watcher");
        assertThat(policy.listForTask(claimed, "alice", watcher, NO_GROUPS)).isEmpty();
        assertThatThrownBy(() -> policy.assertAllowedOnTask(claimed, "alice", watcher, NO_GROUPS, "complete"))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void tasksRequireAMutatingRoleOrCandidateGroupMembership() {
        CaseTask open = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.OPEN, null, null, List.of("reviewers"), null, 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        // A read-only watcher gets nothing: the review's proof case was a watcher (or no
        // roles at all) claiming any open synced task, gated on task state only.
        assertThat(policy.listForTask(open, "alice", Set.of("watcher"), NO_GROUPS))
                .extracting(AvailableAction::action).doesNotContain("claim");
        // No roles whatsoever -- the other half of the same proof case.
        assertThat(policy.listForTask(open, "alice", Set.of(), NO_GROUPS))
                .extracting(AvailableAction::action).doesNotContain("claim");

        // Candidate-group membership is how work reaches someone who is not yet a
        // participant, so it is sufficient on its own -- no owner/handler role required.
        assertThat(policy.listForTask(open, "alice", Set.of(), Set.of("reviewers")))
                .extracting(AvailableAction::action).contains("claim");
    }

    /**
     * The escalation that merging the two role vocabularies into one set allowed (fix round 1,
     * review finding I3): an identity group is caller-controlled input as far as this rule is
     * concerned, and a group named "owner" or "handler" must never satisfy mayMutate. It can only
     * ever be matched against the task's own candidateGroups.
     */
    @Test
    void anIdentityGroupNamedLikeAMutatingRoleGrantsNothing() {
        CaseTask open = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.OPEN, null, null, List.of("reviewers"), null, 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        // No participant roles at all; groups named exactly like the mutating roles.
        assertThat(policy.listForTask(open, "mallory", Set.of(), Set.of("owner", "handler")))
                .isEmpty();
        assertThatThrownBy(() -> policy.assertAllowedOnTask(
                open, "mallory", Set.of(), Set.of("owner", "handler"), "claim"))
                .isInstanceOf(CaseConflictException.class);

        // ...and the mirror image: a PARTICIPANT role that happens to be named like the task's
        // candidate group does not stand in for membership of that group either.
        assertThat(policy.listForTask(open, "mallory", Set.of("reviewers"), NO_GROUPS)).isEmpty();
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

        assertThat(policy.listForTask(pending, "alice", Set.of("handler"), NO_GROUPS))
                .extracting(AvailableAction::action).doesNotContain("claim");
    }

    @Test
    void claimedTasksOfferCompleteToTheirAssigneeOnly() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(claimed, "alice", Set.of("handler"), NO_GROUPS))
                .extracting(AvailableAction::action).contains("complete");
        assertThat(policy.listForTask(claimed, "bob", Set.of("handler"), NO_GROUPS))
                .extracting(AvailableAction::action).doesNotContain("complete");
    }

    @Test
    void formKeyRidesAlongSoARendererKnowsWhichSchemaToFetch() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(claimed, "alice", Set.of("handler"), NO_GROUPS))
                .filteredOn(a -> a.action().equals("complete"))
                .singleElement()
                .extracting(AvailableAction::formKey).isEqualTo("reviewForm");
    }
}
