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
        if (task.state() == TaskState.OPEN) {
            actions.add(AvailableAction.post("claim", base + "/claim"));
        }
        if (task.state() == TaskState.CLAIMED && callerUserId.equals(task.assignee())) {
            actions.add(AvailableAction.post("complete", base + "/complete", task.formKey()));
        }
        return actions;
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
