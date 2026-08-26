package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.PlanModelEvaluator;
import org.casemgmt.rules.PlanModelInstantiator;
import org.casemgmt.rules.Transition;

import java.util.List;

/** Preserves the legacy plan evaluator behind the orchestration SPI. */
public final class PlanModelOrchestration implements CaseOrchestration {

    private final PlanModelEvaluator evaluator;
    private final PlanModelInstantiator instantiator;

    public PlanModelOrchestration(PlanModelEvaluator evaluator, PlanModelInstantiator instantiator) {
        this.evaluator = evaluator;
        this.instantiator = instantiator;
    }

    @Override
    public OrchestrationMode mode() {
        return OrchestrationMode.PLAN_MODEL;
    }

    @Override
    public List<PlanItem> initialItems(String caseId, CaseDefinition definition) {
        return instantiator.initialItems(caseId, definition);
    }

    @Override
    public List<Transition> evaluate(CaseSnapshot snapshot) {
        return evaluator.evaluate(snapshot);
    }

    @Override
    public List<PlanItemDefinition> repeatable(CaseSnapshot snapshot) {
        return evaluator.repeatable(snapshot);
    }

    @Override
    public PlanItem repeat(PlanItem previous, PlanItemDefinition definition) {
        return instantiator.repeat(previous, definition);
    }
}
