package org.casemgmt.service;

import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.StageCompletion;
import org.casemgmt.rules.Transition;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The manual half of the plan-item state machine (spec §3.2): enable, start, complete,
 * terminate — the four actions a client actually invokes, as opposed to {@link
 * automatic entry/exit-criteria transitions, which
 * {@code CaseService.reevaluate} applies on every mutation.
 *
 * <p>Legal source states per action (must agree with {@code ActionPolicy.listForPlanItem} in
 * case-management-rest — checked directly against it while writing this class; the two tables
 * are identical, so the API never advertises an action this class then refuses):
 * <ul>
 *   <li>{@code enable}: AVAILABLE -&gt; ENABLED</li>
 *   <li>{@code start}: ENABLED -&gt; ACTIVE</li>
 *   <li>{@code complete}: ACTIVE -&gt; COMPLETED</li>
 *   <li>{@code terminate}: AVAILABLE, ENABLED, or ACTIVE -&gt; TERMINATED</li>
 * </ul>
 * Anything else is a {@link CaseConflictException} (409), naming the actions that ARE legal
 * from the item's current state.
 *
 * <p>The source-state table above is necessary but NOT sufficient — see the containment and
 * cascade section below, and {@link #assertModelInvariants}, for the plan-model rules a legal
 * source state does not imply. {@code ActionPolicy.listForPlanItem} mirrors those too, so the
 * projection and this class still agree action-for-action.
 *
 * <p><b>Persist-once (Task 16 note):</b> the task brief's own draft had {@code transition()}
 * write the row via {@code planItems.updateState(...)} and then hand a {@link Transition} for
 * the same item to {@code TransitionApplier.apply}, which reads the item fresh and writes it
 * AGAIN. That never throws — {@code apply} re-reads before it writes, so it always has the
 * current version — but it is a genuine double write: two UPDATEs and two version bumps for one
 * manual action, and it leaves the caller not knowing the item's final version without a
 * re-read, which breaks Task 4's "build the return value from your own successful write" rule.
 * This class persists the state itself (it already owns a {@link PlanItemRepository}) and calls
 * only {@link TransitionApplier#sideEffects} — engine task creation, milestone achievement, the
 * transitioned event — never {@link TransitionApplier#apply}. See that class's Javadoc for the
 * {@code persist}/{@code sideEffects} split this required.
 *
 * <p><b>Containment and cascade are enforced HERE, not only in the evaluator</b> (final
 * whole-branch review, Important 2 — this replaces an earlier version of this paragraph that
 * argued the opposite, and was wrong). The earlier argument ran: the transition evaluator
 * only admits a child to ENABLED/ACTIVE while its parent stage is ACTIVE
 * ({@link org.casemgmt.rules.StageCompletion#isContained}), so an item could only ever have
 * reached ENABLED under an ACTIVE parent, and any later end of that parent cascade-terminates
 * the child in the same transaction — therefore no containment check was needed here. That is
 * sound for the evaluator-driven path and unsound for this one, for three reasons found
 * together:
 * <ul>
 *   <li><b>{@code complete} on a STAGE.</b> {@link org.casemgmt.rules.StageCompletion#blockingItems}
 *       is what stops a stage completing over live children — and nothing consulted it on this
 *       path. A manual {@code complete} produced a COMPLETED stage with ACTIVE descendants:
 *       verbatim the orphan shape Task 9 spent three review rounds closing on the automatic
 *       path.</li>
 *   <li><b>{@code terminate} on a STAGE.</b> Same hole, worse consequences, and it cascaded
 *       nowhere. Every live descendant was orphaned, and any AVAILABLE descendant was then
 *       PERMANENTLY frozen: {@code isContained} refuses entry to a child whose parent is not
 *       ACTIVE and is consulted only on the AVAILABLE-&gt;entry edge, so the child could never
 *       enter and never end. If it was {@code required}, {@code caseBlockers} blocked close
 *       forever and the case was wedged with no API path out.</li>
 *   <li><b>{@code enable}/{@code start}.</b> The old argument depended on ENABLED being
 *       reachable only through the evaluator. It is not: {@code enable} is manually invokable
 *       on ANY AVAILABLE item regardless of its parent's state, so a client could enable a
 *       child of a never-started stage and then start it — creating exactly the ACTIVE-under-a
 *       -non-ACTIVE-parent state {@code isContained} exists to prevent.</li>
 * </ul>
 * {@code cases.reevaluate} could not repair any of this afterwards: {@code
 * transition calculation derives {@code cascadeTerminatedIds} only from stages whose
 * EXIT CRITERIA fired and {@code claimedForTermination} only from stages IT decided to
 * complete, and it skips every already-ended item — so a manually ended stage is invisible to
 * it. So the enforcement lives here:
 * <ul>
 *   <li>{@code enable} and {@code start} require {@code isContained} (parent stage ACTIVE, or
 *       no parent stage at all);</li>
 *   <li>{@code complete} requires {@code blockingItems} to be empty, and says which items are
 *       in the way when it is not — the same rule, and the same 409 shape, the case-level
 *       {@code close} action already uses;</li>
 *   <li>{@code terminate} cascade-terminates the whole remaining subtree via
 *       {@link org.casemgmt.rules.StageCompletion#childrenToCascadeTerminate}, through
 *       {@link TransitionApplier#apply} so every swept descendant gets a real transition and a
 *       real event — the same treatment {@code CaseService.sweepOpenPlanItems} gives a
 *       close/cancel sweep, and for the same reason.</li>
 * </ul>
 *
 * <p><b>Why not in {@code ActionPolicy} alone:</b> the projection matters (it is fixed there
 * too, so {@code availableActions[]} never advertises a {@code complete} this class then
 * refuses), but a client that POSTs the URL directly never reads the projection. Enforcement
 * has to be on the write path.
 */
public class PlanItemService {

    private static final Set<PlanItemState> TERMINABLE =
            EnumSet.of(PlanItemState.AVAILABLE, PlanItemState.ENABLED, PlanItemState.ACTIVE);

    private final PlanItemRepository planItems;
    private final CaseService cases;
    private final TransitionApplier applier;
    private final EventPublisher publisher;
    private final StageCompletion stageCompletion;

    public PlanItemService(PlanItemRepository planItems, CaseService cases,
                           TransitionApplier applier, EventPublisher publisher,
                           StageCompletion stageCompletion) {
        this.planItems = planItems;
        this.cases = cases;
        this.applier = applier;
        this.publisher = publisher;
        this.stageCompletion = stageCompletion;
    }

    @Transactional
    public PlanItem enable(String caseId, String itemId, long expectedVersion, Actor actor) {
        return transition(caseId, itemId, expectedVersion,
                Set.of(PlanItemState.AVAILABLE), PlanItemState.ENABLED, null, actor);
    }

    @Transactional
    public PlanItem start(String caseId, String itemId, long expectedVersion, Actor actor) {
        return transition(caseId, itemId, expectedVersion,
                Set.of(PlanItemState.ENABLED), PlanItemState.ACTIVE, null, actor);
    }

    @Transactional
    public PlanItem complete(String caseId, String itemId, long expectedVersion, Actor actor) {
        return transition(caseId, itemId, expectedVersion,
                Set.of(PlanItemState.ACTIVE), PlanItemState.COMPLETED, null, actor);
    }

    @Transactional
    public PlanItem terminate(String caseId, String itemId, long expectedVersion,
                              String reason, Actor actor) {
        return transition(caseId, itemId, expectedVersion, TERMINABLE,
                PlanItemState.TERMINATED, reason, actor);
    }

    /**
     * Deliberately private and unannotated, not {@code @Transactional} itself: it only ever
     * runs via plain self-invocation from the four public actions above, each already entered
     * through the Spring proxy and each carrying its own {@code @Transactional} boundary — the
     * same pattern {@code CaseService.reevaluate} uses, and for the same reason (see
     * {@code TransactionManagerConfig}'s self-invocation warning: annotating an internally-called
     * method would claim a transactional boundary the proxy never actually opens for it).
     */
    private PlanItem transition(String caseId, String itemId, long expectedVersion,
                                Set<PlanItemState> legalFrom, PlanItemState to,
                                String reason, Actor actor) {
        PlanItem item = planItems.require(itemId);
        if (!item.caseId().equals(caseId)) {
            throw new CaseConflictException("wrong-case",
                    "Plan item " + itemId + " does not belong to case " + caseId, List.of());
        }
        if (!legalFrom.contains(item.state())) {
            throw new CaseConflictException("illegal-transition",
                    "Cannot move plan item " + itemId + " from " + item.state() + " to " + to,
                    legalActionsFor(item.state()));
        }

        CaseSnapshot snapshot = cases.snapshot(caseId);
        assertModelInvariants(snapshot, item, to);

        PlanItem target = withReason(item.withState(to), reason);

        PlanItem updated;
        try {
            updated = planItems.updateState(target, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }

        // Side effects only — the write above already persisted the state. See class Javadoc.
        applier.sideEffects(snapshot,
                new Transition(itemId, item.state(), to, "manual action"), updated, actor);

        if (to == PlanItemState.TERMINATED) {
            cascadeTerminate(snapshot, item, actor);
        }

        publisher.audit(caseId, snapshot.caseInstance().tenantId(), actor.userId(),
                "planitem." + to.name().toLowerCase(), "PlanItem", itemId,
                Map.of("state", item.state().name()), Map.of("state", to.name()));

        return updated;
    }

    /**
     * The plan-model rules a manual action must satisfy on top of "the source state is legal"
     * (final whole-branch review, Important 2 — see the class Javadoc for the three defects
     * this closes and why {@code cases.reevaluate} could not repair any of them afterwards).
     *
     * <p>Runs against the SAME {@link CaseSnapshot} the side effects below use, taken inside
     * this transaction after the item and its state were read, so the answer cannot disagree
     * with what actually gets written. Both refusals are {@link CaseConflictException} (409) in
     * the shape every other refusal in this codebase uses — a stable {@code code} and the
     * actions that WOULD be legal — because both describe a state the caller can act on rather
     * than a server fault.
     *
     * <p>{@code terminate} is deliberately NOT gated: terminating an item is always legal from
     * any live state (that is the whole point of it being the escape hatch), and its subtree is
     * handled by {@link #cascadeTerminate} instead of being refused.
     *
     * <p><b>What {@code blockingItems} does not block on, and what covers the gap.</b> It ignores
     * a child that is neither {@code required} nor {@code ACTIVE} — an optional child sitting at
     * AVAILABLE or ENABLED does not stop its stage completing (see
     * {@link org.casemgmt.rules.StageCompletion#blockingItems}, which is deliberately shared with
     * the evaluator so both surfaces answer identically). So a manual {@code complete} on such a
     * stage is permitted here, and what stops it orphaning that child is {@code reevaluate}: the
     * evaluator would already have autocompleted the same stage, sweeping the leftover child via
     * {@code childrenToTerminate}, before any client could observe it. Unreachable today for that
     * reason, and stated so nobody removes the compensating behaviour on the grounds that this
     * check "already covers it" — it does not, and the two together are what make the invariant
     * hold.
     *
     * <p><b>The richer refusal is not reachable over HTTP.</b> {@code blocking-items-open} names
     * the specific items in the way, which is the diagnostic a client actually wants. A client
     * never sees it: {@code PlanItemController.act} calls
     * {@code ActionPolicy.assertAllowedOnPlanItem} first, and the projection no longer offers
     * {@code complete} for a blocked stage, so the request is refused earlier with
     * {@code action-not-available} — correct, and silent about WHY. Enforcement is satisfied
     * either way (this check is what a direct service caller hits, and what would catch a
     * projection that drifted); only the diagnostic is lost at the wire. Recorded rather than
     * restructured: making the wire carry it means reordering the controller's gate or teaching
     * {@code ActionPolicy} to explain a refusal it currently only decides.
     */
    private void assertModelInvariants(CaseSnapshot snapshot, PlanItem item, PlanItemState to) {
        if ((to == PlanItemState.ENABLED || to == PlanItemState.ACTIVE)
                && !stageCompletion.isContained(snapshot, item)) {
            throw new CaseConflictException("parent-stage-not-active",
                    "Plan item " + item.id() + " is contained by stage " + item.parentStageId()
                            + ", which is not ACTIVE; a child may only enter while its parent "
                            + "stage is ACTIVE",
                    List.of("terminate"));
        }
        if (to == PlanItemState.COMPLETED) {
            List<PlanItem> blockers = stageCompletion.blockingItems(snapshot, item);
            if (!blockers.isEmpty()) {
                throw new CaseConflictException("blocking-items-open",
                        "Plan item " + item.id() + " cannot complete while these contained items "
                                + "are open: " + blockers.stream().map(PlanItem::name).toList(),
                        List.of("terminate"));
            }
        }
    }

    /**
     * Terminates every still-open descendant of a manually terminated item, at any depth.
     *
     * <p>Routed through {@link TransitionApplier#apply}, never a raw
     * {@code planItems.updateState} loop, for exactly the reason {@code
     * CaseService.sweepOpenPlanItems} documents at length: a raw update produces no
     * {@code case.planitem.transitioned} event, and that event stream is the federation
     * contract (spec §6.2), not an implementation detail. {@code apply} re-reads each item
     * before writing it, so the versions stay correct across the whole subtree without this
     * method tracking them.
     *
     * <p>Uses {@link StageCompletion#childrenToCascadeTerminate} — the exit-criterion variant,
     * which includes ACTIVE descendants — not {@link StageCompletion#childrenToTerminate}: a
     * manual terminate is an unconditional, operator-stated signal exactly like an exit
     * criterion, and it can fire while work is genuinely in flight beneath the item. Called for
     * every terminated item, not only STAGEs: a non-stage has no children, so the list is empty
     * and this costs one stream over the snapshot — cheaper than a type check that could drift
     * if PROCESS_TASK or another type ever gains children.
     */
    private void cascadeTerminate(CaseSnapshot snapshot, PlanItem item, Actor actor) {
        List<PlanItem> descendants = stageCompletion.childrenToCascadeTerminate(snapshot, item);
        if (descendants.isEmpty()) {
            return;
        }
        applier.apply(snapshot, descendants.stream()
                .map(d -> new Transition(d.id(), d.state(), PlanItemState.TERMINATED,
                        "parent plan item terminated"))
                .toList(), actor);
    }

    private PlanItem withReason(PlanItem item, String reason) {
        if (reason == null) {
            return item;
        }
        return new PlanItem(item.id(), item.caseId(), item.planItemDefId(), item.type(), item.name(),
                item.state(), item.parentStageId(), item.adHoc(), item.repetitionNo(),
                item.engineTaskId(), item.processInstanceId(), reason, item.version(),
                item.createdAt(), item.updatedAt(), item.endedAt());
    }

    private List<String> legalActionsFor(PlanItemState state) {
        return switch (state) {
            case AVAILABLE -> List.of("enable", "terminate");
            case ENABLED -> List.of("start", "terminate");
            case ACTIVE -> List.of("complete", "terminate");
            case COMPLETED, TERMINATED -> List.of();
        };
    }
}
