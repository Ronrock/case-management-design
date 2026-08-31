package org.casemgmt.starter;

import org.casemgmt.engine.CommandDispatchOutcome;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandPolicy;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.casemgmt.engine.remote.RemoteEngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.observation.CommandConfirmationLifecycleReporter;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteEngineDefinitionConfirmationTest {

    @Test
    void remoteDispatcherWiringPersistsEvidenceWithoutWritingAProjectionCallback() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        RemoteEngineGateway gateway = mock(RemoteEngineGateway.class);
        ProductionEngineCommandStore.StoredCommand command =
                mock(ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        when(command.state()).thenReturn(state);
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext(
                "tenant-1", "operation-1", "command-1", EngineCommand.Type.START_PROCESS,
                "orders:9:exact"));
        when(command.operationId()).thenReturn("operation-1");
        when(command.version()).thenReturn(1L);
        var lease = new ProductionEngineCommandStore.LeasedCommand(command,
                "lease-1", "worker", OffsetDateTime.now().plusMinutes(5));
        var evidence = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-1", "operation-1", "command-1", EngineCommand.Type.START_PROCESS,
                "orders:9:exact", "process-42",
                CommandDispatchOutcome.RemoteState.PROCESS_STARTED,
                CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE,
                "http:200:command-1");
        CommandDispatchOutcome outcome = CommandDispatchOutcome.http(200,
                CommandDispatchOutcome.Acceptance.ACCEPTED, null, evidence);
        when(commands.claimDue(anyString(), eq(1), any(), eq(Duration.ofMinutes(5))))
                .thenReturn(List.of(lease), List.of());
        when(commands.commitLeaseOutcome(
                "tenant-1", "operation-1", "lease-1", 1, outcome))
                .thenReturn(command);
        when(gateway.dispatch(command)).thenReturn(outcome);
        EventPublisher events = mock(EventPublisher.class);
        CommandConfirmationLifecycleReporter lifecycle =
                mock(CommandConfirmationLifecycleReporter.class);
        var dispatcher = new RemoteEngineAutoConfiguration().engineCommandDispatcher(
                commands, gateway, events, lifecycle);

        dispatcher.drainOnce();

        verify(commands).commitLeaseOutcome(
                "tenant-1", "operation-1", "lease-1", 1, outcome);
        verify(lifecycle).confirmed(any());
        verify(gateway, never()).startProcess(any());
    }
}
