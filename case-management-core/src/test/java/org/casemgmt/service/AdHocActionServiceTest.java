package org.casemgmt.service;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineTaskRef;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CriterionEvaluator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdHocActionServiceTest {

    @Test
    void rejectsAnActionForACancelledCaseBeforeItCanCreateAnEngineTask() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        CaseInstance cancelled = instance(CaseState.CANCELLED);
        when(cases.require(cancelled.id())).thenReturn(cancelled);
        when(engine.createHumanTask(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EngineTaskRef("engine-task", "Investigate", null,
                        cancelled.id(), OffsetDateTime.parse("2026-08-30T12:00:00Z")));
        AdHocActionService service = service(cases, engine, contract("""
                {"adHocActions":[{"id":"investigate","type":"TASK","roles":["handler"]}]}
                """));

        assertThatThrownBy(() -> service.execute(cancelled.id(), "investigate", 4L, Map.of(),
                new Actor("alice", List.of("handlers"))))
                .isInstanceOf(CaseConflictException.class)
                .extracting(error -> ((CaseConflictException) error).code())
                .isEqualTo("case-not-active");

        verifyNoInteractions(engine);
    }

    @Test
    void rejectsAnUndeclaredTaskFormFieldBeforeItCanReachTheEngine() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        CaseInstance active = instance(CaseState.ACTIVE);
        when(cases.require(active.id())).thenReturn(active);
        AdHocActionService service = service(cases, engine, release("""
                {"key":"definition","orchestrationMode":"BPMN","fields":{},
                 "forms":{"review":{"schema":{"type":"object","properties":{"outcome":{"type":"string"}}}}},
                 "adHocActions":[{"id":"investigate","type":"TASK","roles":["handler"],"formRef":"review"}]}
                """));

        assertThatThrownBy(() -> service.execute(active.id(), "investigate", 4L,
                Map.of("unmapped", "must-not-be-an-engine-variable"),
                new Actor("alice", List.of("handlers"))))
                .isInstanceOf(org.casemgmt.error.FormValidationException.class)
                .hasMessageContaining("Undeclared action field 'unmapped'");

        verifyNoInteractions(engine);
    }

    @Test
    void remoteTaskActionDoesNotCreatePendingProjectionsBeforeCommandEvidence() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        PlanItemRepository planItems = mock(PlanItemRepository.class);
        CaseTaskRepository tasks = mock(CaseTaskRepository.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        CaseInstance active = instance(CaseState.ACTIVE);
        when(cases.require(active.id())).thenReturn(active);
        when(engine.defersTaskMutations()).thenReturn(true);
        when(engine.createHumanTask(org.mockito.ArgumentMatchers.any())).thenReturn(
                new EngineTaskRef(null, "Investigate", null, active.id(),
                        OffsetDateTime.parse("2026-08-30T12:00:00Z")));
        when(operations.submitAdHoc(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new EngineOperationService.Operation(
                "operation-1", "command-1", active.id(), "CREATE_TASK", "target-1", "PENDING",
                0L, null, null, List.of()));
        AdHocActionService service = service(cases, engine, planItems, tasks, operations, contract("""
                {"adHocActions":[{"id":"investigate","type":"TASK","roles":["handler"]}]}
                """));

        service.execute(active.id(), "investigate", 4L, Map.of(),
                new Actor("alice", List.of("handlers")));

        verify(planItems, never()).insert(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(tasks);
        verify(engine, org.mockito.Mockito.atLeastOnce()).defersTaskMutations();
        org.mockito.Mockito.verifyNoMoreInteractions(engine);
    }

    private static AdHocActionService service(CaseRepository cases, EngineGateway engine,
                                              CaseDefinitionRelease release) {
        return service(cases, engine, mock(PlanItemRepository.class), mock(CaseTaskRepository.class),
                null, release);
    }

    private static AdHocActionService service(CaseRepository cases, EngineGateway engine,
                                              PlanItemRepository planItems, CaseTaskRepository tasks,
                                              CaseDefinitionRelease release) {
        return service(cases, engine, planItems, tasks, null, release);
    }

    private static AdHocActionService service(CaseRepository cases, EngineGateway engine,
                                              PlanItemRepository planItems, CaseTaskRepository tasks,
                                              EngineOperationService operations,
                                              CaseDefinitionRelease release) {
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        ParticipantRepository participants = mock(ParticipantRepository.class);
        when(bindings.find("definition:1")).thenReturn(Optional.of(binding()));
        when(releases.require("contract:1", "tenant-a")).thenReturn(release);
        when(participants.rolesOf("case-1", "alice", List.of("handlers")))
                .thenReturn(Set.of("handler"));
        return new AdHocActionService(cases, bindings, releases, participants,
                planItems, tasks,
                mock(LinkedProcessService.class), engine, mock(CriterionEvaluator.class),
                mock(EventPublisher.class), operations,
                new org.casemgmt.release.JsonSchemaCaseContractValidator(), new FormValidator());
    }

    private static CaseDefinitionRelease contract(String suffix) {
        String json = "{\"key\":\"definition\",\"orchestrationMode\":\"BPMN\","
                + "\"fields\":{},\"forms\":{}," + suffix.substring(1);
        return release(json);
    }

    private static CaseDefinitionRelease release(String json) {
        return new CaseDefinitionRelease("contract:1", "definition", "tenant-a",
                ReleaseKind.CONTRACT, "application/json", json.getBytes(StandardCharsets.UTF_8),
                "sha", ReleaseStatus.ACTIVE, null, null, null, null, null, null,
                OffsetDateTime.parse("2026-08-30T12:00:00Z"), "author");
    }

    private static CaseDefinitionVersionBinding binding() {
        return new CaseDefinitionVersionBinding("definition:1", "definition", "tenant-a",
                "orchestration:1", "orchestration-sha", "contract:1", "sha",
                "presentation:1", "presentation-sha", ReleaseStatus.ACTIVE,
                OrchestrationMode.BPMN, BindingStatus.ACTIVE, null, null,
                OffsetDateTime.parse("2026-08-30T12:00:00Z"),
                OffsetDateTime.parse("2026-08-30T12:00:00Z"), null, "author");
    }

    private static CaseInstance instance(CaseState state) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T12:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Case", state, CasePriority.MEDIUM, null,
                null, "alice", "NONE", null, null, Map.of(), 4L, now, now, null);
    }
}
