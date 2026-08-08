package org.casemgmt.rules;

import java.util.Map;

/**
 * Everything a criterion may read. Nested maps only — no domain objects, because
 * exposing objects would require a BeanELResolver and with it method access.
 */
public record EvaluationContext(
        Map<String, Object> caseAttributes,
        Map<String, Object> variables,
        Map<String, Map<String, Object>> items) {}
