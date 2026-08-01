package org.casemgmt.rest.policy;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.StageCompletion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single rule table behind both {@code availableActions[]} (projection) and
 * {@code assertAllowed} (enforcement). Two entry points, one set of rules — which is
 * what keeps the UI and the server from disagreeing (spec §4.5).
 *
 * <p>Case-level closure defers to {@link StageCompletion#caseCanClose}, not
 * {@link StageCompletion#blockingItems}: the two are deliberately separate rules
 * (see {@code StageCompletion}'s Javadoc) and mixing them here would silently
 * import the ACTIVE-child-blocks-completion rule — meant for stage autocomplete —
 * into case closure, which has never been part of the case-closing contract.
 */
public class ActionPolicy {

    private static final Set<String> MUTATING_ROLES = Set.of("owner", "handler");

    private final StageCompletion stageCompletion = new StageCompletion();

    public List<AvailableAction> listForCase(CaseSnapshot snapshot, Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        String base = "/cases/" + snapshot.caseInstance().id();
        CaseState state = snapshot.caseInstance().state();

        if (!mayMutate(callerRoles) || state == CaseState.CANCELLED) {
            return actions;
        }
        if (state == CaseState.ACTIVE) {
            actions.add(AvailableAction.patch("update", base));
            actions.add(AvailableAction.post("cancel", base + "/cancel"));
            if (stageCompletion.caseCanClose(snapshot)) {
                actions.add(AvailableAction.post("close", base + "/close"));
            }
        }
        return actions;
    }

    public List<AvailableAction> listForPlanItem(CaseSnapshot snapshot, PlanItem item,
                                                  Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayMutate(callerRoles) || item.state().isEnded()) {
            return actions;
        }
        String base = "/cases/" + item.caseId() + "/plan-items/" + item.id();
        switch (item.state()) {
            case AVAILABLE -> {
                actions.add(AvailableAction.post("enable", base + "/enable"));
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            case ENABLED -> {
                actions.add(AvailableAction.post("start", base + "/start"));
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            case ACTIVE -> {
                actions.add(AvailableAction.post("complete", base + "/complete"));
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            default -> { }
        }
        return actions;
    }

    public List<AvailableAction> listForTask(CaseTask task, String callerUserId, Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        String base = "/tasks/" + task.id();

        // A task the engine has not created yet cannot be claimed: the claim would fail.
        if (task.engineSync() != CaseTask.EngineSync.SYNCED) {
            return actions;
        }
        // Role gate (review fix): a caller with no mutating role and no candidate-group
        // membership gets nothing, same as the case- and plan-item-level surfaces. See
        // mayActOnTask() for the rule and why it is OR, not the plain mayMutate() check
        // the other two surfaces use.
        if (!mayActOnTask(task, callerRoles)) {
            return actions;
        }
        if (task.state() == TaskState.OPEN) {
            actions.add(AvailableAction.post("claim", base + "/claim"));
        }
        if (task.state() == TaskState.CLAIMED && callerUserId.equals(task.assignee())) {
            actions.add(AvailableAction.post("complete", base + "/complete", task.formKey()));
        }
        return actions;
    }

    /**
     * Authorization rule for task actions (review fix, Critical): {@code mayMutate}
     * ("owner" or "handler") OR membership in the task's own {@code candidateGroups}.
     *
     * <p>Plain {@code mayMutate} — what {@link #listForCase} and {@link #listForPlanItem}
     * use — is wrong here on its own: candidate-group membership is precisely how work
     * reaches someone who is not yet a case participant (spec's worklist model — a task
     * is offered to a group before anyone in it is "the handler" of the case). Requiring
     * {@code mayMutate} alone would make a case's declared candidate groups meaningless
     * for claiming; requiring candidate-group membership alone would stop an owner/handler
     * acting on a task that, for whatever reason, doesn't list their group. Either condition
     * is sufficient.
     *
     * <p>What this does NOT do: a caller who satisfies neither condition gets nothing,
     * including a "watcher" role or an empty role set — those must never see or perform
     * {@code claim}/{@code complete} on any task, which is exactly the hole this fixes
     * (a watcher, or a caller with no roles at all, could otherwise claim any open synced
     * task, gated on task state only).
     */
    private boolean mayActOnTask(CaseTask task, Set<String> callerRoles) {
        return mayMutate(callerRoles) || callerRoles.stream().anyMatch(task.candidateGroups()::contains);
    }

    public void assertAllowed(CaseSnapshot snapshot, Set<String> callerRoles, String action) {
        List<AvailableAction> allowed = listForCase(snapshot, callerRoles);
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on case "
                            + snapshot.caseInstance().id() + " in state "
                            + snapshot.caseInstance().state(),
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    public void assertAllowedOnPlanItem(CaseSnapshot snapshot, PlanItem item, Set<String> callerRoles,
                                         String action) {
        List<AvailableAction> allowed = listForPlanItem(snapshot, item, callerRoles);
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on plan item " + item.id()
                            + " in state " + item.state(),
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    public void assertAllowedOnTask(CaseTask task, String callerUserId, Set<String> callerRoles,
                                     String action) {
        List<AvailableAction> allowed = listForTask(task, callerUserId, callerRoles);
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on task " + task.id(),
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    private boolean mayMutate(Set<String> callerRoles) {
        return callerRoles.stream().anyMatch(MUTATING_ROLES::contains);
    }
}
