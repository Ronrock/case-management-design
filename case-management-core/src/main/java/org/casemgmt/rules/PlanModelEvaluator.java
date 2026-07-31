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

    public static final int MAX_ITERATIONS = 20;

    private final CriterionEvaluator criteria;
    private final PlanModelInstantiator instantiator = new PlanModelInstantiator();

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

    private List<Transition> singlePass(CaseSnapshot snapshot) {
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
     * Applies a round's transitions to produce the snapshot the next round evaluates against.
     *
     * A repeatable item (spec §3.2) that completes gets a fresh AVAILABLE instance right away —
     * that new instance, not the completed one, is what {@link CaseSnapshot#latest} and sibling
     * criteria see from here on. This is also what makes truly mutually-triggering criteria
     * (each satisfied by the other's non-completion) spin forever instead of settling after one
     * round: every round completes-then-repeats both items, so neither's *latest* instance is
     * ever seen as COMPLETED by the other. That divergence is exactly what MAX_ITERATIONS exists
     * to catch.
     */
    private CaseSnapshot apply(CaseSnapshot snapshot, List<Transition> transitions) {
        Map<String, PlanItemState> byId = new HashMap<>();
        transitions.forEach(t -> byId.put(t.planItemId(), t.to()));

        List<PlanItem> updated = new ArrayList<>();
        for (PlanItem item : snapshot.planItems()) {
            PlanItemState newState = byId.get(item.id());
            if (newState == null) {
                updated.add(item);
                continue;
            }
            PlanItem transitioned = item.withState(newState);
            updated.add(transitioned);

            PlanItemDefinition def = snapshot.definitionOf(item);
            if (newState == PlanItemState.COMPLETED && def.repetition()) {
                updated.add(instantiator.repeat(transitioned, def));
            }
        }
        return snapshot.withPlanItems(updated);
    }
}
