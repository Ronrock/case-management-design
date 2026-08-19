package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.casemgmt.rules.PlanModelFixtures.*;

class PlanModelEvaluatorTest {

    private final PlanModelEvaluator evaluator = new PlanModelEvaluator(new JuelCriterionEvaluator());

    @Test
    void instantiatesEveryDefinedPlanItemAsAvailable() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE),
                def("task", PlanItemType.HUMAN_TASK));

        List<PlanItem> items = new PlanModelInstantiator().initialItems("eng-a:1", def);

        assertThat(items).hasSize(2);
        assertThat(items).allMatch(i -> i.state() == PlanItemState.AVAILABLE);
        assertThat(items).allMatch(i -> i.caseId().equals("eng-a:1"));
    }

    @Test
    void ungatedAutoActivatingItemGoesStraightToActive() {
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).singleElement()
                .satisfies(t -> {
                    assertThat(t.planItemId()).isEqualTo("pi-1");
                    assertThat(t.from()).isEqualTo(PlanItemState.AVAILABLE);
                    assertThat(t.to()).isEqualTo(PlanItemState.ACTIVE);
                });
    }

    @Test
    void manualActivationStopsAtEnabled() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, true, false, false, List.of(), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).singleElement()
                .extracting(Transition::to).isEqualTo(PlanItemState.ENABLED);
    }

    @Test
    void enabledItemsAreNotStartedAutomatically() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, true, false, false, List.of(), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.ENABLED)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }

    @Test
    void unmetEntryCriterionKeepsTheItemAvailable() {
        CaseDefinition def = definition(
                def("gated", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${vars.amount > 1000}"), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "gated", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of("amount", 100));

        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }

    @Test
    void metEntryCriterionActivatesTheItem() {
        CaseDefinition def = definition(
                def("gated", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${vars.amount > 1000}"), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "gated", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of("amount", 5000));

        assertThat(evaluator.evaluate(snapshot)).singleElement()
                .extracting(Transition::to).isEqualTo(PlanItemState.ACTIVE);
    }

    @Test
    void criteriaSeeSiblingStates() {
        CaseDefinition def = definition(
                def("first", PlanItemType.HUMAN_TASK),
                def("second", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${items.first.state == 'COMPLETED'}"), List.of(), 20));
        var snapshot = snapshot(def, List.of(
                item("pi-1", "first", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-2", "second", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).singleElement()
                .satisfies(t -> assertThat(t.planItemId()).isEqualTo("pi-2"));
    }

    @Test
    void reachesAFixpointAcrossChainedCriteria() {
        // a completes -> milestone m achieves -> b activates. One evaluate() call must do both.
        CaseDefinition def = definition(
                def("a", PlanItemType.HUMAN_TASK),
                def("m", PlanItemType.MILESTONE, null, false, false, false,
                        List.of("${items.a.state == 'COMPLETED'}"), List.of(), 20),
                def("b", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${items.m.state == 'COMPLETED'}"), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-a", "a", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-m", "m", PlanItemType.MILESTONE, PlanItemState.AVAILABLE),
                item("pi-b", "b", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)), Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).extracting(Transition::planItemId).containsExactly("pi-m", "pi-b");
        assertThat(transitions).extracting(Transition::to)
                .containsExactly(PlanItemState.COMPLETED, PlanItemState.ACTIVE);
    }

    @Test
    void evaluatesInSortOrderSoTransitionsAreDeterministic() {
        CaseDefinition def = definition(
                def("late", PlanItemType.HUMAN_TASK, null, false, false, false, List.of(), List.of(), 99),
                def("early", PlanItemType.HUMAN_TASK, null, false, false, false, List.of(), List.of(), 1));
        var snapshot = snapshot(def, List.of(
                item("pi-late", "late", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE),
                item("pi-early", "early", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)), Map.of());

        assertThat(evaluator.evaluate(snapshot))
                .extracting(Transition::planItemId).containsExactly("pi-early", "pi-late");
    }

    @Test
    void loopGuardThrowsWhenTransitionsNeverSettle() {
        // With a fixed item set and a monotone state machine (states only move forward, ended
        // items are never reconsidered), no real model can make singlePass() return non-empty
        // transitions for 20 straight rounds — the cap is unreachable by construction today.
        // It exists for a future change (e.g. repetition creating items mid-loop) that could
        // break that monotonicity, so it's exercised directly here via a test seam: a subclass
        // whose singlePass() always reports a transition, forcing every round to look "still
        // moving" regardless of what the criteria or item states actually are.
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of());

        PlanModelEvaluator neverSettles = new PlanModelEvaluator(new JuelCriterionEvaluator()) {
            @Override
            List<Transition> singlePass(CaseSnapshot ignored, java.util.Set<String> stagesActivatedPreviousRound) {
                return List.of(new Transition("pi-1", PlanItemState.AVAILABLE, PlanItemState.ACTIVE,
                        "test double: never settles"));
            }
        };

        assertThatThrownBy(() -> neverSettles.evaluate(snapshot))
                .isInstanceOf(PlanModelLoopException.class)
                .hasMessageContaining("20");
    }

    @Test
    void endedItemsAreNeverReconsidered() {
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        var snapshot = snapshot(def, List.of(
                item("pi-1", "task", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-2", "task", PlanItemType.HUMAN_TASK, PlanItemState.TERMINATED)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }
}
