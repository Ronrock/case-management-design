package org.casemgmt.engine;

public record EngineProcessRef(String processInstanceId, String processDefinitionId,
                               String processDefinitionKey, String businessKey) {

    public EngineProcessRef {
        if (processDefinitionId != null && processDefinitionId.isBlank()) {
            throw new IllegalArgumentException("processDefinitionId must not be blank");
        }
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            throw new IllegalArgumentException("processDefinitionKey must not be blank");
        }
    }

    /** Source-compatible reference whose exact deployed definition identity is unavailable. */
    public EngineProcessRef(String processInstanceId, String processDefinitionKey,
                            String businessKey) {
        this(processInstanceId, null, processDefinitionKey, businessKey);
    }

    public EngineProcessRef(String processInstanceId, String processDefinitionKey) {
        this(processInstanceId, null, processDefinitionKey, null);
    }
}
