package org.casemgmt.rest.policy;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.orchestration.OrchestrationMode;
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

    /**
     * Identity groups that may administer the deployment itself — deploy case definitions,
     * subscribe webhooks. An identity group, not a participant role: these actions are not
     * scoped to any case. See {@link #listForAdministration}.
     */
    private static final Set<String> ADMIN_GROUPS = Set.of("admin");

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
            // PLAN_MODEL cases retain their explicit close transition. A BPMN case is
            // closed only when its pinned root process ends, so advertising this legacy
            // action would let a caller bypass the orchestrator-of-record lifecycle.
            if (snapshot.definition().orchestrationMode() == OrchestrationMode.PLAN_MODEL
                    && stageCompletion.caseCanClose(snapshot)) {
                actions.add(AvailableAction.post("close", base + "/close"));
            }
        }
        return actions;
    }

    /**
     * <p><b>Consults {@link StageCompletion} — final whole-branch review, Important 2.</b> This
     * method used to be a bare state-transition table, written without reference to
     * {@code StageCompletion} at all, while {@code PlanItemService} enforced the same bare
     * table. So the API advertised {@code complete} on a stage with live required children (the
     * generic consumer hit exactly that: it force-completed a stage, orphaned the worklist task
     * beneath it, and had to start excluding {@code STAGE} by TYPE to stay out of the way), and
     * advertised {@code enable} on a child of a stage that had never started.
     *
     * <p>The enforcement now lives in {@code PlanItemService.assertModelInvariants} — a client
     * POSTing the URL directly never reads this projection, so a fix here alone would fix
     * nothing. This mirror is what keeps the promise the class Javadoc above makes: one set of
     * rules behind both surfaces, so the API never offers an action the server then refuses.
     * The two conditions are literally the same two calls the service makes.
     */
    public List<AvailableAction> listForPlanItem(CaseSnapshot snapshot, PlanItem item,
                                                  Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayMutate(callerRoles) || item.state().isEnded()) {
            return actions;
        }
        String base = "/cases/" + item.caseId() + "/plan-items/" + item.id();
        boolean contained = stageCompletion.isContained(snapshot, item);
        switch (item.state()) {
            case AVAILABLE -> {
                if (contained) {
                    actions.add(AvailableAction.post("enable", base + "/enable"));
                }
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            case ENABLED -> {
                if (contained) {
                    actions.add(AvailableAction.post("start", base + "/start"));
                }
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            case ACTIVE -> {
                if (stageCompletion.blockingItems(snapshot, item).isEmpty()) {
                    actions.add(AvailableAction.post("complete", base + "/complete"));
                }
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            default -> { }
        }
        return actions;
    }

    public List<AvailableAction> listForTask(CaseTask task, String callerUserId,
                                            Set<String> participantRoles, Set<String> callerGroups) {
        List<AvailableAction> actions = new ArrayList<>();
        String base = "/tasks/" + task.id();

        // A task the engine has not created yet cannot be claimed: the claim would fail.
        if (task.engineSync() != CaseTask.EngineSync.SYNCED) {
            return actions;
        }
        if (task.state() == TaskState.OPEN) {
            if (mayActOnTask(task, participantRoles, callerGroups)) {
                actions.add(AvailableAction.post("claim", base + "/claim"));
            }
        }
        if (task.state() == TaskState.CLAIMED && callerUserId.equals(task.assignee())) {
            actions.add(AvailableAction.post("complete", base + "/complete", task.formKey()));
        }
        return actions;
    }

    /**
     * Authorization rule for task actions (review fix, Critical): {@code mayMutate}
     * ("owner" or "handler" among the caller's PARTICIPANT roles) OR membership of one of the
     * task's own {@code candidateGroups} among the caller's IDENTITY groups.
     *
     * <p><b>Two parameters, deliberately, and never one merged set</b> (Task 24 fix round 1,
     * review finding I3). The first cut of the REST wiring passed a single union of participant
     * roles and identity groups, because {@code candidateGroups} holds group names while
     * {@code mayMutate} tests role names and a single set had to satisfy both. That union is a
     * privilege-escalation primitive: an identity group literally named {@code owner} or
     * {@code handler} would satisfy {@code mayMutate} and grant claim/complete on <em>every</em>
     * task in <em>every</em> case, with no participant row anywhere. Splitting the parameter
     * makes the two vocabularies structurally incapable of crossing, so the invariant is in the
     * code rather than in a deployment convention about how groups are named.
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
    private boolean mayActOnTask(CaseTask task, Set<String> participantRoles, Set<String> callerGroups) {
        return mayMutate(participantRoles)
                || callerGroups.stream().anyMatch(task.candidateGroups()::contains);
    }

    /**
     * Case-level collaboration: adding a comment, linking/removing document references, and
     * starting a BPMN process correlated to the case.
     *
     * <p>Added by Task 24 fix round 1 (review finding, Critical). These endpoints previously had
     * no rule at all and were gated by authentication alone, so any authenticated user could
     * write to any case through them — the same caller the case-level rule refuses a title edit.
     * They are separated from {@link #listForCase} rather than folded into it because they are a
     * different resource: their hrefs are sub-resources, not case transitions, and a client
     * reading a case's {@code availableActions[]} should not be told it can "comment" as if that
     * changed the case's state.
     *
     * <p>Rule: {@code mayMutate} on a live (ACTIVE) case — deliberately the same tier as
     * {@code update}, not a new one. A finer "any participant, including a watcher, may comment"
     * tier is arguable and is explicitly NOT invented here; introducing a privilege level nobody
     * specified is how a rule table stops being reviewable.
     */
    public List<AvailableAction> listForCollaboration(CaseSnapshot snapshot, Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayMutate(callerRoles) || snapshot.caseInstance().state() != CaseState.ACTIVE) {
            return actions;
        }
        String base = "/cases/" + snapshot.caseInstance().id();
        actions.add(AvailableAction.post("comment", base + "/comments"));
        actions.add(AvailableAction.post("add-document", base + "/documents"));
        actions.add(AvailableAction.delete("remove-document", base + "/documents/{documentId}"));
        actions.add(AvailableAction.post("start-process", base + "/processes"));
        return actions;
    }

    /**
     * A milestone can be achieved manually exactly once, on a live case, by a caller who may
     * mutate it. Takes the milestone's identity and achieved flag rather than a repository row
     * type so this class keeps depending only on the domain and the snapshot.
     */
    public List<AvailableAction> listForMilestone(CaseSnapshot snapshot, String milestoneId,
                                                  boolean achieved, Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayMutate(callerRoles) || snapshot.caseInstance().state() != CaseState.ACTIVE || achieved) {
            return actions;
        }
        actions.add(AvailableAction.post("achieve",
                "/cases/" + snapshot.caseInstance().id() + "/milestones/" + milestoneId + "/achieve"));
        return actions;
    }

    /**
     * SLA clocks mirror their own state machine, the same way {@link #listForPlanItem} mirrors
     * the plan-item one: a RUNNING clock can be paused, a PAUSED clock resumed, and a clock in
     * any other state (BREACHED, STOPPED) offers neither — which keeps this rule table and
     * {@code SlaService}'s {@code sla-not-running} conflict from disagreeing.
     */
    public List<AvailableAction> listForSla(CaseSnapshot snapshot, String slaId, String status,
                                            Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayMutate(callerRoles) || snapshot.caseInstance().state() != CaseState.ACTIVE) {
            return actions;
        }
        String base = "/cases/" + snapshot.caseInstance().id() + "/slas/" + slaId;
        if ("RUNNING".equals(status)) {
            actions.add(AvailableAction.post("pause", base + "/pause"));
        }
        if ("PAUSED".equals(status)) {
            actions.add(AvailableAction.post("resume", base + "/resume"));
        }
        return actions;
    }

    /**
     * Deployment-wide administration: deploying a case definition and subscribing a webhook.
     *
     * <p>Added by Task 24 fix round 1 (review finding, Critical — and the sharper half of it).
     * Neither of these is scoped to a case, so there is no participant row to consult and none
     * of the rules above can express them; both were consequently reachable by any authenticated
     * caller. Deploying a definition rewrites how every future case of that type behaves;
     * subscribing a webhook opens a continuous outbound stream of case events. They are gated on
     * an IDENTITY GROUP ({@code admin}), not a participant role, because that is the only
     * vocabulary that exists above the level of a single case.
     *
     * <p>Group membership is asserted against {@code callerGroups} only — never against
     * participant roles — for the same reason {@link #mayActOnTask} keeps its two parameters
     * apart: a participant role named {@code admin} on one case must never confer
     * deployment-wide authority.
     */
    public List<AvailableAction> listForAdministration(Set<String> callerGroups) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayAdminister(callerGroups)) {
            return actions;
        }
        actions.add(AvailableAction.post("deploy-case-definition", "/case-definitions"));
        actions.add(AvailableAction.post("subscribe-webhook", "/webhooks"));
        actions.add(AvailableAction.get("view-webhook-dead-letters", "/webhooks/{webhookId}/dead-letters"));
        actions.add(AvailableAction.post("redeliver-webhook-dead-letters",
                "/webhooks/{webhookId}/dead-letters/redeliver"));
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

    public void assertAllowedOnTask(CaseTask task, String callerUserId, Set<String> participantRoles,
                                     Set<String> callerGroups, String action) {
        List<AvailableAction> allowed = listForTask(task, callerUserId, participantRoles, callerGroups);
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on task " + task.id(),
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    public void assertAllowedOnCollaboration(CaseSnapshot snapshot, Set<String> callerRoles,
                                             String action) {
        refuseUnlessListed(listForCollaboration(snapshot, callerRoles), action,
                "case " + snapshot.caseInstance().id() + " in state "
                        + snapshot.caseInstance().state());
    }

    public void assertAllowedOnMilestone(CaseSnapshot snapshot, String milestoneId, boolean achieved,
                                         Set<String> callerRoles, String action) {
        refuseUnlessListed(listForMilestone(snapshot, milestoneId, achieved, callerRoles), action,
                "milestone " + milestoneId + (achieved ? " (already achieved)" : ""));
    }

    public void assertAllowedOnSla(CaseSnapshot snapshot, String slaId, String status,
                                   Set<String> callerRoles, String action) {
        refuseUnlessListed(listForSla(snapshot, slaId, status, callerRoles), action,
                "SLA record " + slaId + " in status " + status);
    }

    public void assertMayAdminister(Set<String> callerGroups, String action) {
        refuseUnlessListed(listForAdministration(callerGroups), action, "this deployment");
    }

    /**
     * The one place a refusal is minted, so every surface refuses in exactly the same shape:
     * 409 {@code action-not-available} carrying the actions that WOULD be legal. Consistency
     * here is the point — a client switches on {@code code} and reads {@code availableActions},
     * and it must not have to learn a different error shape per resource.
     */
    private void refuseUnlessListed(List<AvailableAction> allowed, String action, String subject) {
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on " + subject,
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    private boolean mayMutate(Set<String> callerRoles) {
        return callerRoles.stream().anyMatch(MUTATING_ROLES::contains);
    }

    private boolean mayAdminister(Set<String> callerGroups) {
        return callerGroups.stream().anyMatch(ADMIN_GROUPS::contains);
    }
}
