package org.casemgmt.observation;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/** Core owner of persisted process-to-case authority and legacy identity backfill. */
public final class PersistedProcessCaseAuthority implements ProcessCaseAuthority {

    private final EngineProcessAuthorityLookup engine;
    private final LinkedProcessRepository processes;
    private final CaseRepository cases;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final Clock clock;

    public PersistedProcessCaseAuthority(
            EngineProcessAuthorityLookup engine,
            LinkedProcessRepository processes,
            CaseRepository cases,
            CaseDefinitionVersionBindingRepository bindings) {
        this(engine, processes, cases, bindings, Clock.systemUTC());
    }

    public PersistedProcessCaseAuthority(
            EngineProcessAuthorityLookup engine,
            LinkedProcessRepository processes,
            CaseRepository cases,
            CaseDefinitionVersionBindingRepository bindings,
            Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String caseId(String processInstanceId) {
        String definitionId = engine.processDefinitionId(processInstanceId).orElse(null);
        return authority(processInstanceId, definitionId)
                .map(Authority::caseId).orElse(null);
    }

    @Override
    public String caseId(String processInstanceId, String processDefinitionId) {
        return authority(processInstanceId, processDefinitionId)
                .map(Authority::caseId).orElse(null);
    }

    @Override
    public Optional<Authority> authority(String processInstanceId, String processDefinitionId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Optional.empty();
        }
        var confirmed = processes.findByProcessInstanceId(processInstanceId);
        if (confirmed.isPresent()) {
            var link = confirmed.orElseThrow();
            if (link.processDefinitionId() != null
                    && (processDefinitionId == null
                    || !processDefinitionId.equals(link.processDefinitionId()))) {
                return Optional.empty();
            }
            var managed = managedCase(link.caseId());
            if (managed.isEmpty()) {
                return Optional.empty();
            }
            if (link.processDefinitionId() != null) {
                return Optional.of(managed.orElseThrow().authority());
            }
            if (managed.orElseThrow().authority().orchestrationMode()
                    != OrchestrationMode.PLAN_MODEL
                    || processDefinitionId == null || processDefinitionId.isBlank()) {
                return Optional.empty();
            }
            var definition = exactDefinition(processDefinitionId, processInstanceId);
            validateDefinitionAuthority(link, managed.orElseThrow().instance(), definition,
                    processInstanceId);
            processes.confirmStarted(link.caseId(), link.correlationId(), processInstanceId,
                    processDefinitionId, definition.key(),
                    clock.instant().atOffset(ZoneOffset.UTC));
            return Optional.of(managed.orElseThrow().authority());
        }

        String correlationId = engine.lifecycleCorrelationId(processInstanceId).orElse(null);
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        var pending = processes.findByCorrelation(correlationId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        var managed = managedCase(pending.orElseThrow().caseId());
        if (managed.isEmpty()) return Optional.empty();
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new IllegalStateException("Managed process " + processInstanceId
                    + " has no exact process-definition id");
        }
        var definition = exactDefinition(processDefinitionId, processInstanceId);
        var link = pending.orElseThrow();
        validateDefinitionAuthority(link, managed.orElseThrow().instance(), definition,
                processInstanceId);
        processes.confirmStarted(link.caseId(), correlationId, processInstanceId,
                processDefinitionId, definition.key(),
                clock.instant().atOffset(ZoneOffset.UTC));
        return Optional.of(managed.orElseThrow().authority());
    }

    private EngineProcessAuthorityLookup.ProcessDefinition exactDefinition(
            String processDefinitionId, String processInstanceId) {
        var definition = engine.processDefinition(processDefinitionId).orElse(null);
        if (definition == null || definition.key() == null || definition.key().isBlank()) {
            throw new IllegalStateException("No exact process definition " + processDefinitionId
                    + " for managed process " + processInstanceId);
        }
        return definition;
    }

    private static void validateDefinitionAuthority(
            LinkedProcessRepository.LinkedProcessRow link,
            CaseInstance instance,
            EngineProcessAuthorityLookup.ProcessDefinition definition,
            String processInstanceId) {
        if (!Objects.equals(link.processDefinitionKey(), definition.key())) {
            throw new IllegalStateException("Process definition " + definition.id()
                    + " does not match stored key for managed process " + processInstanceId);
        }
        if (!Objects.equals(instance.tenantId(), definition.tenantId())) {
            throw new IllegalStateException("Process definition tenant does not match case tenant "
                    + "for managed process " + processInstanceId);
        }
    }

    private Optional<ManagedCase> managedCase(String caseId) {
        var instance = cases.require(caseId);
        return bindings.find(instance.caseDefId())
                .map(binding -> new ManagedCase(instance,
                        new Authority(caseId, binding.orchestrationMode())));
    }

    private record ManagedCase(CaseInstance instance, Authority authority) { }
}
