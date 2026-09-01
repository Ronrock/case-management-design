package org.casemgmt.observation;

import org.casemgmt.domain.CaseInstance;

/** Validates immutable BPMN and engine authority before lifecycle effects. */
@FunctionalInterface
public interface EngineObservationAuthorityValidator {
    void validate(EngineObservation observation, CaseInstance caseInstance);
}
