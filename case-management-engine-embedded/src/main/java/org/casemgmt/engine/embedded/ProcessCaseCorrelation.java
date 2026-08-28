package org.casemgmt.engine.embedded;

@FunctionalInterface
public interface ProcessCaseCorrelation {
    String caseId(String processInstanceId);

    /** Exact-definition-aware form used by synchronous engine callbacks. */
    default String caseId(String processInstanceId, String processDefinitionId) {
        return caseId(processInstanceId);
    }
}
