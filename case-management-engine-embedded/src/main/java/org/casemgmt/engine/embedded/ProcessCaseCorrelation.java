package org.casemgmt.engine.embedded;

@FunctionalInterface
public interface ProcessCaseCorrelation {
    String caseId(String processInstanceId);
}
