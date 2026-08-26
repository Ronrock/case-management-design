package org.casemgmt.orchestration;

import org.casemgmt.domain.PlanItemState;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.rules.JuelCriterionEvaluator;
import org.casemgmt.rules.PlanModelEvaluator;
import org.casemgmt.rules.PlanModelInstantiator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.def;
import static org.casemgmt.rules.PlanModelFixtures.definition;
import static org.casemgmt.rules.PlanModelFixtures.item;
import static org.casemgmt.rules.PlanModelFixtures.snapshot;

class PlanModelOrchestrationTest {

    private final PlanModelOrchestration orchestration = new PlanModelOrchestration(
            new PlanModelEvaluator(new JuelCriterionEvaluator()), new PlanModelInstantiator());

    @Test
    void preservesInitialInstantiationAndEvaluationBehavior() {
        var definition = definition(def("review", PlanItemType.HUMAN_TASK));
        var initial = orchestration.initialItems("eng-a:1", definition);

        assertThat(orchestration.mode()).isEqualTo(OrchestrationMode.PLAN_MODEL);
        assertThat(initial).singleElement().extracting(i -> i.state())
                .isEqualTo(PlanItemState.AVAILABLE);

        var current = snapshot(definition,
                List.of(item("pi-1", "review", PlanItemType.HUMAN_TASK,
                        PlanItemState.AVAILABLE)), Map.of());
        assertThat(orchestration.evaluate(current)).singleElement()
                .extracting(t -> t.to()).isEqualTo(PlanItemState.ACTIVE);
    }
}
