package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineException;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.engine.StartProcessRequest;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedEngineGatewayResponseTest {

    @Test
    void legacyKeyStartReturnsTheRuntimeExactDefinitionIdentity() {
        RuntimeService runtime = mock(RuntimeService.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(runtime.startProcessInstanceByKey(eq("orders"), eq("case-1"), anyMap()))
                .thenReturn(instance);
        when(instance.getId()).thenReturn("process-42");
        when(instance.getProcessDefinitionId()).thenReturn("orders:9:exact");
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, mock(RepositoryService.class));

        var ref = gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders", Map.of(), null));

        assertThat(ref.processDefinitionId()).isEqualTo("orders:9:exact");
        assertThat(ref.processDefinitionKey()).isEqualTo("orders");
    }

    @Test
    void exactStartRejectsAMissingProcessInstanceId() {
        RuntimeService runtime = mock(RuntimeService.class);
        RepositoryService repository = mock(RepositoryService.class);
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(repository.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId("definition-1")).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        when(definition.getKey()).thenReturn("orders");
        when(runtime.startProcessInstanceById(eq("definition-1"), eq("case-1"), anyMap()))
                .thenReturn(instance);
        when(instance.getId()).thenReturn(null);
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, repository);

        assertThatThrownBy(() -> gateway.startProcess(new StartProcessRequest(
                "case-1", null, "definition-1", "orders", null, Map.of(), null)))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("process-instance id");
    }

    @Test
    void legacyStartRejectsABlankProcessInstanceId() {
        RuntimeService runtime = mock(RuntimeService.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(runtime.startProcessInstanceByKey(eq("orders"), eq("case-1"), anyMap()))
                .thenReturn(instance);
        when(instance.getId()).thenReturn("   ");
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, mock(RepositoryService.class));

        assertThatThrownBy(() -> gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders", Map.of(), null)))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("process-instance id");
    }
}
