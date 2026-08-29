package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;

import java.util.List;

/** Mode-dependent orchestration decisions. Engine-specific types must not cross this boundary. */
public interface CaseOrchestration {

    OrchestrationMode mode();

    List<PlanItem> initialItems(String caseId, CaseDefinition definition);

    default void onCaseCreated(CaseInstance caseInstance, CaseDefinition definition) {
    }

    default void onCaseCancelled(CaseInstance caseInstance, String reason) {
    }

    List<Transition> evaluate(CaseSnapshot snapshot);

    List<PlanItemDefinition> repeatable(CaseSnapshot snapshot);

    PlanItem repeat(PlanItem previous, PlanItemDefinition definition);

    default boolean allowsExplicitClose() {
        return false;
    }
}
