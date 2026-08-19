package org.casemgmt.engine;

public record EngineProcessRef(String processInstanceId, String processDefinitionKey, String businessKey) {

    public EngineProcessRef(String processInstanceId, String processDefinitionKey) {
        this(processInstanceId, processDefinitionKey, null);
    }
}
