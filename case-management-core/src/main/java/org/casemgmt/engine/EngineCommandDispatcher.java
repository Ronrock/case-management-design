package org.casemgmt.engine;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.orchestration.OrchestrationDeploymentClient;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;

import java.util.Base64;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Drains the engine command outbox against the real (remote) gateway and reports the resulting
 * sync state back onto CM_TASK (for {@code CREATE_TASK}) or CM_LINKED_PROCESS (for {@code
 * START_PROCESS}), so {@code availableActions} can withhold {@code claim} until the engine
 * actually has the task, and a linked process's separate correlation can be replaced by the
 * engine's real process-instance identity once confirmed.
 *
 * <p><b>Timeout assumption, for Task 25 (the production {@code RestClient} bean):</b> this
 * dispatcher's whole retry-versus-dead-letter decision depends on {@link EngineGateway} calls
 * either succeeding or throwing {@link EngineException} within a bounded time. Task 12's review
 * already flagged that the {@code RestClient} backing the remote gateway has no connect/read
 * timeout configured — a remote engine that is up but hung produces no exception at all, so
 * {@link #drainOnce} blocks forever on that one command instead of retrying or dead-lettering it,
 * and every command behind it in the same batch never gets a chance to run. Whoever wires the
 * production {@code RestClient} MUST set both a connect and a read timeout; this class has no way
 * to defend against an unbounded delegate call from the outside.
 */
public class EngineCommandDispatcher {

    /**
     * Callback: (correlation key, sync state, engine id). For {@code CREATE_TASK} the correlation
     * key is {@code planItemId} (a human task is always backed by exactly one plan item). For
     * {@code START_PROCESS} failures use the {@code CM_LINKED_PROCESS} row correlation. Successful
     * starts use {@link #confirmProcessStarted} because root confirmation also needs the case id
     * and confirmation time for the atomic case/link update.
     */
    public interface SyncReporter {
        void report(String correlationKey, CaseTask.EngineSync sync, String engineId);

        /**
         * Reports a successful process start with all identities needed to atomically confirm
         * both a root link and its owning case. Simple reporters retain the older callback
         * behavior; production remote wiring overrides this method.
         */
        default void confirmProcessStarted(String caseId, String correlationId,
                                           String engineProcessInstanceId,
                                           OffsetDateTime confirmedAt) {
            report(correlationId, CaseTask.EngineSync.SYNCED, engineProcessInstanceId);
        }

        default void confirmProcessStarted(String caseId, String correlationId,
                                           String engineProcessInstanceId,
                                           String processDefinitionId,
                                           String processDefinitionKey,
                                           OffsetDateTime confirmedAt) {
            confirmProcessStarted(caseId, correlationId, engineProcessInstanceId, confirmedAt);
        }
    }

    public interface DeploymentReporter {
        void report(String releaseId, ReleaseStatus status, EngineDeploymentIdentity identity,
                    String failureDetail);
    }

    private final EngineCommandRepository commands;
    private final EngineGateway delegate;
    private final SyncReporter syncReporter;
    private final DeploymentReporter deploymentReporter;
    private final EngineCommandTransport transport;
    private final String workerOwner;
    private final Clock clock;
    private final Duration leaseDuration;
    private final EventPublisher events;

    public EngineCommandDispatcher(EngineCommandRepository commands, EngineGateway delegate,
                                   SyncReporter syncReporter) {
        this(commands, delegate, syncReporter, (release, status, deployment, failure) -> { });
    }

    public EngineCommandDispatcher(EngineCommandRepository commands, EngineGateway delegate,
                                   SyncReporter syncReporter,
                                   DeploymentReporter deploymentReporter) {
        this.commands = commands;
        this.delegate = delegate;
        this.syncReporter = syncReporter;
        this.deploymentReporter = deploymentReporter;
        this.transport = null;
        this.workerOwner = null;
        this.clock = null;
        this.leaseDuration = null;
        this.events = null;
    }

    /** Production dispatcher: all results flow through the typed policy/store boundary. */
    public EngineCommandDispatcher(
            EngineCommandRepository commands, EngineCommandTransport transport,
            String workerOwner, Clock clock, Duration leaseDuration) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.workerOwner = java.util.Objects.requireNonNull(workerOwner, "workerOwner");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.leaseDuration = java.util.Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.delegate = null;
        this.syncReporter = null;
        this.deploymentReporter = null;
        this.events = null;
    }

    /** Production dispatcher with lifecycle publication for command-backed ad-hoc actions. */
    public EngineCommandDispatcher(
            EngineCommandRepository commands, EngineCommandTransport transport,
            String workerOwner, Clock clock, Duration leaseDuration, EventPublisher events) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.workerOwner = java.util.Objects.requireNonNull(workerOwner, "workerOwner");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.leaseDuration = java.util.Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.delegate = null;
        this.syncReporter = null;
        this.deploymentReporter = null;
    }

    public int drainOnce() {
        if (transport != null) return drainProduction();
        List<EngineCommand> due = commands.claimDue(50);
        for (EngineCommand command : due) {
            try {
                execute(command);
                commands.markDone(command.id());
            } catch (RuntimeException e) {
                if (EngineCommand.exhausted(command.attempts())) {
                    commands.markDead(command.id(), e.getMessage());
                    reportFailure(command, e.getMessage());
                } else {
                    commands.markRetry(command.id(), e.getMessage(),
                            EngineCommand.nextAttempt(command.attempts()));
                }
            }
        }
        return due.size();
    }

    private int drainProduction() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        commands.recoverExpiredLeases(now);
        int processed = 0;
        while (processed < 50) {
            List<ProductionEngineCommandStore.LeasedCommand> due = commands.claimDue(
                    workerOwner, 1, now, leaseDuration);
            if (due.isEmpty()) break;
            ProductionEngineCommandStore.LeasedCommand lease = due.getFirst();
            ProductionEngineCommandStore.StoredCommand command = lease.command();
            CommandDispatchOutcome outcome;
            try {
                outcome = java.util.Objects.requireNonNull(
                        transport.dispatch(command), "transport outcome");
            } catch (RuntimeException unexpected) {
                // An unclassified client failure may have happened after bytes left this process.
                // Persist uncertainty instead of blindly re-sending a non-idempotent command.
                outcome = CommandDispatchOutcome.transportFailure(
                        CommandDispatchOutcome.TransportFailure.UNKNOWN);
            }
            ProductionEngineCommandStore.StoredCommand committed = commands.commitLeaseOutcome(command.state().command().tenantId(),
                    command.operationId(), lease.leaseToken(), command.version(), outcome);
            publishAdHocTerminal(command, committed);
            processed++;
        }
        return processed;
    }

    private void publishAdHocTerminal(ProductionEngineCommandStore.StoredCommand submitted,
                                      ProductionEngineCommandStore.StoredCommand committed) {
        if (events == null || committed.correlationJson() == null) return;
        Map<String, Object> correlation;
        try {
            correlation = JsonCodec.toMap(committed.correlationJson());
        } catch (RuntimeException malformed) {
            return; // correlation is auxiliary metadata; never turn a dispatched effect into a retry
        }
        Object action = correlation.get("adHocActionId");
        if (!(action instanceof String actionId) || actionId.isBlank()) return;
        EngineCommandStatus status = committed.state().committedDecision().status();
        String event = switch (status) {
            case CONFIRMED -> EventTypes.AD_HOC_ACTION_CONFIRMED;
            case FAILED -> EventTypes.AD_HOC_ACTION_FAILED;
            default -> null;
        };
        if (event == null) return;
        events.publish(new CaseEvent(CaseIds.newId(), events.engineId(), event, committed.caseId(),
                submitted.state().command().tenantId(), OffsetDateTime.now(clock),
                Map.of("actionId", actionId, "operationId", committed.operationId(),
                        "commandType", submitted.state().command().commandType().name(),
                        "status", status.name())));
    }

    private void execute(EngineCommand command) {
        Map<String, Object> p = command.payload();
        switch (command.type()) {
            case CREATE_TASK -> {
                EngineTaskRef ref = delegate.createHumanTask(new HumanTaskRequest(
                        command.caseId(), str(p, "planItemId"), str(p, "name"),
                        blankToNull(str(p, "assignee")), strings(p.get("candidateGroups")),
                        blankToNull(str(p, "formKey")), map(p.get("variables")), command.id()));
                syncReporter.report(str(p, "planItemId"), CaseTask.EngineSync.SYNCED, ref.engineTaskId());
            }
            case CLAIM_TASK -> delegate.claimTask(str(p, "engineTaskId"), str(p, "userId"));
            case COMPLETE_TASK -> delegate.completeTask(str(p, "engineTaskId"), map(p.get("variables")));
            case START_PROCESS -> {
                EngineProcessRef ref;
                // Commands written before exact-ID support have no selectionType and contain
                // only a key. Treat that historical shape as the explicit legacy path; only a
                // newly written ID marker may enter the exact-start path.
                if (!"ID".equals(str(p, "selectionType"))) {
                    ref = delegate.startProcessByKey(new StartProcessByKeyRequest(
                            command.caseId(), blankToNull(str(p, "planItemId")),
                            str(p, "processDefinitionKey"), map(p.get("variables")),
                            blankToNull(str(p, "correlationId")),
                            blankToNull(str(p, "tenantId"))));
                } else {
                    ref = delegate.startProcess(new StartProcessRequest(
                            command.caseId(), blankToNull(str(p, "planItemId")),
                            str(p, "processDefinitionId"), blankToNull(str(p, "processDefinitionKey")),
                            blankToNull(str(p, "tenantId")), map(p.get("variables")),
                            blankToNull(str(p, "correlationId"))));
                }
                String processInstanceId = requireProcessInstanceId(ref);
                DefinitionIdentity definition = definitionIdentity(p, ref);
                syncReporter.confirmProcessStarted(command.caseId(), str(p, "correlationId"),
                        processInstanceId, definition.id(), definition.key(), OffsetDateTime.now());
            }
            case CANCEL_PROCESS -> delegate.cancelProcess(str(p, "processInstanceId"), str(p, "reason"));
            case DEPLOY_ORCHESTRATION -> {
                if (!(delegate instanceof OrchestrationDeploymentClient deploymentClient)) {
                    throw new EngineException("Configured engine does not support orchestration deployment");
                }
                String releaseId = str(p, "releaseId");
                EngineDeploymentIdentity identity = deploymentClient.deploy(releaseId,
                        str(p, "definitionKey"), blankToNull(str(p, "tenantId")),
                        Base64.getDecoder().decode(str(p, "contentBase64")),
                        str(p, "mediaType"));
                deploymentReporter.report(releaseId, ReleaseStatus.ACTIVE, identity, null);
            }
            case CORRELATE_MESSAGE -> delegate.correlateMessage(new MessageCorrelationRequest(
                    command.caseId(), str(p, "messageName"), map(p.get("variables"))));
        }
    }

    /**
     * Reports {@code FAILED} once a command is dead-lettered, so whatever row is waiting on it
     * does not stay {@code PENDING} forever. {@code CREATE_TASK} and {@code START_PROCESS} use
     * different keys in their payload ({@code planItemId} vs. {@code correlationId} — see {@link
     * SyncReporter}'s Javadoc), so both are checked; a command only ever populates one of them,
     * and {@code blankToNull} guards the {@code ""} sentinel {@link OutboxEngineGateway} writes
     * for an absent value (an ad hoc linked process's {@code planItemId}, in particular) from
     * being reported as a real correlation key.
     */
    private void reportFailure(EngineCommand command, String failureDetail) {
        Map<String, Object> p = command.payload();
        switch (command.type()) {
            case CREATE_TASK -> {
                String planItemId = blankToNull(str(p, "planItemId"));
                if (planItemId != null) {
                    syncReporter.report(planItemId, CaseTask.EngineSync.FAILED, null);
                }
            }
            case START_PROCESS -> {
                String correlationId = blankToNull(str(p, "correlationId"));
                if (correlationId != null) {
                    syncReporter.report(correlationId, CaseTask.EngineSync.FAILED, null);
                }
            }
            case DEPLOY_ORCHESTRATION -> deploymentReporter.report(
                    str(p, "releaseId"), ReleaseStatus.FAILED, null, failureDetail);
            default -> {
                // No local task/process row waits for sync state on these command types.
            }
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String requireProcessInstanceId(EngineProcessRef ref) {
        String processInstanceId = ref == null ? null : blankToNull(ref.processInstanceId());
        if (processInstanceId == null) {
            throw new EngineException("Engine start returned no process-instance id");
        }
        return processInstanceId;
    }

    private static DefinitionIdentity definitionIdentity(Map<String, Object> payload,
                                                         EngineProcessRef ref) {
        String requestedKey = blankToNull(str(payload, "processDefinitionKey"));
        String returnedKey = ref == null ? null : blankToNull(ref.processDefinitionKey());
        if (requestedKey == null || returnedKey == null || !requestedKey.equals(returnedKey)) {
            throw new EngineException("Engine start returned an inconsistent process-definition key");
        }
        String returnedId = ref == null ? null : blankToNull(ref.processDefinitionId());
        if ("ID".equals(str(payload, "selectionType"))) {
            String requestedId = blankToNull(str(payload, "processDefinitionId"));
            if (requestedId == null || (returnedId != null && !requestedId.equals(returnedId))) {
                throw new EngineException("Engine start returned an inconsistent process-definition id");
            }
            return new DefinitionIdentity(requestedId, requestedKey);
        }
        if (returnedId == null) {
            throw new EngineException("Engine start returned no process-definition id");
        }
        return new DefinitionIdentity(returnedId, returnedKey);
    }

    private record DefinitionIdentity(String id, String key) { }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
