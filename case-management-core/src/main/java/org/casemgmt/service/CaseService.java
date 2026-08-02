package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    /**
     * Sanity ceiling on how many times a single repeatable plan-item definition may be
     * instantiated for one case. See {@link #reevaluate} for why this exists: without it,
     * a repeatable item whose entry criteria are trivially satisfied (most obviously a
     * MILESTONE, which completes the instant it enters) gets one more AVAILABLE instance
     * every time {@code reevaluate} runs — including runs triggered by an unrelated no-op
     * {@link #update}, since {@code CaseRepository.update} bumps the version even when the
     * patch changes nothing observable. Left unguarded that is unbounded row (and, for
     * HUMAN_TASK/PROCESS_TASK repeats, engine task) growth as a side effect of ordinary API
     * traffic, not just a pathological case definition. 500 is deliberately generous — far
     * beyond any legitimate repetition count in this PoC's domain — so it only ever bites a
     * genuine runaway, the same way {@code PlanModelEvaluator.MAX_ITERATIONS} is a ceiling
     * unreachable by correct models rather than a real business limit.
     */
    static final int MAX_REPETITIONS_PER_ITEM = 500;

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    private final CaseRepository cases;
    private final CaseDefinitionRepository definitions;
    private final PlanItemRepository planItems;
    private final MilestoneRepository milestones;
    private final ParticipantRepository participants;
    private final PlanModelEvaluator evaluator;
    private final PlanModelInstantiator instantiator;
    private final StageCompletion stageCompletion;
    private final TransitionApplier applier;
    private final EventPublisher publisher;
    private final String engineId;

    public CaseService(CaseRepository cases, CaseDefinitionRepository definitions,
                       PlanItemRepository planItems, MilestoneRepository milestones,
                       ParticipantRepository participants, PlanModelEvaluator evaluator,
                       PlanModelInstantiator instantiator, StageCompletion stageCompletion,
                       TransitionApplier applier, EventPublisher publisher, String engineId) {
        this.cases = cases;
        this.definitions = definitions;
        this.planItems = planItems;
        this.milestones = milestones;
        this.participants = participants;
        this.evaluator = evaluator;
        this.instantiator = instantiator;
        this.stageCompletion = stageCompletion;
        this.applier = applier;
        this.publisher = publisher;
        this.engineId = engineId;
    }

    @Transactional
    public CaseInstance create(String caseDefKey, String tenantId, String businessKey, String title,
                               CasePriority priority, Map<String, Object> variables, Actor actor) {
        CaseDefinition def = definitions.findLatest(caseDefKey, tenantId)
                .orElseThrow(() -> new NotFoundException("CaseDefinition", caseDefKey));

        OffsetDateTime now = OffsetDateTime.now();
        CaseInstance created = new CaseInstance(CaseIds.newCaseId(engineId), engineId, tenantId,
                def.id(), def.key(), def.versionNo(), businessKey, title, CaseState.ACTIVE,
                priority == null ? CasePriority.MEDIUM : priority, null, null, actor.userId(),
                "NONE", null, null, variables == null ? Map.of() : variables, 0L, now, now, null);
        cases.insert(created);

        participants.insert(CaseIds.newId(), created.id(), actor.userId(), null, "owner");
        instantiator.initialItems(created.id(), def).forEach(planItems::insert);

        publisher.publish(event(created, EventTypes.CASE_CREATED, Map.of(
                "caseDefinitionKey", def.key(), "state", created.state().name(),
                "businessKey", businessKey == null ? "" : businessKey)));
        publisher.audit(created.id(), tenantId, actor.userId(), "case.create", "Case",
                created.id(), null, Map.of("state", created.state().name(), "title", title));

        // reevaluate() only ever touches CM_PLAN_ITEM/CM_TASK/CM_MILESTONE, never CM_CASE, so
        // `created` already IS the case row's final state for this call — no re-read needed.
        reevaluate(created.id(), actor);
        return created;
    }

    public CaseInstance get(String caseId) {
        return cases.require(caseId);
    }

    public CaseSnapshot snapshot(String caseId) {
        CaseInstance instance = cases.require(caseId);
        return new CaseSnapshot(instance, definitions.require(instance.caseDefId()),
                planItems.findByCase(caseId));
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
                    patched.businessKey(), String.valueOf(patch.get("title")), patched.state(),
                    patched.priority(), patched.assignee(), patched.queueId(), patched.initiator(),
                    patched.slaStatus(), patched.outcome(), patched.cancelReason(),
                    patched.variables(), patched.version(), patched.createdAt(),
                    patched.updatedAt(), patched.closedAt());
        }
        if (patch.containsKey("variables")) {
            patched = patched.withVariables((Map<String, Object>) patch.get("variables"));
        }

        CaseInstance saved = cases.update(patched, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_UPDATED, Map.of("fields", patch.keySet())));
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.update", "Case", caseId,
                Map.of("title", current.title(), "variables", current.variables()),
                Map.of("title", saved.title(), "variables", saved.variables()));

        reevaluate(caseId, actor);

        // `saved` is what CaseRepository.update already proved committed (see its Javadoc):
        // re-reading here would risk returning a concurrent writer's row as if it confirmed
        // this call's own write. reevaluate() above never touches CM_CASE, so `saved` is still
        // exactly correct.
        return saved;
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
        List<PlanItem> blockers = stageCompletion.caseBlockers(snapshot);
        if (!blockers.isEmpty()) {
            throw new CaseConflictException("required-items-open",
                    "Case cannot close while required plan items are open: "
                            + blockers.stream().map(PlanItem::name).toList(),
                    List.of("cancel", "update"));
        }

        reevaluate(caseId, actor);
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
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.close", "Case", caseId,
                Map.of("state", current.state().name()), Map.of("state", "CLOSED", "outcome", outcome));
        return saved;
    }

    @Transactional
    public CaseInstance cancel(String caseId, long expectedVersion, String reason, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireTransitionAllowed(current, CaseState.CANCELLED, "cancel");

        // Same gap as close()'s sweep: terminates the plan item, not any CM_TASK row or live
        // engine task an ACTIVE HUMAN_TASK already has. See sweepOpenPlanItems' Javadoc.
        sweepOpenPlanItems(caseId, "case cancelled", actor);

        CaseInstance cancelled = new CaseInstance(current.id(), current.engineId(), current.tenantId(),
                current.caseDefId(), current.caseDefKey(), current.caseDefVersion(),
                current.businessKey(), current.title(), CaseState.CANCELLED, current.priority(),
                current.assignee(), current.queueId(), current.initiator(), current.slaStatus(),
                current.outcome(), reason, current.variables(), current.version(),
                current.createdAt(), current.updatedAt(), OffsetDateTime.now());

        CaseInstance saved = cases.update(cancelled, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_CANCELLED, Map.of("reason", reason == null ? "" : reason)));
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.cancel", "Case", caseId,
                Map.of("state", current.state().name()), Map.of("state", "CANCELLED", "reason", reason));
        return saved;
    }

    /**
     * Re-evaluates the plan model after every mutation (spec §4.3) and applies what it decides.
     *
     * <p>Deliberately package-visible-and-unannotated, not public/{@code @Transactional}: it is
     * called only via plain self-invocation from {@link #create}/{@link #update}, which — per
     * this class's own Javadoc warning — the Spring AOP proxy never sees. Annotating it would
     * claim a transactional boundary this method does not actually get; leaving the annotation
     * off is the honest description of what happens (it runs inside whatever transaction the
     * public caller already opened, nothing more).
     *
     * <p>Repetition guard: {@code evaluator.repeatable(...)} is a pure query that answers
     * "has the latest instance of this repeatable definition ended, and do its entry criteria
     * currently hold" — it does not, and structurally cannot on its own, know whether anything
     * has actually changed since the previous cycle. For a repeatable item whose criteria are
     * trivially true (the sharpest case: a MILESTONE, which the evaluator completes the instant
     * it enters — see {@code PlanModelEvaluator.targetOnEntry}), that condition is satisfied
     * again immediately after this method mints a fresh instance, so the very next call to
     * {@code reevaluate} — for ANY reason, including a client calling {@link #update} with an
     * empty patch purely to force a round-trip — would mint another one, forever. This method
     * stops that by refusing to instantiate past {@link #MAX_REPETITIONS_PER_ITEM} for a given
     * definition. It skips rather than throws: failing this case mutation outright because some
     * unrelated repeatable item on the case hit its ceiling would be a disproportionate blast
     * radius, and per the task brief's own framing, no further repetition is a safer outcome
     * than unbounded repetition.
     */
    void reevaluate(String caseId, Actor actor) {
        CaseSnapshot snapshot = snapshot(caseId);
        List<Transition> transitions = evaluator.evaluate(snapshot);
        if (!transitions.isEmpty()) {
            applier.apply(snapshot, transitions, actor);
        }

        CaseSnapshot postTransition = snapshot(caseId);
        for (PlanItemDefinition repeatable : evaluator.repeatable(postTransition)) {
            PlanItem latest = postTransition.latest(repeatable.defKey());
            if (latest.repetitionNo() >= MAX_REPETITIONS_PER_ITEM) {
                // Loud enough to notice a genuine runaway (a stuck repetition count is otherwise
                // invisible — nothing else here fails or even changes shape), quiet enough not to
                // fail the mutation that happened to trigger it (see the class-level reasoning for
                // why this skips rather than throws).
                log.warn("Case {}: repeatable plan item '{}' hit MAX_REPETITIONS_PER_ITEM ({}) — "
                                + "no further instances will be created. This usually means the "
                                + "item's entry criteria don't depend on anything that changes "
                                + "between cycles; check the case definition.",
                        caseId, repeatable.defKey(), MAX_REPETITIONS_PER_ITEM);
                continue;
            }
            planItems.insert(instantiator.repeat(latest, repeatable));
        }
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
