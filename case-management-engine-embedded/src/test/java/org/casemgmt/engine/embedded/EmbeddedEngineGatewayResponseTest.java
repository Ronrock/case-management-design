package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineException;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.engine.StartProcessRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class EmbeddedEngineGatewayResponseTest {

    @Test
    void bodylessCancellationUsesTheVersionedNullIntentEnvelope() {
        RuntimeService runtime = mock(RuntimeService.class);
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, mock(RepositoryService.class));

        gateway.cancelProcess("process-42", null);

        verify(runtime).deleteProcessInstance("process-42",
                "__casemgmt_cancel_v1__:N");
    }

    @ParameterizedTest
    @MethodSource("cancellationReasons")
    void everyNullableUserReasonHasAnUnambiguousVersionedEngineEnvelope(
            String reason, String expectedEnvelope) {
        RuntimeService runtime = mock(RuntimeService.class);
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, mock(RepositoryService.class));

        gateway.cancelProcess("process-42", reason);

        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        verify(runtime).deleteProcessInstance(eq("process-42"), encoded.capture());
        assertThat(encoded.getValue()).isEqualTo(expectedEnvelope);
        if (reason != null) {
            assertThat(encoded.getValue()).isNotEqualTo(reason);
        }
    }

    private static Stream<Arguments> cancellationReasons() {
        return Stream.of(
                Arguments.of(null, "__casemgmt_cancel_v1__:N"),
                Arguments.of("", "__casemgmt_cancel_v1__:S"),
                Arguments.of("customer withdrew",
                        "__casemgmt_cancel_v1__:SY3VzdG9tZXIgd2l0aGRyZXc"),
                Arguments.of("case-management:cancelled-without-reason",
                        "__casemgmt_cancel_v1__:SY2FzZS1tYW5hZ2VtZW50OmNhbmNlbGxlZC13aXRob3V0LXJlYXNvbg"),
                Arguments.of("__casemgmt_cancel_v1__:",
                        "__casemgmt_cancel_v1__:SX19jYXNlbWdtdF9jYW5jZWxfdjFfXzo"),
                Arguments.of("reden: klant koos café ☕",
                        "__casemgmt_cancel_v1__:ScmVkZW46IGtsYW50IGtvb3MgY2Fmw6kg4piV"));
    }

    @Test
    void keyStartPinsTheTenantScopedExactDefinitionIdentity() {
        RuntimeService runtime = mock(RuntimeService.class);
        RepositoryService repository = mock(RepositoryService.class);
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(repository.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionKey("orders")).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        when(query.tenantIdIn("tenant-a")).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        when(definition.getId()).thenReturn("orders:9:exact");
        when(definition.getKey()).thenReturn("orders");
        when(runtime.startProcessInstanceById(eq("orders:9:exact"), eq("case-1"), anyMap()))
                .thenReturn(instance);
        when(instance.getId()).thenReturn("process-42");
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, repository);

        var ref = gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders", Map.of(), null, "tenant-a"));

        assertThat(ref.processDefinitionId()).isEqualTo("orders:9:exact");
        assertThat(ref.processDefinitionKey()).isEqualTo("orders");
    }

    @Test
    void startCarriesTheCallerOwnedLifecycleCorrelationAndCannotBeSpoofedByVariables() {
        RuntimeService runtime = mock(RuntimeService.class);
        RepositoryService repository = mock(RepositoryService.class);
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(repository.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionKey("orders")).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        when(query.withoutTenantId()).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        when(definition.getId()).thenReturn("orders:9:exact");
        when(definition.getKey()).thenReturn("orders");
        when(runtime.startProcessInstanceById(eq("orders:9:exact"), eq("case-1"), anyMap()))
                .thenReturn(instance);
        when(instance.getId()).thenReturn("process-42");
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, repository);

        gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders",
                Map.of(EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE, "attacker"),
                "correlation-7"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(runtime).startProcessInstanceById(eq("orders:9:exact"), eq("case-1"),
                variables.capture());
        assertThat(variables.getValue()).containsEntry(
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE, "correlation-7");
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
    void keyStartRejectsABlankProcessInstanceId() {
        RuntimeService runtime = mock(RuntimeService.class);
        RepositoryService repository = mock(RepositoryService.class);
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(repository.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionKey("orders")).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        when(query.withoutTenantId()).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        when(definition.getId()).thenReturn("orders:9:exact");
        when(definition.getKey()).thenReturn("orders");
        when(runtime.startProcessInstanceById(eq("orders:9:exact"), eq("case-1"), anyMap()))
                .thenReturn(instance);
        when(instance.getId()).thenReturn("   ");
        EmbeddedEngineGateway gateway = new EmbeddedEngineGateway(
                mock(TaskService.class), runtime, repository);

        assertThatThrownBy(() -> gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders", Map.of(), null)))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("process-instance id");
    }
}
