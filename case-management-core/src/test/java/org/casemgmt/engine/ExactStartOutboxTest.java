package org.casemgmt.engine;

import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExactStartOutboxTest {

    @Test
    void exactIdentitySurvivesOutboxSerialization() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        OutboxEngineGateway outbox = new OutboxEngineGateway(commands, ignored -> { });

        outbox.startProcess(new StartProcessRequest(
                "case-1", null, "orders:1:exact", "orders", "tenant-a",
                Map.of("amount", 10), "root-link-1"));

        ArgumentCaptor<EngineCommand> command = ArgumentCaptor.forClass(EngineCommand.class);
        verify(commands).enqueue(command.capture());
        assertThat(command.getValue().payload())
                .containsEntry("selectionType", "ID")
                .containsEntry("processDefinitionId", "orders:1:exact")
                .containsEntry("processDefinitionKey", "orders")
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("correlationId", "root-link-1");
    }

    @Test
    void dispatcherReconstructsTheExactIdentityInsteadOfSelectingByKey() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway delegate = mock(EngineGateway.class);
        EngineCommand command = new EngineCommand(
                "command-1", "case-1", EngineCommand.Type.START_PROCESS,
                Map.of("selectionType", "ID",
                        "planItemId", "",
                        "processDefinitionId", "orders:1:exact",
                        "processDefinitionKey", "orders",
                        "tenantId", "tenant-a",
                        "variables", Map.of("amount", 10),
                        "correlationId", "root-link-1"),
                "CLAIMED", 0, OffsetDateTime.now(), null);
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(delegate.startProcess(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EngineProcessRef("engine-instance-1", "orders", "case-1"));

        new EngineCommandDispatcher(commands, delegate, (key, sync, engineId) -> { }).drainOnce();

        ArgumentCaptor<StartProcessRequest> request = ArgumentCaptor.forClass(StartProcessRequest.class);
        verify(delegate).startProcess(request.capture());
        assertThat(request.getValue().processDefinitionId()).isEqualTo("orders:1:exact");
        assertThat(request.getValue().processDefinitionKey()).isEqualTo("orders");
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
        verify(commands).markDone(anyString());
    }

    @Test
    void dispatcherKeepsPreUpgradeKeyOnlyCommandsCompatible() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway delegate = mock(EngineGateway.class);
        EngineCommand command = new EngineCommand(
                "legacy-command", "case-1", EngineCommand.Type.START_PROCESS,
                Map.of("planItemId", "pi-1",
                        "processDefinitionKey", "legacy-process",
                        "variables", Map.of(),
                        "correlationId", "linked-1"),
                "CLAIMED", 0, OffsetDateTime.now(), null);
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(delegate.startProcessByKey(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EngineProcessRef("engine-instance-1", "legacy-process", "case-1"));

        new EngineCommandDispatcher(commands, delegate, (key, sync, engineId) -> { }).drainOnce();

        ArgumentCaptor<StartProcessByKeyRequest> request =
                ArgumentCaptor.forClass(StartProcessByKeyRequest.class);
        verify(delegate).startProcessByKey(request.capture());
        assertThat(request.getValue().processDefinitionKey()).isEqualTo("legacy-process");
    }

    @Test
    void dispatcherRetriesExactStartWithoutARealProcessInstanceId() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway delegate = mock(EngineGateway.class);
        EngineCommandDispatcher.SyncReporter reporter =
                mock(EngineCommandDispatcher.SyncReporter.class);
        EngineCommand command = exactCommand("exact-without-id");
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(delegate.startProcess(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EngineProcessRef(null, "orders", "case-1"));

        new EngineCommandDispatcher(commands, delegate, reporter).drainOnce();

        verify(reporter, never()).confirmProcessStarted(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(commands, never()).markDone(command.id());
        verify(commands).markRetry(eq(command.id()), contains("process-instance id"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatcherRetriesPreUpgradeKeyCommandWithABlankProcessInstanceId() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway delegate = mock(EngineGateway.class);
        EngineCommandDispatcher.SyncReporter reporter =
                mock(EngineCommandDispatcher.SyncReporter.class);
        EngineCommand command = new EngineCommand(
                "legacy-without-id", "case-1", EngineCommand.Type.START_PROCESS,
                Map.of("planItemId", "pi-1",
                        "processDefinitionKey", "legacy-process",
                        "variables", Map.of(),
                        "correlationId", "linked-1"),
                "CLAIMED", 0, OffsetDateTime.now(), null);
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(delegate.startProcessByKey(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EngineProcessRef("   ", "legacy-process", "case-1"));

        new EngineCommandDispatcher(commands, delegate, reporter).drainOnce();

        verify(reporter, never()).confirmProcessStarted(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(commands, never()).markDone(command.id());
        verify(commands).markRetry(eq(command.id()), contains("process-instance id"),
                org.mockito.ArgumentMatchers.any());
    }

    private static EngineCommand exactCommand(String id) {
        return new EngineCommand(
                id, "case-1", EngineCommand.Type.START_PROCESS,
                Map.of("selectionType", "ID",
                        "planItemId", "",
                        "processDefinitionId", "orders:1:exact",
                        "processDefinitionKey", "orders",
                        "tenantId", "tenant-a",
                        "variables", Map.of(),
                        "correlationId", "root-link-1"),
                "CLAIMED", 0, OffsetDateTime.now(), null);
    }
}
