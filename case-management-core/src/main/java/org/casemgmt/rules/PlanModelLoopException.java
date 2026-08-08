package org.casemgmt.rules;

public class PlanModelLoopException extends RuntimeException {
    public PlanModelLoopException(String caseId, int maxIterations) {
        super("Plan model for case " + caseId + " did not reach a fixpoint within "
                + maxIterations + " iterations — check for mutually-triggering criteria");
    }
}
