package org.casemgmt.engine.embedded;

import org.casemgmt.domain.CaseInstance;
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
        return authority(processInstanceId,
                instance == null ? null : instance.getProcessDefinitionId())
                .map(Authority::caseId).orElse(null);
    }

    @Override
    public String caseId(String processInstanceId, String processDefinitionId) {
        return authority(processInstanceId, processDefinitionId)
                .map(Authority::caseId).orElse(null);
    }

    @Override
    public java.util.Optional<Authority> authority(
            String processInstanceId, String processDefinitionId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return java.util.Optional.empty();
        }
        var confirmed = processes.findByProcessInstanceId(processInstanceId);
        if (confirmed.isPresent()) {
            var link = confirmed.orElseThrow();
            if (link.processDefinitionId() != null
                    && (processDefinitionId == null
                    || !processDefinitionId.equals(link.processDefinitionId()))) {
                return java.util.Optional.empty();
            }
            var managed = managedCase(link.caseId());
            if (managed.isEmpty()) {
                return java.util.Optional.empty();
            }
            if (link.processDefinitionId() != null) {
                return java.util.Optional.of(managed.orElseThrow().authority());
            }
            // Only legacy PLAN_MODEL rows may lack the exact definition id. BPMN authority is
            // strict and never infers or repairs an incomplete link from callback data.
            if (managed.orElseThrow().authority().orchestrationMode()
                    != org.casemgmt.orchestration.OrchestrationMode.PLAN_MODEL
                    || processDefinitionId == null || processDefinitionId.isBlank()) {
                return java.util.Optional.empty();
            }
            var definition = exactDefinition(processDefinitionId, processInstanceId);
            validateDefinitionAuthority(link, managed.orElseThrow().instance(), definition,
                    processInstanceId);
            processes.confirmStarted(link.caseId(), link.correlationId(), processInstanceId,
                    processDefinitionId, definition.getKey(),
                    clock.instant().atOffset(ZoneOffset.UTC));
            return java.util.Optional.of(managed.orElseThrow().authority());
        }

        Object marker = runtime.getVariable(processInstanceId,
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE);
        if (!(marker instanceof String correlationId) || correlationId.isBlank()) {
            return java.util.Optional.empty();
        }
        var pending = processes.findByCorrelation(correlationId);
        if (pending.isEmpty()) {
            return java.util.Optional.empty();
        }
        var managed = managedCase(pending.orElseThrow().caseId());
        if (managed.isEmpty()) return java.util.Optional.empty();
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new IllegalStateException("Managed process " + processInstanceId
                    + " has no exact process-definition id");
        }
        var definition = exactDefinition(processDefinitionId, processInstanceId);
        var link = pending.orElseThrow();
        validateDefinitionAuthority(link, managed.orElseThrow().instance(), definition,
                processInstanceId);
        processes.confirmStarted(link.caseId(), correlationId, processInstanceId,
                processDefinitionId, definition.getKey(),
                clock.instant().atOffset(ZoneOffset.UTC));
        return java.util.Optional.of(managed.orElseThrow().authority());
    }

    private org.operaton.bpm.engine.repository.ProcessDefinition exactDefinition(
            String processDefinitionId, String processInstanceId) {
        var definition = repository.getProcessDefinition(processDefinitionId);
        if (definition == null || definition.getKey() == null || definition.getKey().isBlank()) {
            throw new IllegalStateException("No exact process definition " + processDefinitionId
                    + " for managed process " + processInstanceId);
        }
        return definition;
    }

    private static void validateDefinitionAuthority(
            LinkedProcessRepository.LinkedProcessRow link,
            CaseInstance instance,
            org.operaton.bpm.engine.repository.ProcessDefinition definition,
            String processInstanceId) {
        if (!Objects.equals(link.processDefinitionKey(), definition.getKey())) {
            throw new IllegalStateException("Process definition " + definition.getId()
                    + " does not match stored key for managed process " + processInstanceId);
        }
        if (!Objects.equals(instance.tenantId(), definition.getTenantId())) {
            throw new IllegalStateException("Process definition tenant does not match case tenant "
                    + "for managed process " + processInstanceId);
        }
    }

    private java.util.Optional<ManagedCase> managedCase(String caseId) {
        var instance = cases.require(caseId);
        return bindings.find(instance.caseDefId())
                .map(binding -> new ManagedCase(instance,
                        new Authority(caseId, binding.orchestrationMode())));
    }

    private record ManagedCase(CaseInstance instance, Authority authority) { }
}
