package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.orchestration.CaseOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.rules.StageCompletion;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseServiceStartableDefinitionTest {

    @Test
    void createsFromTheLatestStartableVersionInsteadOfANewerUnavailableBpmnVersion() {
        CaseDefinition activeV1 = definition("t1:invoice:1", 1);
        CaseDefinition unavailableV2 = definition("t1:invoice:2", 2);
        CaseDefinitionRepository definitions = mock(CaseDefinitionRepository.class);
        when(definitions.findLatestStartable("invoice", "t1"))
                .thenReturn(Optional.of(activeV1));
        // If CaseService regresses to the old selector, this newer row will be chosen and the
        // observable caseDefinitionId/version assertions below fail.
        when(definitions.findLatest("invoice", "t1"))
                .thenReturn(Optional.of(unavailableV2));
        when(definitions.require(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(activeV1.id()) ? activeV1 : unavailableV2);

        AtomicReference<CaseInstance> stored = new AtomicReference<>();
        CaseRepository cases = mock(CaseRepository.class);
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(cases).insert(any());
        when(cases.require(anyString())).thenAnswer(invocation -> stored.get());

        PlanItemRepository planItems = mock(PlanItemRepository.class);
        when(planItems.findByCase(anyString())).thenReturn(List.of());
        CaseOrchestration orchestration = mock(CaseOrchestration.class);
        when(orchestration.mode()).thenReturn(OrchestrationMode.BPMN);
        when(orchestration.initialItems(anyString(), any())).thenReturn(List.of());
        when(orchestration.evaluate(any())).thenReturn(List.of());
        when(orchestration.repeatable(any())).thenReturn(List.of());

        CaseService service = new CaseService(cases, definitions, planItems,
                mock(MilestoneRepository.class), mock(ParticipantRepository.class),
                new CaseOrchestrationRegistry(List.of(orchestration)), new StageCompletion(),
                mock(TransitionApplier.class), mock(EventPublisher.class), "engine-a");

        CaseInstance created = service.create("invoice", "t1", "BK-1", "Invoice",
                CasePriority.MEDIUM, Map.of(), new Actor("alice", List.of()));

        assertThat(created.caseDefId()).isEqualTo("t1:invoice:1");
        assertThat(created.caseDefVersion()).isEqualTo(1);
    }

    private static CaseDefinition definition(String id, int version) {
        return new CaseDefinition(id, "invoice", version, "Invoice", "t1", null, null,
                List.of(), List.of(), Map.of(), List.of(), OrchestrationMode.BPMN,
                OffsetDateTime.now(), "alice");
    }
}
