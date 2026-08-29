package org.casemgmt.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts one exact, previously approved engine process definition.
 *
 * <p>{@code processDefinitionKey} is descriptive metadata only. Engine gateways must select
 * {@code processDefinitionId}; they must never fall back to the key.
 *
 * <p>{@code correlationId} is the caller-owned local row id used to reconcile an asynchronous
 * outbox acknowledgement. It is not an engine process-instance id.
 */
public record StartProcessRequest(
        String caseId,
        String planItemId,
        String processDefinitionId,
        String processDefinitionKey,
        String tenantId,
        Map<String, Object> variables,
        String correlationId) {

    public StartProcessRequest {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new IllegalArgumentException("processDefinitionId must not be blank");
        }
        variables = variables == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public StartProcessRequest(String caseId, String planItemId, String processDefinitionId,
                               String processDefinitionKey, String tenantId,
                               Map<String, Object> variables) {
        this(caseId, planItemId, processDefinitionId, processDefinitionKey, tenantId, variables,
                null);
    }
}
