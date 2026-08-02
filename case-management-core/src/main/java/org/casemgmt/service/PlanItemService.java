package org.casemgmt.service;

import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The manual half of the plan-item state machine (spec §3.2): enable, start, complete,
 * terminate — the four actions a client actually invokes, as opposed to {@link
 * org.casemgmt.rules.PlanModelEvaluator}'s automatic entry/exit-criteria transitions, which
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
 * <p><b>Containment and {@code start} (Task 9's rule):</b> {@code PlanModelEvaluator} only
 * admits a child to ENABLED/ACTIVE while its parent stage is ACTIVE ({@code
 * StageCompletion.isContained}), so an item can only ever reach ENABLED — the source state
 * {@code start} requires — while its parent was ACTIVE at that moment. No separate containment
 * check is added here for {@code start} itself: if the parent stage has since stopped being
 * ACTIVE (completed or terminated), that same {@code reevaluate} cascade-terminates every
 * still-open descendant, including ENABLED ones, in the SAME transaction as whatever mutation
 * ended the parent (this class's own actions are {@code @Transactional}, same as {@code
 * CaseService}'s). By the time a client's {@code start} call can observe the row, the item is
 * therefore either still legitimately ENABLED under an ACTIVE parent, or it has already been
 * cascade-terminated — which the existing state check ({@code TERMINATED} not in {@code
 * legalFrom}) and, for a client racing on a stale read, the optimistic-version check already
 * reject. A redundant "is the parent ACTIVE" read here would not close any gap the state/version
 * checks leave open.
 */
public class PlanItemService {

    private static final Set<PlanItemState> TERMINABLE =
            EnumSet.of(PlanItemState.AVAILABLE, PlanItemState.ENABLED, PlanItemState.ACTIVE);

    private final PlanItemRepository planItems;
    private final CaseService cases;
    private final TransitionApplier applier;
    private final EventPublisher publisher;

    public PlanItemService(PlanItemRepository planItems, CaseService cases,
                           TransitionApplier applier, EventPublisher publisher) {
        this.planItems = planItems;
        this.cases = cases;
        this.applier = applier;
        this.publisher = publisher;
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

        publisher.audit(caseId, snapshot.caseInstance().tenantId(), actor.userId(),
                "planitem." + to.name().toLowerCase(), "PlanItem", itemId,
                Map.of("state", item.state().name()), Map.of("state", to.name()));

        cases.reevaluate(caseId, actor);

        // `updated` is already exactly right for a terminal state: PlanModelEvaluator and
        // StageCompletion both categorically skip ended items (isEnded()), so nothing reevaluate()
        // does can ever touch this row again — re-reading would only risk a concurrent writer's
        // unrelated change (Task 4's rule). enable/start leave the item open, and reevaluate() —
        // running in this same transaction — can, in rare cases (the item's own exit criterion
        // firing, or a parent-stage cascade), transition it further; that IS a legitimate further
        // write this call is responsible for reflecting, so it re-reads only in that case.
        return to.isEnded() ? updated : planItems.require(itemId);
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
