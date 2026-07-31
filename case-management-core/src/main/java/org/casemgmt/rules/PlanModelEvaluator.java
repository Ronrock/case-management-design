package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.util.*;

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

        List<PlanItem> ordered = snapshot.planItems().stream()
                .sorted(Comparator.comparingInt(i -> snapshot.definitionOf(i).sortOrder()))
                .toList();

        for (PlanItem item : ordered) {
            if (item.state().isEnded()) {
                continue;
            }
            PlanItemDefinition def = snapshot.definitionOf(item);

            if (!def.exitCriteria().isEmpty() && criteria.allMatch(def.exitCriteria(), context)) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.TERMINATED,
                        "exit criterion met"));
                continue;
            }
            if (item.state() == PlanItemState.AVAILABLE
                    && criteria.allMatch(def.entryCriteria(), context)) {
                transitions.add(new Transition(item.id(), PlanItemState.AVAILABLE,
                        targetOnEntry(def), "entry criterion met"));
            }
        }
        return transitions;
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
}
