package org.casemgmt.observation;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandPolicy;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandConfirmationLifecycleReporterTest {

    @Test
    void definitiveRemoteProcessStartFailureFailsTheWaitingLinkedCorrelation() {
        CaseRepository cases = mock(CaseRepository.class);
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        EngineObservationHandler lifecycle = mock(EngineObservationHandler.class);
        ProductionEngineCommandStore.StoredCommand command = mock(ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        EngineCommandPolicy.Decision decision = mock(EngineCommandPolicy.Decision.class);
        when(command.state()).thenReturn(state);
        when(command.payload()).thenReturn(Map.of("correlationId", "linked-1"));
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext("tenant-a", "operation-1",
                "command-1", EngineCommand.Type.START_PROCESS, "definition:1"));
        when(state.committedDecision()).thenReturn(decision);
        when(decision.status()).thenReturn(EngineCommandStatus.FAILED);

        new CommandConfirmationLifecycleReporter(cases, processes, lifecycle).confirmed(command);

        verify(processes).markSync("linked-1", CaseTask.EngineSync.FAILED, null);
    }
}
