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
 */
public class StageCompletion {

    public boolean canComplete(CaseSnapshot snapshot, PlanItem stage) {
        return blockingItems(snapshot, stage).isEmpty()
                && children(snapshot, stage).stream().anyMatch(c -> c.state().isEnded());
    }

    public List<PlanItem> blockingItems(CaseSnapshot snapshot, PlanItem stage) {
        return children(snapshot, stage).stream()
                .filter(child -> !child.state().isEnded())
                .filter(child -> snapshot.definitionOf(child).required())
                .toList();
    }

    public boolean caseCanClose(CaseSnapshot snapshot) {
        return caseBlockers(snapshot).isEmpty();
    }

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
     *   <li>This method is consulted only on the AVAILABLE-&gt;entry transition. It is
     *       intentionally NOT re-checked for items that are already ACTIVE: if a parent
     *       stage later terminates or completes, this class does not reach back in and
     *       terminate already-active children. That cascade is out of scope for this task
     *       and is left as a follow-up rather than being silently half-implemented.</li>
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
