package org.casemgmt.observation;

import org.casemgmt.engine.CommandDispatchOutcome;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.ProductionEngineCommandStore;

import java.util.Objects;

/**
 * Confirms only a completed remote task whose stable engine ID is the command's immutable target.
 * Other command types deliberately remain awaiting confirmation until their own evidence contract
 * is implemented; guessing from a history row would turn an ambiguous remote call into a false
 * success.
 */
public final class CompleteTaskObservationCommandReconciler {
    private final ProductionEngineCommandStore commands;

    public CompleteTaskObservationCommandReconciler(ProductionEngineCommandStore commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public void reconcile(EngineObservation observation) {
        if (!(observation instanceof UserTaskObservation task)
                || task.eventType() != UserTaskObservation.EventType.COMPLETED
                || !"remote-history".equals(task.source())) {
            return;
        }
        for (ProductionEngineCommandStore.StoredCommand command : commands.awaitingConfirmation(
                task.tenantId(), EngineCommand.Type.COMPLETE_TASK, task.entityId())) {
            CommandDispatchOutcome.ConfirmationEvidence evidence =
                    new CommandDispatchOutcome.ConfirmationEvidence(task.tenantId(),
                            command.operationId(), command.commandId(), EngineCommand.Type.COMPLETE_TASK,
                            command.expectedTargetIdentity(), task.entityId(),
                            CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                            CommandDispatchOutcome.ConfirmationSource.RECONCILIATION,
                            "remote-history:" + task.observationId());
            try {
                commands.applyOutcome(task.tenantId(), command.operationId(), command.version(),
                        CommandDispatchOutcome.reconciliationConfirmed(evidence));
            } catch (ProductionEngineCommandStore.OptimisticCommandException concurrent) {
                // A concurrent dispatcher/reconciler may already have resolved it.  Only suppress
                // that benign race; a still-awaiting command must be retried from the inbox.
                if (commands.require(task.tenantId(), command.operationId()).state()
                        .committedDecision().status() == EngineCommandStatus.AWAITING_CONFIRMATION) {
                    throw concurrent;
                }
            }
        }
    }
}
