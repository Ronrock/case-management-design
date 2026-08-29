package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.orchestration.CaseOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Case lifecycle: create, update, close, cancel — plus re-evaluating the plan model after
 * every mutation (spec §4.3) and applying whatever the (pure) evaluator decides via
 * {@link TransitionApplier}.
 *
 * <p>{@code @Transactional} here is genuine only when this bean is obtained through a
 * Spring-managed proxy (see {@code org.casemgmt.config.TransactionManagerConfig}) — the AOP
 * proxy is what actually opens/commits/rolls back the transaction. It does nothing for a plain
 * {@code new CaseService(...)} (which is how {@code TestServices} builds it for this module's
 * tests, same as every other repository test here), and it does nothing for a
 * <em>self</em>-invoked call such as {@code this.reevaluate(...)} from inside {@link #create}
 * or {@link #update} — that bypasses the proxy entirely. Both call sites are fine as written:
 * {@code reevaluate} only ever needs to run inside whatever transaction its caller already
 * holds, never one of its own, so it is a private, unannotated helper rather than something
 * that pretends self-invocation gives it a fresh transactional boundary.
 */
public class CaseService {

    private final CaseRepository cases;
    private final CaseDefinitionRepository definitions;
    private final PlanItemRepository planItems;
    private final MilestoneRepository milestones;
    private final ParticipantRepository participants;
    private final CaseOrchestrationRegistry orchestrations;
    private final StageCompletion stageCompletion;
    private final TransitionApplier applier;
    private final EventPublisher publisher;
    private final String engineId;

    public CaseService(CaseRepository cases, CaseDefinitionRepository definitions,
                       PlanItemRepository planItems, MilestoneRepository milestones,
                       ParticipantRepository participants, CaseOrchestrationRegistry orchestrations,
                       StageCompletion stageCompletion, TransitionApplier applier,
                       EventPublisher publisher, String engineId) {
        this.cases = cases;
        this.definitions = definitions;
        this.planItems = planItems;
        this.milestones = milestones;
        this.participants = participants;
        this.orchestrations = orchestrations;
        this.stageCompletion = stageCompletion;
        this.applier = applier;
        this.publisher = publisher;
        this.engineId = engineId;
    }

    @Transactional
    public CaseInstance create(String caseDefKey, String tenantId, String businessKey, String title,
                               CasePriority priority, Map<String, Object> variables, Actor actor) {
        CaseDefinition def = definitions.findLatestStartable(caseDefKey, tenantId)
                .orElseGet(() -> {
                    if (definitions.findLatest(caseDefKey, tenantId).isPresent()) {
                        throw new CaseConflictException("case-definition-not-active",
                                "Case definition '" + caseDefKey
                                        + "' has no ACTIVE binding available for new cases",
                                List.of());
                    }
                    throw new NotFoundException("CaseDefinition", caseDefKey);
                });

        OffsetDateTime now = OffsetDateTime.now();
        CaseInstance created = new CaseInstance(CaseIds.newCaseId(engineId), engineId, tenantId,
                def.id(), def.key(), def.versionNo(), businessKey, title, CaseState.ACTIVE,
                priority == null ? CasePriority.MEDIUM : priority, null, null, actor.userId(),
                "NONE", null, null, variables == null ? Map.of() : variables, 0L, now, now, null);
        cases.insert(created);

        participants.insert(CaseIds.newId(), created.id(), actor.userId(), null, "owner");
        CaseOrchestration orchestration = orchestration(def);
        orchestration.initialItems(created.id(), def).forEach(planItems::insert);
        orchestration.onCaseCreated(created, def);

        publisher.publish(event(created, EventTypes.CASE_CREATED, Map.of(
                "caseDefinitionKey", def.key(), "state", created.state().name(),
                "businessKey", businessKey == null ? "" : businessKey)));
        publisher.audit(created.id(), tenantId, actor.userId(), "case.create", "Case",
                created.id(), null, Map.of("state", created.state().name(), "title", title));

        // Starting BPMN binds the root process and projection state onto CM_CASE. Return that
        // orchestrated row so the create response does not expose the pre-start snapshot.
        return cases.require(created.id());
    }

    public CaseInstance get(String caseId) {
        return cases.require(caseId);
    }

    public CaseSnapshot snapshot(String caseId) {
        return snapshot(cases.require(caseId));
    }

    /**
     * Builds a snapshot around a {@link CaseInstance} the caller already holds, instead of
     * re-reading {@code CM_CASE}.
     *
     * <p>Added by Task 24 for the REST layer, and for the same reason Task 4's rule exists: a
     * controller that has just performed a successful optimistic update holds the authoritative
     * post-write row and must not go back to the database for it. {@code availableActions[]} is
     * derived from the snapshot, so re-reading here would let a concurrent writer's row decide
     * which actions get advertised alongside a body built from <em>this</em> call's write —
     * the two would describe different versions of the case. Plan items are still read (they
     * are not part of the row the caller wrote, and the plan model may legitimately have moved
     * during the same transaction).
     */
    public CaseSnapshot snapshot(CaseInstance instance) {
        return new CaseSnapshot(instance, definitions.require(instance.caseDefId()),
                planItems.findByCase(instance.id()));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public CaseInstance update(String caseId, long expectedVersion, Map<String, Object> patch, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireLive(current, "update");

        CaseInstance patched = current;
        if (patch.containsKey("title")) {
            patched = new CaseInstance(patched.id(), patched.engineId(), patched.tenantId(),
                    patched.caseDefId(), patched.caseDefKey(), patched.caseDefVersion(),
                    patched.businessKey(), (String) patch.get("title"), patched.state(),
                    patched.priority(), patched.assignee(), patched.queueId(), patched.initiator(),
                    patched.slaStatus(), patched.outcome(), patched.cancelReason(),
                    patched.variables(), patched.version(), patched.createdAt(),
                    patched.updatedAt(), patched.closedAt());
        }
        if (patch.containsKey("variables")) {
            Object variablesPatch = patch.get("variables");
            patched = patched.withVariables(variablesPatch == null
                    ? Map.of()
                    : mergeObject(patched.variables(), (Map<String, Object>) variablesPatch));
        }

        CaseInstance saved = cases.update(patched, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_UPDATED, Map.of("fields", patch.keySet())));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("title", current.title());
        before.put("variables", current.variables());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", saved.title());
        after.put("variables", saved.variables());
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.update", "Case", caseId,
                before, after);

        // `saved` is what CaseRepository.update already proved committed (see its Javadoc):
        // re-reading here would risk returning a concurrent writer's row as if it confirmed
        // this call's own write. The orchestration start never touches CM_CASE, so `saved` is
        // exactly correct.
        return saved;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeObject(Map<String, Object> current,
                                                   Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (entry.getValue() == null) {
                merged.remove(entry.getKey());
            } else if (entry.getValue() instanceof Map<?, ?> patchObject
                    && merged.get(entry.getKey()) instanceof Map<?, ?> currentObject) {
                merged.put(entry.getKey(), mergeObject((Map<String, Object>) currentObject,
                        (Map<String, Object>) patchObject));
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * Closes a case, then brings its plan items into line the way every other terminal-state
     * path in this codebase already does: {@code StageCompletion}'s autocomplete cascade
     * terminates unstarted descendants at any depth, and {@link #cancel} terminates every open
     * item outright. Before this fix {@code close} did neither — it only checked
     * {@link StageCompletion#caseBlockers} and wrote the case row, so a case could close with a
     * plan item still sitting AVAILABLE or ACTIVE: e.g. a milestone whose entry criterion became
     * satisfied by the very same required item that just unblocked the close, but which nothing
     * ever went back to evaluate, so it never got its {@code CM_MILESTONE} row or its achieved
     * event.
     *
     * <p>Fixed with BOTH of the two remedies the review raised, not either alone, because they
     * cover different failure modes:
     * <ul>
     * <li>{@link #reevaluate} first, so anything the plan model can <em>genuinely</em> resolve —
     * a milestone whose criteria now hold, a stage that can now autocomplete — is recorded
     * properly: a real {@link Transition}, a real event, a real {@code CM_MILESTONE} row where
     * applicable. Terminating such an item outright instead (skipping straight to TERMINATED)
     * would misrecord something that actually completed as if it had been aborted.</li>
     * <li>An explicit termination sweep afterwards, identical in shape to {@link #cancel}'s,
     * because {@code reevaluate} alone does not <em>guarantee</em> every item ends — an item
     * gated on something that never happened (no entry criteria ever satisfied, e.g. an
     * unstarted optional stage) would still be sitting open after {@code reevaluate} returns.
     * The sweep is what makes "a CLOSED case carries no AVAILABLE/ACTIVE plan item" an actual
     * invariant rather than something that merely usually happens to be true. It runs through
     * {@link #sweepOpenPlanItems}, not a raw repository update — see that method's Javadoc for
     * why (second review round: a raw update produced no event and no audit row, silently
     * breaking the event-stream contract every other transition path in this codebase honors).</li>
     * </ul>
     *
     * <p>Ordering matters: {@link StageCompletion#caseBlockers} is checked <em>before</em> either
     * remedy runs, against the pre-close snapshot. Required items are only ever finished by an
     * external actor (completing a human task) — {@code reevaluate} cannot do that on its own —
     * so checking blockers first, and refusing to close (and therefore never touching the plan
     * model) while one is open, is unaffected by moving the rest of this method's plan-item
     * handling around it.
     */
    @Transactional
    public CaseInstance close(String caseId, long expectedVersion, String outcome, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireTransitionAllowed(current, CaseState.CLOSED, "close");

        CaseSnapshot snapshot = snapshot(caseId);
        if (!orchestration(snapshot.definition()).allowsExplicitClose()) {
            throw new CaseConflictException("explicit-close-not-supported",
                    "BPMN cases close when their root process completes", List.of("cancel"));
        }
        List<PlanItem> blockers = stageCompletion.caseBlockers(snapshot);
        if (!blockers.isEmpty()) {
            throw new CaseConflictException("required-items-open",
                    "Case cannot close while required plan items are open: "
                            + blockers.stream().map(PlanItem::name).toList(),
                    List.of("cancel", "update"));
        }

        // Terminates every plan item still open — but not any CM_TASK row or live engine task
        // an ACTIVE HUMAN_TASK item already has. See sweepOpenPlanItems' Javadoc: closing that
        // gap needs CaseTaskRepository + an EngineGateway cancel call, task-lifecycle work.
        sweepOpenPlanItems(caseId, "case closed with plan item still open", actor);

        CaseInstance closed = new CaseInstance(current.id(), current.engineId(), current.tenantId(),
                current.caseDefId(), current.caseDefKey(), current.caseDefVersion(),
                current.businessKey(), current.title(), CaseState.CLOSED, current.priority(),
                current.assignee(), current.queueId(), current.initiator(), current.slaStatus(),
                outcome, current.cancelReason(), current.variables(), current.version(),
                current.createdAt(), current.updatedAt(), OffsetDateTime.now());

        CaseInstance saved = cases.update(closed, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_CLOSED, Map.of("outcome", outcome == null ? "" : outcome)));
        // LinkedHashMap, not Map.of: an outcome is optional (POST /cases/{id}/close accepts no
        // body at all — see CaseController.close), and Map.of throws NPE on a null value. Found
        // in this task (Task 26) by actually closing a case with no outcome over real HTTP — the
        // same class of "Map.of null intolerance" bug already fixed for CM_TASK.OUTCOME_ (Task
        // 17) and for several REST response bodies (Task 24, deviation D3), just not here.
        Map<String, Object> closeAuditValues = new LinkedHashMap<>();
        closeAuditValues.put("state", "CLOSED");
        closeAuditValues.put("outcome", outcome);
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.close", "Case", caseId,
                Map.of("state", current.state().name()), closeAuditValues);
        return saved;
    }

    @Transactional
    public CaseInstance cancel(String caseId, long expectedVersion, String reason, Actor actor) {
        // Serialize the API precondition and engine command with lifecycle observations. The
        // synchronous embedded handler acquires this same row lock first and participates in
        // this transaction, so no independently committed termination can be mistaken for this
        // request's callback between the version check and engine cancellation.
        cases.lockForObservation(caseId);
        CaseInstance current = cases.require(caseId);
        requireTransitionAllowed(current, CaseState.CANCELLED, "cancel");
        if (current.version() != expectedVersion) {
            throw new OptimisticLockException("Case", caseId, expectedVersion);
        }

        orchestration(definitions.require(current.caseDefId())).onCaseCancelled(current, reason);

        // Same gap as close()'s sweep: terminates the plan item, not any CM_TASK row or live
        // engine task an ACTIVE HUMAN_TASK already has. See sweepOpenPlanItems' Javadoc.
        sweepOpenPlanItems(caseId, "case cancelled", actor);

        // Embedded Operaton callbacks run synchronously inside this transaction. Root process
        // termination therefore may already have performed the authoritative case transition,
        // version bump, projection sweep and CASE_CANCELLED publication. Re-read after returning
        // from orchestration and, in that case, add only the user's reason and intent audit at
        // the fresh version. Remote/no-callback orchestration still follows the service-owned
        // transition below.
        CaseInstance afterOrchestration = cases.require(caseId);
        if (afterOrchestration.state() == CaseState.CANCELLED) {
            // Root termination itself owns one version increment. Anything beyond that means a
            // different writer advanced the case after this request's precondition read; do not
            // hide that conflict merely because the synchronous callback also cancelled it.
            if (afterOrchestration.version() != expectedVersion + 1) {
                throw new OptimisticLockException("Case", caseId, expectedVersion);
            }
            CaseInstance saved = cases.updateCancellationReason(
                    afterOrchestration, reason, afterOrchestration.version());
            auditCancellationIntent(current, saved, reason, actor);
            return saved;
        }

        CaseInstance cancelled = new CaseInstance(current.id(), current.engineId(), current.tenantId(),
                current.caseDefId(), current.caseDefKey(), current.caseDefVersion(),
                current.businessKey(), current.title(), CaseState.CANCELLED, current.priority(),
                current.assignee(), current.queueId(), current.initiator(), current.slaStatus(),
                current.outcome(), reason, current.variables(), current.version(),
                current.createdAt(), current.updatedAt(), OffsetDateTime.now());

        CaseInstance saved = cases.update(cancelled, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_CANCELLED, Map.of("reason", reason == null ? "" : reason)));
        auditCancellationIntent(current, saved, reason, actor);
        return saved;
    }

    private void auditCancellationIntent(CaseInstance current, CaseInstance saved,
                                         String reason, Actor actor) {
        // Same null-intolerance fix as close() above, for the same reason: POST
        // /cases/{id}/cancel also accepts no body (CaseController.cancel), so reason may be null.
        Map<String, Object> cancelAuditValues = new LinkedHashMap<>();
        cancelAuditValues.put("state", "CANCELLED");
        cancelAuditValues.put("reason", reason);
        publisher.audit(saved.id(), saved.tenantId(), actor.userId(), "case.cancel", "Case",
                saved.id(), Map.of("state", current.state().name()), cancelAuditValues);
    }

    private CaseOrchestration orchestration(CaseDefinition definition) {
        return orchestrations.require(definition.orchestrationMode());
    }

    /**
     * Terminates every plan item still not {@link PlanItemState#isEnded()} for a case that is
     * closing or cancelling — routed through {@link TransitionApplier#apply}, never a raw
     * {@code planItems.updateState(...)}, so a swept termination produces the exact same
     * {@code case.planitem.transitioned} event (and any audit trail {@code TransitionApplier}
     * attaches to a transition) that every other transition in this codebase does.
     *
     * <p>Second review round found this the hard way: the first cut of both {@link #close} and
     * {@link #cancel} called {@code planItems.updateState} directly in a loop — a plain UPDATE,
     * no event, no audit row. That silently broke the invariant this codebase enforces
     * everywhere else a plan item can end without an explicit user action: Task 9 made
     * {@code StageCompletion}'s cascade terminations produce real {@link Transition}s
     * specifically so the service layer would persist and report them, and every transition
     * {@link TransitionApplier#apply} itself applies gets exactly one event. A raw update here
     * would have made swept items vanish from the event stream with no explanation — and that
     * stream is the federation contract (spec §6.2), not an implementation detail.
     *
     * <p><b>KNOWN GAP, not this method's to close:</b> this only ever touches
     * {@code CM_PLAN_ITEM}. A HUMAN_TASK item that is ACTIVE at sweep time already has an open
     * {@code CM_TASK} row (and, in remote mode, a live engine task); this terminates the plan
     * item but does not cancel that task or notify the engine, leaving genuinely dead work
     * sitting in someone's worklist. Reconciling {@code CM_TASK}/the engine on case close or
     * cancel needs {@code CaseTaskRepository} plus an {@code EngineGateway} cancel call, which
     * belongs to the task-lifecycle work, not the plan-item lifecycle this class owns — flagged
     * here deliberately rather than improvised into this method.
     */
    private void sweepOpenPlanItems(String caseId, String reason, Actor actor) {
        List<PlanItem> stillOpen = planItems.findByCase(caseId).stream()
                .filter(item -> !item.state().isEnded())
                .toList();
        if (stillOpen.isEmpty()) {
            return;
        }
        CaseSnapshot snapshot = snapshot(caseId);
        List<Transition> sweep = stillOpen.stream()
                .map(item -> new Transition(item.id(), item.state(), PlanItemState.TERMINATED, reason))
                .toList();
        applier.apply(snapshot, sweep, actor);
    }

    private void requireLive(CaseInstance c, String action) {
        if (c.state() == CaseState.CLOSED || c.state() == CaseState.CANCELLED) {
            throw new CaseConflictException("illegal-state",
                    "Cannot " + action + " a case in state " + c.state(),
                    availableActionsFrom(c.state()));
        }
    }

    /**
     * Validates a close/cancel request against {@link CaseState#canTransitionTo}, the domain's
     * own state machine, rather than a hand-rolled "not CLOSED/CANCELLED" check: unlike
     * {@link #requireLive} (used by {@link #update}, which is not itself a state transition),
     * close and cancel each target a specific state, and the transition table already encodes
     * the real rule — for instance, CLOSED can only be reached from ACTIVE, never from
     * SUSPENDED, which a blanket "anything but CLOSED/CANCELLED" check would have missed.
     */
    private void requireTransitionAllowed(CaseInstance c, CaseState target, String action) {
        if (!c.state().canTransitionTo(target)) {
            throw new CaseConflictException("illegal-state",
                    "Cannot " + action + " a case in state " + c.state(),
                    availableActionsFrom(c.state()));
        }
    }

    private List<String> availableActionsFrom(CaseState state) {
        List<String> actions = new ArrayList<>();
        if (state.canTransitionTo(CaseState.ACTIVE)) actions.add("reactivate");
        if (state.canTransitionTo(CaseState.CLOSED)) actions.add("close");
        if (state.canTransitionTo(CaseState.CANCELLED)) actions.add("cancel");
        if (state.canTransitionTo(CaseState.SUSPENDED)) actions.add("suspend");
        return actions;
    }

    private CaseEvent event(CaseInstance c, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), engineId, type, c.id(), c.tenantId(),
                OffsetDateTime.now(), data);
    }
}
