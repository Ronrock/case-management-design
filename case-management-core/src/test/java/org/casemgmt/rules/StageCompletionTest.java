package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.*;

class StageCompletionTest {

    private final StageCompletion completion = new StageCompletion();
    private final PlanModelEvaluator evaluator = new PlanModelEvaluator(new JuelCriterionEvaluator());

    private CaseDefinition stageWithTwoChildren(boolean secondRequired) {
        return definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("required", PlanItemType.HUMAN_TASK, "stage", false, true, false,
                        List.of(), List.of(), 20),
                def("optional", PlanItemType.HUMAN_TASK, "stage", false, secondRequired, false,
                        List.of(), List.of(), 30));
    }

    @Test
    void stageWithAnUnfinishedRequiredChildCannotComplete() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-stage"),
                item("pi-opt", "optional", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage")),
                Map.of());

        PlanItem stage = snapshot.planItems().get(0);

        assertThat(completion.canComplete(snapshot, stage)).isFalse();
        assertThat(completion.blockingItems(snapshot, stage))
                .extracting(PlanItem::id).containsExactly("pi-req");
    }

    @Test
    void stageCompletesWhenRequiredChildrenAreDoneEvenIfOptionalOnesAreNot() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-opt", "optional", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage")),
                Map.of());

        assertThat(completion.canComplete(snapshot, snapshot.planItems().get(0))).isTrue();
    }

    @Test
    void evaluatorCompletesASatisfiedStage() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-opt", "optional", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage")),
                Map.of());

        assertThat(evaluator.evaluate(snapshot))
                .anySatisfy(t -> {
                    assertThat(t.planItemId()).isEqualTo("pi-stage");
                    assertThat(t.to()).isEqualTo(PlanItemState.COMPLETED);
                });
    }

    @Test
    void caseCannotCloseWhileARequiredItemIsOpen() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-stage")),
                Map.of());

        assertThat(completion.caseCanClose(snapshot)).isFalse();
        assertThat(completion.caseBlockers(snapshot)).extracting(PlanItem::name)
                .containsExactly("required");
    }

    @Test
    void childOfAnEnabledStageIsNotContainedAndStaysAvailable() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, true, false, false, List.of(), List.of(), 10),
                def("child", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 20));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ENABLED),
                item("pi-child", "child", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage")),
                Map.of());

        PlanItem child = snapshot.planItems().stream().filter(i -> i.id().equals("pi-child")).findFirst().get();
        assertThat(completion.isContained(snapshot, child)).isFalse();
        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }

    @Test
    void instantiatorWiresChildInstancesToTheirParentStageInstance() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("child", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 20));

        List<PlanItem> items = new PlanModelInstantiator().initialItems("eng-a:1", def);

        PlanItem stage = items.stream().filter(i -> "stage".equals(i.name())).findFirst().get();
        PlanItem child = items.stream().filter(i -> "child".equals(i.name())).findFirst().get();

        assertThat(child.parentStageId()).isEqualTo(stage.id());
    }

    @Test
    void topLevelItemWithNoParentStageIsAlwaysContained() {
        CaseDefinition def = definition(
                def("solo", PlanItemType.HUMAN_TASK, null, false, false, false, List.of(), List.of(), 10));
        var snapshot = snapshot(def, List.of(
                item("pi-solo", "solo", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)),
                Map.of());

        PlanItem solo = snapshot.planItems().get(0);
        assertThat(completion.isContained(snapshot, solo)).isTrue();
    }
}
