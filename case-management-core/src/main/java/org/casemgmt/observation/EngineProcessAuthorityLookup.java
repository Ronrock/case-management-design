package org.casemgmt.observation;

import java.util.Optional;

/** Engine-neutral read port for the exact runtime and deployment facts used by authority checks. */
public interface EngineProcessAuthorityLookup {

    record ProcessDefinition(String id, String key, String tenantId) { }

    Optional<String> processDefinitionId(String processInstanceId);

    Optional<String> lifecycleCorrelationId(String processInstanceId);

    Optional<ProcessDefinition> processDefinition(String processDefinitionId);
}
