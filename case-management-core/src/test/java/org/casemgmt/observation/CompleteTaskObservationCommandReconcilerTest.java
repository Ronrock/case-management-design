package org.casemgmt.observation;

import org.casemgmt.engine.CommandDispatchOutcome;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The command is confirmed only by a durable, matching terminal remote observation. */
class CompleteTaskObservationCommandReconcilerTest {

    @Test
    void confirmsOnlyTheAwaitingCompleteTaskWhoseImmutableTargetMatchesCompletedEvidence() {
        ProductionEngineCommandStore commands = mock(ProductionEngineCommandStore.class);
        ProductionEngineCommandStore.StoredCommand command = mock(ProductionEngineCommandStore.StoredCommand.class);
        when(command.commandId()).thenReturn("command-1");
        when(command.operationId()).thenReturn("operation-1");
        when(command.expectedTargetIdentity()).thenReturn("task-42");
        when(command.version()).thenReturn(7L);
        when(commands.awaitingConfirmation("tenant-a", EngineCommand.Type.COMPLETE_TASK,
                "task-42")).thenReturn(List.of(command));

        new CompleteTaskObservationCommandReconciler(commands).reconcile(completed("task-42"));

        verify(commands).applyOutcome(eq("tenant-a"), eq("operation-1"), eq(7L),
                any(CommandDispatchOutcome.class));
    }

    @Test
    void leavesAwaitingCommandUntouchedWhenTheObservationIsNotCompletionEvidenceForItsTarget() {
        ProductionEngineCommandStore commands = mock(ProductionEngineCommandStore.class);

        new CompleteTaskObservationCommandReconciler(commands).reconcile(created("task-42"));

        verify(commands, never()).awaitingConfirmation(any(), any(), any());
        verify(commands, never()).applyOutcome(any(), any(), any(Long.class), any());
    }

    private static UserTaskObservation completed(String taskId) {
        return observation(taskId, UserTaskObservation.EventType.COMPLETED);
    }

    private static UserTaskObservation created(String taskId) {
        return observation(taskId, UserTaskObservation.EventType.CREATED);
    }

    private static UserTaskObservation observation(String taskId, UserTaskObservation.EventType event) {
        Instant now = Instant.parse("2026-08-30T10:00:00Z");
        return new UserTaskObservation("remote-task-" + taskId + "-" + event, 1,
                "remote-history", "engine-west", "tenant-a", "case-1", "process-1", taskId,
                null, event, now, now, Map.of("taskDefinitionKey", "review"));
    }
}
