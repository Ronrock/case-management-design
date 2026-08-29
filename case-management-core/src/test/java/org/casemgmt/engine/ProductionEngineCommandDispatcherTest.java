package org.casemgmt.engine;

import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.*;

class ProductionEngineCommandDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Test
    void commitsTheTypedTransportOutcomeAgainstTheOwnedLease() {
        EngineCommandRepository repository = mock(EngineCommandRepository.class);
        EngineCommandTransport transport = mock(EngineCommandTransport.class);
        ProductionEngineCommandStore.StoredCommand command = command(7);
        var lease = new ProductionEngineCommandStore.LeasedCommand(command,
                "lease-1", "worker-1", OffsetDateTime.ofInstant(
                NOW.plusSeconds(30), ZoneOffset.UTC));
        CommandDispatchOutcome outcome = CommandDispatchOutcome.transportFailure(
                CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE);
        when(repository.claimDue("worker-1", 1, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30))).thenReturn(List.of(lease), List.of());
        when(transport.dispatch(command)).thenReturn(outcome);

        new EngineCommandDispatcher(repository, transport, "worker-1",
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).drainOnce();

        verify(repository).recoverExpiredLeases(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(repository).commitLeaseOutcome(
                "tenant-1", "operation-1", "lease-1", 7, outcome);
    }

    @Test
    void unexpectedTransportExceptionIsPersistedAsPossiblySentUncertainty() {
        EngineCommandRepository repository = mock(EngineCommandRepository.class);
        EngineCommandTransport transport = mock(EngineCommandTransport.class);
        ProductionEngineCommandStore.StoredCommand command = command(3);
        var lease = new ProductionEngineCommandStore.LeasedCommand(command,
                "lease-2", "worker-1", OffsetDateTime.ofInstant(
                NOW.plusSeconds(30), ZoneOffset.UTC));
        when(repository.claimDue(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(lease), List.of());
        when(transport.dispatch(command)).thenThrow(new IllegalStateException("secret=response"));

        new EngineCommandDispatcher(repository, transport, "worker-1",
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).drainOnce();

        verify(repository).commitLeaseOutcome(eq("tenant-1"), eq("operation-1"),
                eq("lease-2"), eq(3L), eq(CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.UNKNOWN)));
    }

    @Test
    void duplicateConfirmationEvidenceIsPassedThroughWithoutGeneratingAnotherEffect() {
        EngineCommandRepository repository = mock(EngineCommandRepository.class);
        EngineCommandTransport transport = mock(EngineCommandTransport.class);
        ProductionEngineCommandStore.StoredCommand command = command(4);
        var lease = new ProductionEngineCommandStore.LeasedCommand(command,
                "lease-3", "worker-1", OffsetDateTime.ofInstant(
                NOW.plusSeconds(30), ZoneOffset.UTC));
        var evidence = new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-1", "operation-1", "command-1", EngineCommand.Type.START_PROCESS,
                "definition-1", "process-1", CommandDispatchOutcome.RemoteState.PROCESS_STARTED,
                CommandDispatchOutcome.ConfirmationSource.DUPLICATE_RESPONSE,
                "duplicate:command-1");
        CommandDispatchOutcome outcome = CommandDispatchOutcome.duplicateResponse(evidence);
        when(repository.claimDue(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(lease), List.of());
        when(transport.dispatch(command)).thenReturn(outcome);

        new EngineCommandDispatcher(repository, transport, "worker-1",
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).drainOnce();

        verify(repository).commitLeaseOutcome(
                "tenant-1", "operation-1", "lease-3", 4, outcome);
        verify(transport, times(1)).dispatch(command);
    }

    @Test
    void commitFailureLeavesTheNextCommandUnleasedAndImmediatelyDispatchable() {
        EngineCommandRepository repository = mock(EngineCommandRepository.class);
        EngineCommandTransport transport = mock(EngineCommandTransport.class);
        ProductionEngineCommandStore.StoredCommand first = command(1);
        ProductionEngineCommandStore.StoredCommand second = command("operation-2", 1);
        var firstLease = new ProductionEngineCommandStore.LeasedCommand(first,
                "lease-a", "worker-1", OffsetDateTime.ofInstant(
                NOW.plusSeconds(30), ZoneOffset.UTC));
        var secondLease = new ProductionEngineCommandStore.LeasedCommand(second,
                "lease-b", "worker-1", OffsetDateTime.ofInstant(
                NOW.plusSeconds(30), ZoneOffset.UTC));
        CommandDispatchOutcome outcome = CommandDispatchOutcome.transportFailure(
                CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE);
        when(repository.claimDue(eq("worker-1"), eq(1), any(), any()))
                .thenReturn(List.of(firstLease), List.of(secondLease), List.of());
        when(transport.dispatch(any())).thenReturn(outcome);
        doThrow(new IllegalStateException("database unavailable")).when(repository)
                .commitLeaseOutcome("tenant-1", "operation-1", "lease-a", 1, outcome);
        EngineCommandDispatcher dispatcher = new EngineCommandDispatcher(repository, transport,
                "worker-1", Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

        org.assertj.core.api.Assertions.assertThatThrownBy(dispatcher::drainOnce)
                .isInstanceOf(IllegalStateException.class);
        verify(repository, times(1)).claimDue(
                eq("worker-1"), eq(1), any(), any());

        reset(repository);
        when(repository.claimDue(eq("worker-1"), eq(1), any(), any()))
                .thenReturn(List.of(secondLease), List.of());
        dispatcher.drainOnce();
        verify(repository).commitLeaseOutcome(
                "tenant-1", "operation-2", "lease-b", 1, outcome);
    }

    private static ProductionEngineCommandStore.StoredCommand command(long version) {
        return command("operation-1", version);
    }

    private static ProductionEngineCommandStore.StoredCommand command(
            String operationId, long version) {
        ProductionEngineCommandStore.StoredCommand command =
                mock(ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        when(command.state()).thenReturn(state);
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext(
                "tenant-1", operationId, "command-" + operationId,
                EngineCommand.Type.START_PROCESS, "definition-1"));
        when(command.operationId()).thenReturn(operationId);
        when(command.version()).thenReturn(version);
        return command;
    }
}
