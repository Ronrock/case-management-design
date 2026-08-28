package org.casemgmt.engine.embedded;

import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;

/** Resolves only persisted BPMN authorities, never an arbitrary engine business key. */
public final class PersistedProcessCaseCorrelation implements ProcessCaseCorrelation {

    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final LinkedProcessRepository processes;
    private final CaseRepository cases;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final Clock clock;

    public PersistedProcessCaseCorrelation(
            RuntimeService runtime,
            RepositoryService repository,
            LinkedProcessRepository processes,
            CaseRepository cases,
            CaseDefinitionVersionBindingRepository bindings) {
        this(runtime, repository, processes, cases, bindings, Clock.systemUTC());
    }

    PersistedProcessCaseCorrelation(
            RuntimeService runtime,
            RepositoryService repository,
            LinkedProcessRepository processes,
            CaseRepository cases,
            CaseDefinitionVersionBindingRepository bindings,
            Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String caseId(String processInstanceId) {
        var instance = runtime.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return caseId(processInstanceId,
                instance == null ? null : instance.getProcessDefinitionId());
    }

    @Override
    public String caseId(String processInstanceId, String processDefinitionId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return null;
        }
        var confirmed = processes.findByProcessInstanceId(processInstanceId);
        if (confirmed.isPresent()) {
            return isBpmnCase(confirmed.orElseThrow().caseId())
                    ? confirmed.orElseThrow().caseId() : null;
        }

        Object marker = runtime.getVariable(processInstanceId,
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE);
        if (!(marker instanceof String correlationId) || correlationId.isBlank()) {
            return null;
        }
        var pending = processes.findByCorrelation(correlationId);
        if (pending.isEmpty() || !isBpmnCase(pending.orElseThrow().caseId())) {
            return null;
        }
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new IllegalStateException("Managed process " + processInstanceId
                    + " has no exact process-definition id");
        }
        var definition = repository.getProcessDefinition(processDefinitionId);
        if (definition == null || definition.getKey() == null || definition.getKey().isBlank()) {
            throw new IllegalStateException("No exact process definition " + processDefinitionId
                    + " for managed process " + processInstanceId);
        }
        var link = pending.orElseThrow();
        processes.confirmStarted(link.caseId(), correlationId, processInstanceId,
                processDefinitionId, definition.getKey(),
                clock.instant().atOffset(ZoneOffset.UTC));
        return link.caseId();
    }

    private boolean isBpmnCase(String caseId) {
        var instance = cases.require(caseId);
        return bindings.find(instance.caseDefId())
                .filter(binding -> binding.orchestrationMode() == OrchestrationMode.BPMN)
                .isPresent();
    }
}
