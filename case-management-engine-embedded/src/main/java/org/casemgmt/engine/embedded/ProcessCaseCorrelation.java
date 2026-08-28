package org.casemgmt.engine.embedded;

import org.casemgmt.orchestration.OrchestrationMode;

import java.util.Optional;

@FunctionalInterface
public interface ProcessCaseCorrelation {

    record Authority(String caseId, OrchestrationMode orchestrationMode) { }

    String caseId(String processInstanceId);

    /** Exact-definition-aware form used by synchronous engine callbacks. */
    default String caseId(String processInstanceId, String processDefinitionId) {
        return caseId(processInstanceId);
    }

    /** Explicit persisted authority including the lifecycle path that may consume callbacks. */
    default Optional<Authority> authority(
            String processInstanceId, String processDefinitionId) {
        return Optional.ofNullable(caseId(processInstanceId, processDefinitionId))
                .map(caseId -> new Authority(caseId, OrchestrationMode.BPMN));
    }
}
