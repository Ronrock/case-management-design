package org.casemgmt.rules;

public class CriterionEvaluationException extends RuntimeException {
    public CriterionEvaluationException(String expression, String problem, Throwable cause) {
        super("Criterion [" + expression + "] " + problem, cause);
    }
}
