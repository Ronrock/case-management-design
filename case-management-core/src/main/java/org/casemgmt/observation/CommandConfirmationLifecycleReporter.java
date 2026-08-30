package org.casemgmt.observation;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a definitive command acknowledgement into the same lifecycle facts used by engine
 * observation ingestion. It never writes a case/task projection itself. Process starts first
 * bind their durable linked-process correlation; task creation is projected against the already
 * linked root process, so there is no second local task authority.
 */
public final class CommandConfirmationLifecycleReporter {
    private final CaseRepository cases;
    private final LinkedProcessRepository processes;
    private final EngineObservationHandler lifecycle;

    public CommandConfirmationLifecycleReporter(CaseRepository cases,
                                                LinkedProcessRepository processes,
                                                EngineObservationHandler lifecycle) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public void confirmed(ProductionEngineCommandStore.StoredCommand command) {
        if (command.state().committedDecision().status() != EngineCommandStatus.CONFIRMED) return;
        switch (command.state().command().commandType()) {
            case START_PROCESS -> confirmProcess(command);
            case CREATE_TASK -> projectCreatedTask(command);
            default -> { }
        }
    }

    private void confirmProcess(ProductionEngineCommandStore.StoredCommand command) {
        Map<String, Object> payload = command.payload();
        String correlation = text(payload, "correlationId");
        String processId = remoteIdentity(command);
        if (correlation == null || processId == null) return;
        processes.confirmStarted(command.caseId(), correlation, processId,
                text(payload, "processDefinitionId"), text(payload, "processDefinitionKey"),
                Instant.now().atOffset(java.time.ZoneOffset.UTC));
    }

    private void projectCreatedTask(ProductionEngineCommandStore.StoredCommand command) {
        var c = cases.require(command.caseId());
        String root = c.rootProcessInstanceId();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("Cannot project ad-hoc task without a linked root process");
        }
        var rootLink = processes.findByProcessInstanceId(root).orElseThrow(() ->
                new IllegalStateException("Case root process is not linked"));
        String engineTaskId = remoteIdentity(command);
        if (engineTaskId == null || engineTaskId.isBlank()) {
            throw new IllegalStateException("Confirmed task command has no engine task identity");
        }
        Map<String, Object> payload = command.payload();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("processDefinitionId", rootLink.processDefinitionId());
        attributes.put("processDefinitionKey", rootLink.processDefinitionKey());
        attributes.put("activityInstanceId", engineTaskId);
        attributes.put("taskDefinitionKey", "adhoc:" + command.commandId());
        attributes.put("name", text(payload, "name"));
        attributes.put("candidateGroups", payload.getOrDefault("candidateGroups", java.util.List.of()));
        attributes.put("formKey", text(payload, "formKey"));
        attributes.put("priority", 0);
        Instant now = Instant.now();
        lifecycle.apply(new UserTaskObservation("command:" + command.commandId() + ":created", 1,
                "command-confirmation", c.engineId(), c.tenantId(), c.id(), root, engineTaskId,
                null, UserTaskObservation.EventType.CREATED, now, now, attributes));
    }

    private static String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String remoteIdentity(ProductionEngineCommandStore.StoredCommand command) {
        var confirmation = command.state().committedDecision().terminalConfirmation();
        return confirmation == null ? null : confirmation.remoteIdentity();
    }
}
