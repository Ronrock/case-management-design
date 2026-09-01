package org.casemgmt.observation;

import org.casemgmt.orchestration.OrchestrationMode;

import java.util.Optional;

/** Read-only authority used by engine adapters before emitting an observation. */
@FunctionalInterface
public interface ProcessCaseAuthority {

    record Authority(String caseId, OrchestrationMode orchestrationMode) { }

    String caseId(String processInstanceId);

    default String caseId(String processInstanceId, String processDefinitionId) {
        return caseId(processInstanceId);
    }

    default Optional<Authority> authority(String processInstanceId, String processDefinitionId) {
        return Optional.ofNullable(caseId(processInstanceId, processDefinitionId))
                .map(caseId -> new Authority(caseId, OrchestrationMode.BPMN));
    }
}
