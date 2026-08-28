package org.casemgmt.observation;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.rules.PlanModelFixtures.caseInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistedProcessCaseAuthorityTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-28T11:00:00Z");

    private EngineProcessAuthorityLookup engine;
    private LinkedProcessRepository processes;
    private CaseRepository cases;
    private CaseDefinitionVersionBindingRepository bindings;
    private PersistedProcessCaseAuthority authority;

    @BeforeEach
    void setUp() {
        engine = mock(EngineProcessAuthorityLookup.class);
        processes = mock(LinkedProcessRepository.class);
        cases = mock(CaseRepository.class);
        bindings = mock(CaseDefinitionVersionBindingRepository.class);
        authority = new PersistedProcessCaseAuthority(engine, processes, cases, bindings,
                Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC));
    }

    @Test
    void atomicallyConfirmsPendingPersistedAuthorityFromTheReservedMarker() {
        var link = pending("child-process");
        when(processes.findByProcessInstanceId("process-42")).thenReturn(Optional.empty());
        running("child-process:3", "correlation-7");
        when(processes.findByCorrelation("correlation-7")).thenReturn(Optional.of(link));
        allowBpmnCase("eng-a:1");
        definition("child-process:3", "child-process", "t1");

        assertThat(authority.caseId("process-42", "child-process:3"))
                .isEqualTo("eng-a:1");

        verify(processes).confirmStarted("eng-a:1", "correlation-7", "process-42",
                "child-process:3", "child-process", CONFIRMED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void ignoresAnUnmarkedProcessEvenWhenItsBusinessKeyLooksLikeACaseId() {
        when(processes.findByProcessInstanceId("foreign-process")).thenReturn(Optional.empty());
        running("foreign-process", "foreign:1", null);

        assertThat(authority.caseId("foreign-process", "foreign:1")).isNull();

        verify(processes, never()).confirmStarted(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolvesAConfirmedManagedProcessWithoutDependingOnEngineRuntime() {
        var link = confirmed("child-process:3", "child-process");
        when(processes.findByProcessInstanceId("process-42")).thenReturn(Optional.of(link));
        allowBpmnCase("eng-a:1");

        assertThat(authority.caseId("process-42", "child-process:3"))
                .isEqualTo("eng-a:1");

        verify(engine, never()).lifecycleCorrelationId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAConfirmedProcessWhenTheCallbackCarriesAnotherExactDefinition() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(confirmed("child-process:3", "child-process")));

        assertThat(authority.authority("process-42", "child-process:4")).isEmpty();

        verify(cases, never()).require(org.mockito.ArgumentMatchers.any());
        verify(bindings, never()).find(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolvesPlanModelOnlyThroughPersistedAuthorityForTheCompatibilityPath() {
        var link = pending("legacy-process");
        when(processes.findByProcessInstanceId("process-42")).thenReturn(Optional.empty());
        running("legacy-process:1", "correlation-7");
        when(processes.findByCorrelation("correlation-7")).thenReturn(Optional.of(link));
        allowPlanModelCase("eng-a:1");
        definition("legacy-process:1", "legacy-process", "t1");

        assertThat(authority.authority("process-42", "legacy-process:1"))
                .contains(new ProcessCaseAuthority.Authority(
                        "eng-a:1", OrchestrationMode.PLAN_MODEL));

        verify(processes).confirmStarted("eng-a:1", "correlation-7", "process-42",
                "legacy-process:1", "legacy-process", CONFIRMED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void claimsMissingDefinitionIdentityForAMigratedConfirmedPlanModelLink() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(migrated("legacy-process")));
        allowPlanModelCase("eng-a:1");
        definition("legacy-process:1", "legacy-process", "t1");

        assertThat(authority.authority("process-42", "legacy-process:1"))
                .contains(new ProcessCaseAuthority.Authority(
                        "eng-a:1", OrchestrationMode.PLAN_MODEL));

        verify(processes).confirmStarted("eng-a:1", "correlation-7", "process-42",
                "legacy-process:1", "legacy-process", CONFIRMED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void keepsBpmnStrictWhenAConfirmedLinkHasNoExactDefinitionIdentity() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(migrated("child-process")));
        allowBpmnCase("eng-a:1");

        assertThat(authority.authority("process-42", "child-process:3")).isEmpty();

        verify(engine, never()).processDefinition(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsMigratedPlanModelDefinitionWithWrongStoredKey() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(migrated("legacy-process")));
        allowPlanModelCase("eng-a:1");
        definition("other-process:1", "other-process", "t1");

        assertThatThrownBy(() -> authority.authority("process-42", "other-process:1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match stored key");
    }

    @Test
    void rejectsMigratedPlanModelDefinitionFromAnotherTenant() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(migrated("legacy-process")));
        allowPlanModelCase("eng-a:1");
        definition("legacy-process:1", "legacy-process", "tenant-b");

        assertThatThrownBy(() -> authority.authority("process-42", "legacy-process:1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void rejectsUnknownExactDefinitionForMigratedPlanModelLink() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(migrated("legacy-process")));
        allowPlanModelCase("eng-a:1");
        when(engine.processDefinition("missing:1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authority.authority("process-42", "missing:1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No exact process definition");
    }

    @Test
    void acceptsAMigratedPlanModelDefinitionWhenBothTenantsAreNull() {
        when(processes.findByProcessInstanceId("process-42"))
                .thenReturn(Optional.of(migrated("legacy-process")));
        var instance = mock(org.casemgmt.domain.CaseInstance.class);
        when(instance.caseDefId()).thenReturn("d:1");
        when(instance.tenantId()).thenReturn(null);
        when(cases.require("eng-a:1")).thenReturn(instance);
        CaseDefinitionVersionBinding binding = mock(CaseDefinitionVersionBinding.class);
        when(binding.orchestrationMode()).thenReturn(OrchestrationMode.PLAN_MODEL);
        when(bindings.find("d:1")).thenReturn(Optional.of(binding));
        definition("legacy-process:1", "legacy-process", null);

        assertThat(authority.authority("process-42", "legacy-process:1"))
                .contains(new ProcessCaseAuthority.Authority(
                        "eng-a:1", OrchestrationMode.PLAN_MODEL));
    }

    private void running(String definitionId, String correlationId) {
        running("process-42", definitionId, correlationId);
    }

    private void running(String processInstanceId, String definitionId, String correlationId) {
        when(engine.processDefinitionId(processInstanceId)).thenReturn(Optional.of(definitionId));
        when(engine.lifecycleCorrelationId(processInstanceId))
                .thenReturn(Optional.ofNullable(correlationId));
    }

    private void definition(String id, String key, String tenantId) {
        when(engine.processDefinition(id)).thenReturn(Optional.of(
                new EngineProcessAuthorityLookup.ProcessDefinition(id, key, tenantId)));
    }

    private LinkedProcessRepository.LinkedProcessRow pending(String key) {
        return new LinkedProcessRepository.LinkedProcessRow("link-1", "eng-a:1", null,
                "correlation-7", null, null, key, "ACTIVE",
                CaseTask.EngineSync.PENDING, false);
    }

    private LinkedProcessRepository.LinkedProcessRow confirmed(String definitionId, String key) {
        return new LinkedProcessRepository.LinkedProcessRow("link-1", "eng-a:1", null,
                "correlation-7", "process-42", definitionId, key, "ACTIVE",
                CaseTask.EngineSync.SYNCED, false);
    }

    private LinkedProcessRepository.LinkedProcessRow migrated(String key) {
        return confirmed(null, key);
    }

    private void allowBpmnCase(String caseId) {
        allowCase(caseId, OrchestrationMode.BPMN);
    }

    private void allowPlanModelCase(String caseId) {
        allowCase(caseId, OrchestrationMode.PLAN_MODEL);
    }

    private void allowCase(String caseId, OrchestrationMode mode) {
        var instance = caseInstance(Map.of());
        when(cases.require(caseId)).thenReturn(instance);
        CaseDefinitionVersionBinding binding = mock(CaseDefinitionVersionBinding.class);
        when(binding.orchestrationMode()).thenReturn(mode);
        when(bindings.find(instance.caseDefId())).thenReturn(Optional.of(binding));
    }
}
