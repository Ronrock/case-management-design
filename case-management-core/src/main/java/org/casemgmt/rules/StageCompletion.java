package org.casemgmt.rules;

import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.domain.PlanItemState;

import java.util.List;

/**
 * "Required" gating (spec §3.2): a stage cannot complete, and a case cannot close,
 * while a required plan item is unfinished. blockingItems() feeds both the evaluator
 * and the 409 response body, so the API can say exactly what is in the way.
 *
 * Also owns CMMN containment (carried forward from Task 8's review): a plan item whose
 * definition nests it inside a stage may only be considered for entry while that stage
 * instance is ACTIVE. See {@link #isContained(CaseSnapshot, PlanItem)} for the exact
 * semantics and the deliberate limits of what is (and is not) enforced.
 *
 * <p><b>CMMN autocomplete (Task 9 review, Critical 1):</b> the first cut of
 * {@code canComplete} — "no required child unfinished AND at least one child ended" —
 * let a stage complete in the very same {@code evaluate()} round that admitted a fresh
 * child beneath it (a live repro: stage with one optional child COMPLETED and another
 * optional child still AVAILABLE would complete the stage while independently admitting
 * the AVAILABLE child to ACTIVE, leaving a COMPLETED stage with a live ACTIVE child that
 * nothing ever revisits). The fix has two parts, both required together:
 * <ol>
 *   <li>{@link #blockingItems} now also blocks on any child that is currently ACTIVE,
 *       not just on unfinished required children — a stage cannot complete while work is
 *       actively in flight beneath it, required or not.</li>
 *   <li>{@link #childrenToTerminate} identifies the AVAILABLE/ENABLED children a
 *       completing stage leaves behind. {@link PlanModelEvaluator} decides, up front and
 *       against the pre-round snapshot, which stages complete this round, and then
 *       terminates their leftover AVAILABLE/ENABLED children as real {@link Transition}s
 *       in that same round — pre-empting those children's own entry-criteria admission
 *       instead of racing it. This makes the fix independent of definition sortOrder
 *       (a stage no longer needs a lower sortOrder than its children to be evaluated
 *       "first"): completions and their fallout are computed as one batch before any
 *       entry/exit criteria are evaluated for the round.</li>
 * </ol>
 *
 * <p><b>Exit-criteria precedence (Task 9 re-review, Important):</b> the batching above
 * introduced a regression — {@code PlanModelEvaluator} was checking "does this stage
 * autocomplete" before "does this stage's own exit criterion fire," so a stage with both a
 * satisfied exit criterion and all-ended children came out COMPLETED instead of TERMINATED.
 * An explicit, author-written exit criterion is a stronger signal than autocomplete and must
 * win: a stage whose exit criteria are satisfied is excluded from the completing-stage batch
 * entirely (see {@code PlanModelEvaluator.singlePass}) and is TERMINATED instead, regardless
 * of what its children are doing. That raised the question the batching for Critical 1 had
 * sidestepped by construction: what happens to that stage's children? Decision: they cascade
 * — see {@link #childrenToCascadeTerminate}, which (unlike {@link #childrenToTerminate})
 * includes ACTIVE children too, because an exit criterion is unconditional and does not wait
 * for work in flight the way autocomplete does.</p>
 */
public class StageCompletion {

    public boolean canComplete(CaseSnapshot snapshot, PlanItem stage) {
        return blockingItems(snapshot, stage).isEmpty();
    }

    /**
     * Children that keep {@code stage} from completing: any unfinished required child, or
     * any child currently ACTIVE (required or not — active work is never silently discarded,
     * see {@link #childrenToTerminate}). Feeds both {@link #canComplete} and the API's 409
     * "why can't this complete" response body.
     *
     * <p>Deliberately NOT shared code with {@link #caseBlockers}, even though both filter
     * "unfinished + required": {@code caseBlockers} answers a case-wide question ("can the
     * whole case close") where the ACTIVE-child rule doesn't apply the same way — a case
     * isn't blocked just because some optional, non-required item happens to be ACTIVE, only
     * because a *required* one is still open. Folding these into one method would either
     * over-block case closure on every in-flight optional item, or under-block stage
     * completion by dropping the ACTIVE-child rule Critical 1 depends on. Keep them separate
     * on purpose; do not "unify" them.
     */
    public List<PlanItem> blockingItems(CaseSnapshot snapshot, PlanItem stage) {
        return children(snapshot, stage).stream()
                .filter(child -> !child.state().isEnded())
                .filter(child -> snapshot.definitionOf(child).required()
                        || child.state() == PlanItemState.ACTIVE)
                .toList();
    }

    /**
     * AVAILABLE/ENABLED children left behind when {@code stage} completes. CMMN autocomplete
     * discards leftover, never-started children by terminating them — never by silently
     * dropping them — so the caller (today, {@link PlanModelEvaluator#singlePass}) must turn
     * every one of these into a real TERMINATED {@link Transition} in the same round the
     * stage completes. Because {@link #blockingItems} already excludes ACTIVE children from
     * ever reaching a completing stage, this list can only ever contain AVAILABLE/ENABLED
     * items — never ACTIVE ones.
     */
    public List<PlanItem> childrenToTerminate(CaseSnapshot snapshot, PlanItem stage) {
        return children(snapshot, stage).stream()
                .filter(c -> c.state() == PlanItemState.AVAILABLE || c.state() == PlanItemState.ENABLED)
                .toList();
    }

    /**
     * ALL of a stage's not-yet-ended children — AVAILABLE, ENABLED, or ACTIVE — used when
     * the stage itself terminates via its own exit criterion rather than autocompleting.
     * Unlike {@link #childrenToTerminate}, this deliberately includes ACTIVE children: an
     * exit criterion is an unconditional, author-stated signal (spec §3.2) that fires
     * regardless of what the stage's children are doing, so termination must cascade to all
     * of them or a TERMINATED stage could be left with a live ACTIVE child that nothing ever
     * revisits — the same orphan shape Critical 1 closed for the COMPLETED case, but exit
     * criteria cannot rely on {@link #blockingItems} to make it structurally unreachable
     * (exit ignores blocking entirely; that's the whole point of it being a stronger signal).
     * Cascades one level only — a nested stage among these children is itself terminated, but
     * its own children are not recursively visited by this method.
     */
    public List<PlanItem> childrenToCascadeTerminate(CaseSnapshot snapshot, PlanItem stage) {
        return children(snapshot, stage).stream()
                .filter(c -> !c.state().isEnded())
                .toList();
    }

    public boolean caseCanClose(CaseSnapshot snapshot) {
        return caseBlockers(snapshot).isEmpty();
    }

    /**
     * Required, unfinished items anywhere in the case — the case-wide analog of
     * {@link #blockingItems}, but intentionally simpler: it does NOT block on ACTIVE
     * non-required items the way {@code blockingItems} does. That rule exists in
     * {@code blockingItems} specifically to stop a stage's own autocomplete from racing an
     * in-flight optional child (Critical 1); it has no case-closure analog here — an
     * optional item quietly running does not by itself justify refusing to close a case that
     * has satisfied every required item. Do not "unify" the two methods; the divergence is
     * intentional, not an oversight.
     */
    public List<PlanItem> caseBlockers(CaseSnapshot snapshot) {
        return snapshot.planItems().stream()
                .filter(i -> !i.state().isEnded())
                .filter(i -> snapshot.definitionOf(i).required())
                .toList();
    }

    /**
     * CMMN containment: false whenever {@code item} has a parent stage instance and that
     * instance is not ACTIVE.
     *
     * Deliberate design decisions (see Task 9 brief / Task 8 review):
     * <ul>
     *   <li>A child of a stage that is still AVAILABLE or ENABLED (never started) is NOT
     *       contained, so the evaluator's entry-criteria block (the only caller of this
     *       method today) leaves the child sitting at AVAILABLE — it is never offered
     *       entry until the parent stage transitions to ACTIVE.</li>
     *   <li>This method itself does NOT reach back in and terminate an already-ACTIVE child
     *       when its parent stage ends — it is consulted only on the AVAILABLE-&gt;entry
     *       transition. But the two ways a stage ends are no longer symmetric: a COMPLETED
     *       stage can never have an ACTIVE child in the first place, because
     *       {@link #blockingItems} refuses to let a stage complete while one exists (Task 9
     *       review, Critical 1) — the cascade question doesn't arise. A TERMINATED stage
     *       (its own exit criterion fired) is different: exit is unconditional, so it CAN
     *       fire while a child is ACTIVE, and {@link PlanModelEvaluator#singlePass} does
     *       cascade-terminate every remaining child via {@link #childrenToCascadeTerminate}
     *       in that case (Task 9 re-review, Important) — just not through this method.</li>
     *   <li>A top-level item (no {@code parentStageId}) always returns true — containment
     *       does not apply to it.</li>
     *   <li>If {@code parentStageId} is set but no such plan item exists in the snapshot
     *       (a dangling reference, which should not happen in practice), this method fails
     *       closed and returns false rather than assuming containment.</li>
     * </ul>
     */
    public boolean isContained(CaseSnapshot snapshot, PlanItem item) {
        String parentStageId = item.parentStageId();
        if (parentStageId == null) {
            return true;
        }
        return snapshot.planItems().stream()
                .filter(i -> parentStageId.equals(i.id()))
                .findFirst()
                .map(parent -> parent.state() == PlanItemState.ACTIVE)
                .orElse(false);
    }

    private List<PlanItem> children(CaseSnapshot snapshot, PlanItem stage) {
        return snapshot.planItems().stream()
                .filter(i -> stage.id().equals(i.parentStageId()))
                .toList();
    }

    /** Definition-level lookup used when a stage has no instantiated children yet. */
    public List<PlanItemDefinition> childDefinitions(CaseSnapshot snapshot, PlanItem stage) {
        String stageKey = snapshot.definitionOf(stage).defKey();
        return snapshot.definition().planItems().stream()
                .filter(d -> stageKey.equals(d.parentStageKey()))
                .toList();
    }
}
