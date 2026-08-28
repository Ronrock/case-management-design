package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.orchestration.CaseOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.StageCompletion;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseServiceSynchronousCancellationTest {

    @Test
    void rejectsAConcurrentVersionAdvanceHiddenBehindTheSynchronousCancellationCallback() {
        CaseRepository cases = mock(CaseRepository.class);
        CaseDefinitionRepository definitions = mock(CaseDefinitionRepository.class);
        PlanItemRepository planItems = mock(PlanItemRepository.class);
        CaseOrchestration orchestration = mock(CaseOrchestration.class);
        EventPublisher events = mock(EventPublisher.class);
        CaseInstance original = instance(CaseState.ACTIVE, 4, null);
        // A root termination owns exactly one version increment. Version 6 proves another writer
        // committed after the API's original read and must not be hidden by the callback.
        CaseInstance concurrentlyAdvanced = instance(CaseState.CANCELLED, 6, null);
        CaseDefinition definition = mock(CaseDefinition.class);
        when(definition.orchestrationMode()).thenReturn(OrchestrationMode.BPMN);
        when(orchestration.mode()).thenReturn(OrchestrationMode.BPMN);
        when(cases.require("case-1")).thenReturn(original, concurrentlyAdvanced);
        when(definitions.require("definition:1")).thenReturn(definition);
        when(planItems.findByCase("case-1")).thenReturn(List.of());
        CaseService service = new CaseService(cases, definitions, planItems,
                mock(MilestoneRepository.class), mock(ParticipantRepository.class),
                new CaseOrchestrationRegistry(List.of(orchestration)),
                mock(StageCompletion.class), mock(TransitionApplier.class), events, "engine-a");

        assertThatThrownBy(() -> service.cancel("case-1", 4, "duplicate",
                new Actor("alice", List.of())))
                .isInstanceOf(OptimisticLockException.class);

        verify(cases, never()).updateCancellationReason(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(events, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    private static CaseInstance instance(CaseState state, long version, String reason) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T12:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Case", state, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, reason, Map.of(), version,
                now, now, state == CaseState.CANCELLED ? now : null);
    }
}
