package org.casemgmt.engine.embedded;

import org.casemgmt.observation.EngineProcessAuthorityLookup;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;

import java.util.Objects;
import java.util.Optional;

/** Read-only Operaton implementation of the core process-authority lookup port. */
public final class OperatonProcessAuthorityLookup implements EngineProcessAuthorityLookup {

    private final RuntimeService runtime;
    private final RepositoryService repository;

    public OperatonProcessAuthorityLookup(RuntimeService runtime, RepositoryService repository) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<String> processDefinitionId(String processInstanceId) {
        var instance = runtime.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return Optional.ofNullable(instance == null ? null : instance.getProcessDefinitionId());
    }

    @Override
    public Optional<String> lifecycleCorrelationId(String processInstanceId) {
        Object marker = runtime.getVariable(processInstanceId,
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE);
        return marker instanceof String text ? Optional.of(text) : Optional.empty();
    }

    @Override
    public Optional<ProcessDefinition> processDefinition(String processDefinitionId) {
        var definition = repository.getProcessDefinition(processDefinitionId);
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(new ProcessDefinition(definition.getId(), definition.getKey(),
                definition.getTenantId()));
    }
}
