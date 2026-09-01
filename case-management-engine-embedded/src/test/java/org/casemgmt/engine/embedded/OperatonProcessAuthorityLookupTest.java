package org.casemgmt.engine.embedded;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatonProcessAuthorityLookupTest {

    @Test
    void readsRuntimeCorrelationWithoutRequiringAFlushedProcessQuery() {
        RuntimeService runtime = mock(RuntimeService.class);
        when(runtime.getVariable("process-42",
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE))
                .thenReturn("correlation-7");
        var lookup = new OperatonProcessAuthorityLookup(
                runtime, mock(RepositoryService.class));

        assertThat(lookup.lifecycleCorrelationId("process-42"))
                .contains("correlation-7");
    }

    @Test
    void exposesOnlyEngineNeutralExactRuntimeAndDefinitionIdentity() {
        RuntimeService runtime = mock(RuntimeService.class);
        RepositoryService repository = mock(RepositoryService.class);
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(runtime.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId("process-42")).thenReturn(query);
        when(query.singleResult()).thenReturn(instance);
        when(instance.getProcessDefinitionId()).thenReturn("claim:7:deployment-a");
        when(repository.getProcessDefinition("claim:7:deployment-a"))
                .thenReturn(definition);
        when(definition.getId()).thenReturn("claim:7:deployment-a");
        when(definition.getKey()).thenReturn("claim");
        when(definition.getTenantId()).thenReturn("tenant-a");
        var lookup = new OperatonProcessAuthorityLookup(runtime, repository);

        assertThat(lookup.processDefinitionId("process-42"))
                .contains("claim:7:deployment-a");
        assertThat(lookup.processDefinition("claim:7:deployment-a"))
                .contains(new org.casemgmt.observation.EngineProcessAuthorityLookup
                        .ProcessDefinition("claim:7:deployment-a", "claim", "tenant-a"));
    }
}
