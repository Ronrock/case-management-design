package org.casemgmt.engine.embedded;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.repository.ProcessDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.caseInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistedProcessCaseCorrelationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-28T11:00:00Z");

    private RuntimeService runtime;
    private RepositoryService repository;
    private LinkedProcessRepository processes;
    private CaseRepository cases;
    private CaseDefinitionVersionBindingRepository bindings;
    private PersistedProcessCaseCorrelation correlation;

    @BeforeEach
    void setUp() {
        runtime = mock(RuntimeService.class);
        repository = mock(RepositoryService.class);
        processes = mock(LinkedProcessRepository.class);
        cases = mock(CaseRepository.class);
        bindings = mock(CaseDefinitionVersionBindingRepository.class);
        correlation = new PersistedProcessCaseCorrelation(runtime, repository, processes,
                cases, bindings, Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC));
    }

    @Test
    void atomicallyConfirmsPendingPersistedAuthorityFromTheReservedMarker() {
        var link = new LinkedProcessRepository.LinkedProcessRow("link-1", "eng-a:1", null,
                "correlation-7", null, null, "child-process", "ACTIVE",
                CaseTask.EngineSync.PENDING, false);
        when(processes.findByProcessInstanceId("process-42")).thenReturn(Optional.empty());
        when(runtime.getVariable("process-42",
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE))
                .thenReturn("correlation-7");
        when(processes.findByCorrelation("correlation-7")).thenReturn(Optional.of(link));
        allowBpmnCase("eng-a:1");
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("child-process");
        when(repository.getProcessDefinition("child-process:3")).thenReturn(definition);

        assertThat(correlation.caseId("process-42", "child-process:3"))
                .isEqualTo("eng-a:1");

        verify(processes).confirmStarted("eng-a:1", "correlation-7", "process-42",
                "child-process:3", "child-process", CONFIRMED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void ignoresAnUnmarkedProcessEvenWhenItsBusinessKeyLooksLikeACaseId() {
        when(processes.findByProcessInstanceId("foreign-process")).thenReturn(Optional.empty());
        when(runtime.getVariable("foreign-process",
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE)).thenReturn(null);

        assertThat(correlation.caseId("foreign-process", "foreign:1")).isNull();

        verify(processes, never()).confirmStarted(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolvesAConfirmedManagedProcessWithoutDependingOnRuntimeVariables() {
        var link = new LinkedProcessRepository.LinkedProcessRow("link-1", "eng-a:1", null,
                "correlation-7", "process-42", "child-process:3", "child-process", "ACTIVE",
                CaseTask.EngineSync.SYNCED, false);
        when(processes.findByProcessInstanceId("process-42")).thenReturn(Optional.of(link));
        allowBpmnCase("eng-a:1");

        assertThat(correlation.caseId("process-42", "child-process:3"))
                .isEqualTo("eng-a:1");

        verify(runtime, never()).getVariable(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void leavesPlanModelProcessCompatibilityOnTheLegacyPath() {
        var link = new LinkedProcessRepository.LinkedProcessRow("link-1", "eng-a:1", null,
                "correlation-7", null, null, "legacy-process", "ACTIVE",
                CaseTask.EngineSync.PENDING, false);
        when(processes.findByProcessInstanceId("process-42")).thenReturn(Optional.empty());
        when(runtime.getVariable("process-42",
                EmbeddedEngineGateway.LIFECYCLE_CORRELATION_VARIABLE))
                .thenReturn("correlation-7");
        when(processes.findByCorrelation("correlation-7")).thenReturn(Optional.of(link));
        when(cases.require("eng-a:1")).thenReturn(caseInstance(Map.of()));
        CaseDefinitionVersionBinding binding = mock(CaseDefinitionVersionBinding.class);
        when(binding.orchestrationMode()).thenReturn(OrchestrationMode.PLAN_MODEL);
        when(bindings.find(caseInstance(Map.of()).caseDefId())).thenReturn(Optional.of(binding));

        assertThat(correlation.caseId("process-42", "legacy-process:1")).isNull();

        verify(processes, never()).confirmStarted(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void allowBpmnCase(String caseId) {
        var instance = caseInstance(Map.of());
        when(cases.require(caseId)).thenReturn(instance);
        CaseDefinitionVersionBinding binding = mock(CaseDefinitionVersionBinding.class);
        when(binding.orchestrationMode()).thenReturn(OrchestrationMode.BPMN);
        when(bindings.find(instance.caseDefId())).thenReturn(Optional.of(binding));
    }
}
