package org.casemgmt.service;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.MessageCorrelationRequest;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.rules.CriterionEvaluator;
import org.casemgmt.rules.EvaluationContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdHocActionServiceTest {

    @Test
    void embeddedProcessActionStartsThePinnedReleaseWithOnlyDeclaredMappings() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        CaseInstance active = instance(CaseState.ACTIVE, Map.of("decision", "approved"));
        when(cases.require(active.id())).thenReturn(active);
        when(engine.startProcess(any())).thenReturn(new EngineProcessRef("process-42", "definition:5",
                "child-process", active.id()));
        AdHocActionService service = service(cases, engine, null, release("""
                {"key":"definition","orchestrationMode":"BPMN",
                 "fields":{"decision":{"schema":{"type":"string"}}},"forms":{},
                 "adHocActions":[{"id":"launch","type":"PROCESS","roles":["handler"],
                   "orchestrationReleaseId":"orchestration:1","processDefinitionKey":"child-process",
                   "mappings":[{"direction":"CASE_TO_ENGINE","source":"decision","target":"decisionCode"}]}]}
                """), mock(CriterionEvaluator.class), mock(LinkedProcessService.class), orchestrationRelease());

        AdHocActionService.Result result = service.execute(active.id(), "launch", 4L, Map.of(),
                new Actor("alice", List.of("handlers")));

        org.mockito.ArgumentCaptor<StartProcessRequest> request =
                org.mockito.ArgumentCaptor.forClass(StartProcessRequest.class);
        verify(engine).startProcess(request.capture());
        assertThat(request.getValue().processDefinitionId()).isEqualTo("definition:5");
        assertThat(request.getValue().processDefinitionKey()).isEqualTo("child-process");
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(request.getValue().variables()).containsExactlyEntriesOf(Map.of("decisionCode", "approved"));
        assertThat(result.linkedProcessId()).isEqualTo("process-42");
        assertThat(result.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void embeddedMessageActionCorrelatesItsDeclaredMessageWithMappedCaseData() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        CaseInstance active = instance(CaseState.ACTIVE, Map.of("reference", "R-42"));
        when(cases.require(active.id())).thenReturn(active);
        AdHocActionService service = service(cases, engine, null, release("""
                {"key":"definition","orchestrationMode":"BPMN",
                 "fields":{"reference":{"schema":{"type":"string"}}},"forms":{},
                 "adHocActions":[{"id":"notify","type":"MESSAGE","roles":["handler"],
                   "messageName":"CaseUpdated",
                   "mappings":[{"direction":"CASE_TO_ENGINE","source":"reference","target":"caseReference"}]}]}
                """), mock(CriterionEvaluator.class), mock(LinkedProcessService.class), null);

        AdHocActionService.Result result = service.execute(active.id(), "notify", 4L, Map.of(),
                new Actor("alice", List.of("handlers")));

        org.mockito.ArgumentCaptor<MessageCorrelationRequest> request =
                org.mockito.ArgumentCaptor.forClass(MessageCorrelationRequest.class);
        verify(engine).correlateMessage(request.capture());
        assertThat(request.getValue().caseId()).isEqualTo(active.id());
        assertThat(request.getValue().messageName()).isEqualTo("CaseUpdated");
        assertThat(request.getValue().variables()).containsExactlyEntriesOf(Map.of("caseReference", "R-42"));
        assertThat(result.type()).isEqualTo("MESSAGE");
        assertThat(result.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void remoteMessageActionRejectsAStaleCorrelationWhenItChangesWhileTheCaseLockIsHeld() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        CaseInstance beforeLock = instance(CaseState.ACTIVE, Map.of("reference", "original"));
        CaseInstance afterLock = instance(CaseState.ACTIVE, Map.of("reference", "changed"));
        when(cases.require(beforeLock.id())).thenReturn(beforeLock, afterLock);
        when(engine.defersTaskMutations()).thenReturn(true);
        AdHocActionService service = service(cases, engine, operations, release("""
                {"key":"definition","orchestrationMode":"BPMN","fields":{},
                 "forms":{"message":{"schema":{"type":"object","properties":{"reference":{"type":"string"}}}}},
                 "adHocActions":[{"id":"notify","type":"MESSAGE","roles":["handler"],"formRef":"message",
                  "messageName":"CaseUpdated","correlationKeys":["reference"]}]}
                """), mock(CriterionEvaluator.class), mock(LinkedProcessService.class));

        assertThatThrownBy(() -> service.execute(beforeLock.id(), "notify", 4L,
                Map.of("reference", "original"), new Actor("alice", List.of("handlers")), "request-1"))
                .isInstanceOf(CaseConflictException.class)
                .extracting(error -> ((CaseConflictException) error).code())
                .isEqualTo("message-correlation-mismatch");

        verify(cases).lockForAdHocAction(beforeLock.id());
        verify(operations, never()).submitAdHoc(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void remoteProcessActionRechecksAvailabilityAfterTheCaseLockBeforeCreatingACommandOrLink() {
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        LinkedProcessService processes = mock(LinkedProcessService.class);
        CriterionEvaluator criteria = mock(CriterionEvaluator.class);
        CaseInstance beforeLock = instance(CaseState.ACTIVE, Map.of("eligible", true));
        CaseInstance afterLock = instance(CaseState.ACTIVE, Map.of("eligible", false));
        when(cases.require(beforeLock.id())).thenReturn(beforeLock, afterLock);
        when(engine.defersTaskMutations()).thenReturn(true);
        when(criteria.matches(eq("${eligible}"), any(EvaluationContext.class)))
                .thenAnswer(invocation -> Boolean.TRUE.equals(
                        invocation.<EvaluationContext>getArgument(1).variables().get("eligible")));
        AdHocActionService service = service(cases, engine, operations, release("""
                {"key":"definition","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "adHocActions":[{"id":"launch","type":"PROCESS","roles":["handler"],
                   "availabilityExpression":"${eligible}","processDefinitionKey":"child",
                   "orchestrationReleaseId":"orchestration:1"}]}
                """), criteria, processes);

        assertThatThrownBy(() -> service.execute(beforeLock.id(), "launch", 4L, Map.of(),
                new Actor("alice", List.of("handlers")), "request-1"))
                .isInstanceOf(CaseConflictException.class)
                .extracting(error -> ((CaseConflictException) error).code())
                .isEqualTo("ad-hoc-action-unavailable");

        verify(cases).lockForAdHocAction(beforeLock.id());
        verifyNoInteractions(processes);
        verify(operations, never()).submitAdHoc(any(), any(), any(), any(), any(), anyLong(), any(), any());
        assertThat(beforeLock.variables()).containsEntry("eligible", true);
    }

    private static AdHocActionService service(CaseRepository cases, EngineGateway engine,
                                              EngineOperationService operations,
                                              CaseDefinitionRelease release,
                                              CriterionEvaluator criteria,
                                              LinkedProcessService processes) {
        return service(cases, engine, operations, release, criteria, processes, null);
    }

    private static AdHocActionService service(CaseRepository cases, EngineGateway engine,
                                              EngineOperationService operations,
                                              CaseDefinitionRelease release,
                                              CriterionEvaluator criteria,
                                              LinkedProcessService processes,
                                              CaseDefinitionRelease orchestrationRelease) {
        CaseDefinitionVersionBindingRepository bindings = mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        ParticipantRepository participants = mock(ParticipantRepository.class);
        when(bindings.find("definition:1")).thenReturn(Optional.of(binding()));
        when(releases.require("contract:1", "tenant-a")).thenReturn(release);
        if (orchestrationRelease != null) {
            when(releases.require(orchestrationRelease.id(), "tenant-a")).thenReturn(orchestrationRelease);
        }
        when(participants.rolesOf("case-1", "alice", List.of("handlers"))).thenReturn(Set.of("handler"));
        return new AdHocActionService(cases, bindings, releases, participants, processes, engine, criteria,
                mock(org.casemgmt.event.EventPublisher.class), operations,
                new org.casemgmt.release.JsonSchemaCaseContractValidator(), new FormValidator());
    }

    private static CaseDefinitionRelease release(String json) {
        return new CaseDefinitionRelease("contract:1", "definition", "tenant-a", ReleaseKind.CONTRACT,
                "application/json", json.getBytes(StandardCharsets.UTF_8), "sha", ReleaseStatus.ACTIVE,
                null, null, null, null, null, null, OffsetDateTime.parse("2026-08-30T12:00:00Z"), "author");
    }

    private static CaseDefinitionRelease orchestrationRelease() {
        return new CaseDefinitionRelease("orchestration:1", "definition", "tenant-a",
                ReleaseKind.ORCHESTRATION, "application/bpmn+xml", new byte[0], "sha",
                ReleaseStatus.ACTIVE, "deployment-1", "definition:5", "child-process", 5,
                "tenant-a", null, OffsetDateTime.parse("2026-08-30T12:00:00Z"), "author");
    }

    private static CaseDefinitionVersionBinding binding() {
        return new CaseDefinitionVersionBinding("definition:1", "definition", "tenant-a",
                "orchestration:1", "orchestration-sha", "contract:1", "sha", "presentation:1",
                "presentation-sha", ReleaseStatus.ACTIVE, OrchestrationMode.BPMN, BindingStatus.ACTIVE,
                null, null, OffsetDateTime.parse("2026-08-30T12:00:00Z"),
                OffsetDateTime.parse("2026-08-30T12:00:00Z"), null, "author");
    }

    private static CaseInstance instance(CaseState state, Map<String, Object> variables) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T12:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1", "definition", 1,
                null, "Case", state, CasePriority.MEDIUM, null, null, "alice", "NONE", null, null,
                variables, 4L, now, now, null);
    }
}
