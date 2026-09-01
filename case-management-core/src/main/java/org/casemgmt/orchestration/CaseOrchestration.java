package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseInstance;
/** Mode-dependent orchestration decisions. Engine-specific types must not cross this boundary. */
public interface CaseOrchestration {

    OrchestrationMode mode();

    default void onCaseCreated(CaseInstance caseInstance, CaseDefinition definition) {
    }

    default void onCaseCancelled(CaseInstance caseInstance, String reason) {
    }

}
