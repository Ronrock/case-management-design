package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Re-evaluates the plan model after every case mutation (spec §4.3).
 *
 * Pure: computes the transitions that should happen and returns them. Applying them —
 * writing rows, creating engine tasks, emitting events — belongs to the service layer.
 */
public class PlanModelEvaluator {

    /**
     * Guard against a modelling bug producing transitions that never settle.
     *
     * With a fixed item set this cap is unreachable by construction: states only move
     * forward (AVAILABLE -> ENABLED/ACTIVE -> COMPLETED/TERMINATED), ended items are never
     * reconsidered ({@link #singlePass}), and a case has finitely many items — so the number
     * of possible transitions across all rounds is bounded by roughly two per item, nowhere
     * near 20. It exists for what this evaluator does NOT yet do: anything that can add a
     * plan item mid-loop (e.g. repetition instantiating a new instance) would break that
     * monotonicity and could in principle spin. This is a guard against that future change,
     * not against today's model — see PlanModelEvaluatorTest.loopGuardThrowsWhenTransitionsNeverSettle,
     * which exercises the guard directly via a test seam rather than trying to construct a
     * model that actually hits it.
     */
    public static final int MAX_ITERATIONS = 20;

    private final CriterionEvaluator criteria;
    private final StageCompletion stageCompletion = new StageCompletion();

    public PlanModelEvaluator(CriterionEvaluator criteria) {
        this.criteria = criteria;
    }

    public List<Transition> evaluate(CaseSnapshot snapshot) {
        List<Transition> all = new ArrayList<>();
        CaseSnapshot current = snapshot;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            List<Transition> round = singlePass(current);
            if (round.isEmpty()) {
                return all;
            }
            all.addAll(round);
            current = apply(current, round);
        }
        throw new PlanModelLoopException(snapshot.caseInstance().id(), MAX_ITERATIONS);
    }

    // Package-private and non-final so tests can override it to exercise the loop guard
    // directly (see PlanModelEvaluatorTest.loopGuardThrowsWhenTransitionsNeverSettle) without
    // needing a model that genuinely never settles — with today's monotone state machine and
    // fixed item set, none does.
    List<Transition> singlePass(CaseSnapshot snapshot) {
        List<Transition> transitions = new ArrayList<>();
        EvaluationContext context = contextOf(snapshot);

        // defKey is a tiebreaker so two definitions sharing a sortOrder still evaluate in a
        // total, deterministic order instead of whatever order planItems() happens to return.
        Comparator<PlanItem> evaluationOrder = Comparator
                .comparingInt((PlanItem i) -> snapshot.definitionOf(i).sortOrder())
                .thenComparing(i -> snapshot.definitionOf(i).defKey());

        List<PlanItem> ordered = snapshot.planItems().stream()
                .sorted(evaluationOrder)
                .toList();

        // Exit criteria are a stronger, author-stated signal than autocomplete (Task 9
        // re-review, Important — the Critical-1 restructuring below had accidentally let
        // autocomplete pre-empt a stage's own exit criterion). Decided first, against the
        // pre-round snapshot: a stage whose exit criterion is satisfied is TERMINATED no
        // matter what its children are doing, and is excluded from the completing-stage
        // batch below so the two can never both claim the same stage.
        List<PlanItem> terminatingStages = ordered.stream()
                .filter(i -> !i.state().isEnded())
                .filter(i -> snapshot.definitionOf(i).type() == PlanItemType.STAGE)
                .filter(i -> exitCriteriaSatisfied(snapshot.definitionOf(i), context))
                .toList();
        Set<String> terminatingStageIds = terminatingStages.stream()
                .map(PlanItem::id)
                .collect(Collectors.toSet());

        // Exit is unconditional, so it cascades to the ENTIRE remaining subtree beneath a
        // terminating stage — children, grandchildren, and so on — including ACTIVE items
        // (unlike autocomplete's childrenToTerminate). See StageCompletion.descendants for
        // why this must walk the whole subtree, not just direct children: a one-level walk
        // left a grandchild orphaned (Task 9 second re-review, Important).
        Set<String> cascadeTerminatedIds = terminatingStages.stream()
                .flatMap(stage -> stageCompletion.childrenToCascadeTerminate(snapshot, stage).stream())
                .map(PlanItem::id)
                .collect(Collectors.toSet());

        // Decided up front, against the pre-round snapshot, so a stage's completion can
        // never race the entry-criteria admission of one of its own children in the same
        // round (Task 9 review, Critical 1) — the decision does not depend on where the
        // stage happens to fall in `ordered` relative to its children. Stages already
        // claimed by exit-criteria termination above are excluded: exit wins.
        List<PlanItem> completingStages = ordered.stream()
                .filter(i -> i.state() == PlanItemState.ACTIVE)
                .filter(i -> snapshot.definitionOf(i).type() == PlanItemType.STAGE)
                .filter(i -> !terminatingStageIds.contains(i.id()))
                .filter(i -> stageCompletion.canComplete(snapshot, i))
                .toList();
        Set<String> completingStageIds = completingStages.stream()
                .map(PlanItem::id)
                .collect(Collectors.toSet());

        // Leftover unstarted descendants of a completing stage — at any depth, not just
        // direct children — are claimed for termination here, before the main loop runs, so
        // they are never independently considered for entry below.
        Set<String> claimedForTermination = completingStages.stream()
                .flatMap(stage -> stageCompletion.childrenToTerminate(snapshot, stage).stream())
                .map(PlanItem::id)
                .collect(Collectors.toSet());

        for (PlanItem item : ordered) {
            if (item.state().isEnded()) {
                continue;
            }

            if (terminatingStageIds.contains(item.id())) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.TERMINATED,
                        "exit criterion met"));
                continue;
            }
            if (cascadeTerminatedIds.contains(item.id())) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.TERMINATED,
                        "parent stage terminated"));
                continue;
            }
            if (completingStageIds.contains(item.id())) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.COMPLETED,
                        "all required children ended"));
                continue;
            }
            if (claimedForTermination.contains(item.id())) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.TERMINATED,
                        "parent stage completed"));
                continue;
            }

            PlanItemDefinition def = snapshot.definitionOf(item);

            if (exitCriteriaSatisfied(def, context)) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.TERMINATED,
                        "exit criterion met"));
                continue;
            }
            if (item.state() == PlanItemState.AVAILABLE
                    && stageCompletion.isContained(snapshot, item)
                    && criteria.allMatch(def.entryCriteria(), context)) {
                transitions.add(new Transition(item.id(), PlanItemState.AVAILABLE,
                        targetOnEntry(def), "entry criterion met"));
            }
        }
        return transitions;
    }

    private boolean exitCriteriaSatisfied(PlanItemDefinition def, EvaluationContext context) {
        return !def.exitCriteria().isEmpty() && criteria.allMatch(def.exitCriteria(), context);
    }

    /**
     * Milestones complete on entry — they mark a fact, they are not worked on.
     * Manual-activation items stop at ENABLED and wait for an explicit start.
     */
    private PlanItemState targetOnEntry(PlanItemDefinition def) {
        if (def.type() == PlanItemType.MILESTONE) {
            return PlanItemState.COMPLETED;
        }
        return def.manualActivation() ? PlanItemState.ENABLED : PlanItemState.ACTIVE;
    }

    private EvaluationContext contextOf(CaseSnapshot snapshot) {
        CaseInstance c = snapshot.caseInstance();
        Map<String, Object> caseAttributes = new LinkedHashMap<>();
        caseAttributes.put("state", c.state().name());
        caseAttributes.put("priority", c.priority().name());
        caseAttributes.put("businessKey", c.businessKey());
        caseAttributes.put("assignee", c.assignee());

        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        for (PlanItemDefinition def : snapshot.definition().planItems()) {
            PlanItem latest = snapshot.latest(def.defKey());
            items.put(def.defKey(), Map.of(
                    "state", latest == null ? PlanItemState.AVAILABLE.name() : latest.state().name(),
                    "type", def.type().name()));
        }
        return new EvaluationContext(caseAttributes, c.variables(), items);
    }

    /**
     * Applies a round's transitions to the existing items only — it creates nothing.
     * Instantiating further repeat instances is the service layer's job (Task 9's
     * {@code repeatable()} query), not the evaluator's: everything this pure function does
     * must be visible in the {@link Transition}s it returns, so a caller that persists
     * exactly those transitions never diverges from what the evaluator went on to reason
     * about internally.
     */
    private CaseSnapshot apply(CaseSnapshot snapshot, List<Transition> transitions) {
        Map<String, PlanItemState> byId = new HashMap<>();
        transitions.forEach(t -> byId.put(t.planItemId(), t.to()));

        List<PlanItem> updated = snapshot.planItems().stream()
                .map(i -> byId.containsKey(i.id()) ? i.withState(byId.get(i.id())) : i)
                .toList();
        return snapshot.withPlanItems(updated);
    }

    /**
     * Definition keys that should get a fresh AVAILABLE instance: repeatable items whose
     * latest instance has just ended and whose entry criteria still hold.
     *
     * Repetition is handled here as a pure query rather than inside singlePass/apply
     * because it creates a new row rather than moving an existing one — instantiating the
     * PlanItem (and persisting it) is the service layer's job (see the class-level
     * invariant that this evaluator creates nothing). The service calls this after
     * applying a round's transitions and, for every definition returned, calls
     * {@link PlanModelInstantiator#repeat(PlanItem, PlanItemDefinition)} on the latest
     * instance before re-evaluating.
     */
    public List<PlanItemDefinition> repeatable(CaseSnapshot snapshot) {
        EvaluationContext context = contextOf(snapshot);
        return snapshot.definition().planItems().stream()
                .filter(PlanItemDefinition::repetition)
                .filter(def -> {
                    PlanItem latest = snapshot.latest(def.defKey());
                    return latest != null && latest.state().isEnded();
                })
                .filter(def -> criteria.allMatch(def.entryCriteria(), context))
                .toList();
    }
}
