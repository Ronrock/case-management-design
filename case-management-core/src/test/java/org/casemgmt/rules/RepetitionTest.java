package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.*;

class RepetitionTest {

    @Test
    void repeatCreatesANewAvailableInstanceWithAnIncrementedCounter() {
        PlanItemDefinition def = def("investigate", PlanItemType.HUMAN_TASK, "stage",
                true, false, true, List.of(), List.of(), 20);
        PlanItem first = item("pi-1", "investigate", PlanItemType.HUMAN_TASK,
                PlanItemState.COMPLETED, "pi-stage");

        PlanItem second = new PlanModelInstantiator().repeat(first, def);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.state()).isEqualTo(PlanItemState.AVAILABLE);
        assertThat(second.repetitionNo()).isEqualTo(2);
        assertThat(second.parentStageId()).isEqualTo("pi-stage");
    }

    @Test
    void criteriaSeeTheMostRecentInstanceOfARepeatedItem() {
        CaseDefinition def = definition(
                def("investigate", PlanItemType.HUMAN_TASK, null, true, false, true,
                        List.of(), List.of(), 10),
                def("after", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${items.investigate.state == 'ACTIVE'}"), List.of(), 20));

        var snapshot = snapshot(def, List.of(
                item("pi-1", "investigate", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-2", "investigate", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE),
                item("pi-after", "after", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)),
                Map.of());

        assertThat(new PlanModelEvaluator(new JuelCriterionEvaluator()).evaluate(snapshot))
                .extracting(Transition::planItemId).contains("pi-after");
    }
}
