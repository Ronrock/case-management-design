package org.casemgmt.rules;

import java.util.List;

public interface CriterionEvaluator {

    boolean matches(String expression, EvaluationContext context);

    default boolean allMatch(List<String> expressions, EvaluationContext context) {
        if (expressions == null || expressions.isEmpty()) {
            return true;   // no entry criteria means the item is not gated
        }
        return expressions.stream().allMatch(e -> matches(e, context));
    }
}
