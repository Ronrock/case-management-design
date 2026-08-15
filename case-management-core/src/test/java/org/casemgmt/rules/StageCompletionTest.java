package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // --- Task 9 review, Critical 1: a stage cannot complete in the same round it admits a
    // fresh child beneath it, and a completing stage terminates (rather than silently drops
    // or races) any AVAILABLE/ENABLED children it leaves behind. ---

    @Test
    void stageWithAnActiveChildDoesNotComplete() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("required", PlanItemType.HUMAN_TASK, "stage", false, true, false,
                        List.of(), List.of(), 20),
                def("optionalActive", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-active", "optionalActive", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-stage")),
                Map.of());

        PlanItem stage = snapshot.planItems().get(0);
        assertThat(completion.canComplete(snapshot, stage)).isFalse();
        assertThat(completion.blockingItems(snapshot, stage))
                .extracting(PlanItem::id).containsExactly("pi-active");
        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }

    @Test
    void completingStageTerminatesLeftoverAvailableAndEnabledChildrenAsRealTransitions() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("required", PlanItemType.HUMAN_TASK, "stage", false, true, false,
                        List.of(), List.of(), 20),
                def("leftoverAvailable", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 30),
                def("leftoverEnabled", PlanItemType.HUMAN_TASK, "stage", true, false, false,
                        List.of(), List.of(), 40));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-avail", "leftoverAvailable", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage"),
                item("pi-enabled", "leftoverEnabled", PlanItemType.HUMAN_TASK, PlanItemState.ENABLED, "pi-stage")),
                Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).hasSize(3);
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.COMPLETED);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-avail");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-enabled");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
    }

    @Test
    void reviewersReproNoLongerLeavesACompletedStageWithALiveChild() {
        // Exact repro from the Task 9 review: stage with one optional child COMPLETED and
        // another optional child still AVAILABLE used to produce [stage ACTIVE->COMPLETED,
        // opt-b AVAILABLE->ACTIVE] from a single evaluate() call — a completed stage with a
        // live active child that nothing ever revisited.
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("opt-a", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 20),
                def("opt-b", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-opt-a", "opt-a", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-opt-b", "opt-b", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage")),
                Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).noneMatch(t -> "pi-opt-b".equals(t.planItemId()) && t.to() == PlanItemState.ACTIVE);
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.COMPLETED);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-opt-b");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
    }

    @Test
    void stageThatJustEnteredLetsItsOptionalChildMaterialiseBeforeAutocomplete() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false,
                        List.of("${vars.ready == true}"), List.of(), 10),
                def("child", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 20));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.AVAILABLE),
                item("pi-child", "child", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage")),
                Map.of("ready", true));

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.ACTIVE);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-child");
            assertThat(t.to()).isEqualTo(PlanItemState.ACTIVE);
        });
        assertThat(transitions).noneMatch(t ->
                "pi-stage".equals(t.planItemId()) && t.to() == PlanItemState.COMPLETED);
        assertThat(transitions).noneMatch(t ->
                "pi-child".equals(t.planItemId()) && t.to() == PlanItemState.TERMINATED);
    }

    // --- Task 9 review, Critical 2: a malformed parentStageKey must fail loudly. ---

    @Test
    void instantiatorRejectsAMalformedParentStageKey() {
        CaseDefinition def = definition(
                def("child", PlanItemType.HUMAN_TASK, "missing-stage", false, false, false,
                        List.of(), List.of(), 10));

        assertThatThrownBy(() -> new PlanModelInstantiator().initialItems("eng-a:1", def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child")
                .hasMessageContaining("missing-stage");
    }

    // --- Task 9 re-review, Important: the Critical-1 restructuring accidentally let
    // autocomplete pre-empt a stage's own exit criterion. Exit must win. ---

    @Test
    void exitCriterionOnAStageWinsOverAutocompleteInTheSameRound() {
        // Reviewer's exact repro: a stage with a satisfied exit criterion AND an
        // already-ended child came out COMPLETED (autocomplete) instead of TERMINATED
        // (exit criterion), silently discarding the author-stated exit signal.
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false,
                        List.of(), List.of("${vars.abort == true}"), 10),
                def("child", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 20));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-child", "child", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage")),
                Map.of("abort", true));

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).noneMatch(t -> "pi-stage".equals(t.planItemId()) && t.to() == PlanItemState.COMPLETED);
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
    }

    @Test
    void terminatingStageCascadeTerminatesAllRemainingChildrenIncludingActiveOnes() {
        // Design decision made explicit: unlike autocomplete (which can never see an ACTIVE
        // child, by construction of blockingItems), an exit criterion is unconditional and
        // can fire while a child is ACTIVE — so termination cascades to every remaining
        // child, active or not, rather than leaving one behind.
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false,
                        List.of(), List.of("${vars.abort == true}"), 10),
                def("activeChild", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 20),
                def("availableChild", PlanItemType.HUMAN_TASK, "stage", false, false, false,
                        List.of(), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-active", "activeChild", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-stage"),
                item("pi-avail", "availableChild", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage")),
                Map.of("abort", true));

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).hasSize(3);
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-active");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-avail");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
        });
    }

    // --- Task 9 second re-review, Important: a one-level cascade orphaned a grandchild.
    // Both termination paths must walk the whole subtree, not just direct children. ---

    @Test
    void exitTerminationCascadesThroughTwoLevelsToAGrandchild() {
        // Reviewer's exact repro: stage --(exit criterion satisfied)--> substage (ACTIVE)
        // --> grandchild (ACTIVE). A one-level cascade correctly terminated the substage but
        // left the grandchild ACTIVE beneath a TERMINATED parent beneath a TERMINATED
        // grandparent — precisely the orphan shape Critical 1 closed for autocomplete,
        // reopened one level down.
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false,
                        List.of(), List.of("${vars.abort == true}"), 10),
                def("substage", PlanItemType.STAGE, "stage", false, false, false,
                        List.of(), List.of(), 20),
                def("grandchild", PlanItemType.HUMAN_TASK, "substage", false, false, false,
                        List.of(), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-substage", "substage", PlanItemType.STAGE, PlanItemState.ACTIVE, "pi-stage"),
                item("pi-grandchild", "grandchild", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-substage")),
                Map.of("abort", true));

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).hasSize(3);
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
            assertThat(t.reason()).isEqualTo("exit criterion met");
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-substage");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
            assertThat(t.reason()).isEqualTo("parent stage terminated");
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-grandchild");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
            assertThat(t.reason()).isEqualTo("parent stage terminated");
        });
    }

    @Test
    void autocompleteCascadesThroughTwoLevelsToTerminateUnstartedDescendants() {
        // Equivalent three-level case for the autocomplete path: an unstarted substage swept
        // up by a completing stage can itself have an unstarted grandchild that must also be
        // terminated, not left behind.
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("required", PlanItemType.HUMAN_TASK, "stage", false, true, false,
                        List.of(), List.of(), 20),
                def("substage", PlanItemType.STAGE, "stage", false, false, false,
                        List.of(), List.of(), 30),
                def("grandchild", PlanItemType.HUMAN_TASK, "substage", false, false, false,
                        List.of(), List.of(), 40));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-substage", "substage", PlanItemType.STAGE, PlanItemState.AVAILABLE, "pi-stage"),
                item("pi-grandchild", "grandchild", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-substage")),
                Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).hasSize(3);
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-stage");
            assertThat(t.to()).isEqualTo(PlanItemState.COMPLETED);
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-substage");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
            assertThat(t.reason()).isEqualTo("parent stage completed");
        });
        assertThat(transitions).anySatisfy(t -> {
            assertThat(t.planItemId()).isEqualTo("pi-grandchild");
            assertThat(t.to()).isEqualTo(PlanItemState.TERMINATED);
            assertThat(t.reason()).isEqualTo("parent stage completed");
        });
    }

    @Test
    void cascadeLeavesAnAlreadyEndedDescendantAloneRatherThanReTerminatingIt() {
        // Depth-3 chain where the deepest item is already COMPLETED: the cascade must not
        // re-touch it, even though the walk passes through its (also cascading) parent.
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE, null, false, false, false,
                        List.of(), List.of("${vars.abort == true}"), 10),
                def("substage", PlanItemType.STAGE, "stage", false, false, false,
                        List.of(), List.of(), 20),
                def("grandchild", PlanItemType.HUMAN_TASK, "substage", false, false, false,
                        List.of(), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-substage", "substage", PlanItemType.STAGE, PlanItemState.ACTIVE, "pi-stage"),
                item("pi-grandchild", "grandchild", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-substage")),
                Map.of("abort", true));

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).hasSize(2);
        assertThat(transitions).extracting(Transition::planItemId)
                .containsExactlyInAnyOrder("pi-stage", "pi-substage");
        assertThat(transitions).noneMatch(t -> "pi-grandchild".equals(t.planItemId()));
    }

    @Test
    void descendantWalkFailsLoudlyOnACycleInParentStageId() {
        // A malformed model could in principle link parentStageId in a cycle. Rather than
        // recurse forever or overflow the stack, the walk must fail loudly.
        CaseDefinition def = definition(
                def("a", PlanItemType.STAGE),
                def("b", PlanItemType.STAGE));
        PlanItem a = item("pi-a", "a", PlanItemType.STAGE, PlanItemState.ACTIVE, "pi-b");
        PlanItem b = item("pi-b", "b", PlanItemType.STAGE, PlanItemState.ACTIVE, "pi-a");
        var snapshot = snapshot(def, List.of(a, b), Map.of());

        assertThatThrownBy(() -> completion.childrenToCascadeTerminate(snapshot, a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cycle");
    }
}
