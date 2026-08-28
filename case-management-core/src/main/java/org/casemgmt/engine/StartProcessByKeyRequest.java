package org.casemgmt.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit compatibility request for PLAN_MODEL process tasks and ad-hoc linked processes.
 *
 * <p>This type prevents a descriptive key from being mistaken for an exact engine identity.
 * BPMN root orchestration must use {@link StartProcessRequest}.
 */
public record StartProcessByKeyRequest(
        String caseId,
        String planItemId,
        String processDefinitionKey,
        Map<String, Object> variables,
        String correlationId) {

    public StartProcessByKeyRequest {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            throw new IllegalArgumentException("processDefinitionKey must not be blank");
        }
        variables = variables == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public StartProcessByKeyRequest(String caseId, String planItemId, String processDefinitionKey,
                                    Map<String, Object> variables) {
        this(caseId, planItemId, processDefinitionKey, variables, null);
    }
}
