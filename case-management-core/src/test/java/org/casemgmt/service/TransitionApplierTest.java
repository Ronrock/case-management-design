package org.casemgmt.service;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.definition;
import static org.casemgmt.rules.PlanModelFixtures.item;
import static org.casemgmt.rules.PlanModelFixtures.snapshot;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransitionApplierTest {

    @Test
    void pendingProcessTaskPersistsCorrelationWithoutAPlaceholderProcessInstanceId() {
        PlanItemDefinition processDefinition = new PlanItemDefinition(
                "pd-generate", "d:1", "generate", PlanItemType.PROCESS_TASK,
                "generate", null, false, false, false, List.of(), List.of(),
                null, "letter-process", List.of(), 10);
        var processItem = item(
                "plan-item-1", "generate", PlanItemType.PROCESS_TASK, PlanItemState.ACTIVE);
        CaseSnapshot caseSnapshot = snapshot(definition(processDefinition), List.of(processItem),
                Map.of("customer", "C-1"));
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        when(engine.startProcessByKey(any()))
                .thenReturn(new EngineProcessRef(null, "letter-process", "eng-a:1"));
        EventPublisher events = mock(EventPublisher.class);
        when(events.engineId()).thenReturn("eng-test");
        TransitionApplier applier = new TransitionApplier(
                mock(PlanItemRepository.class), mock(CaseTaskRepository.class), processes,
                mock(MilestoneRepository.class), engine, events);

        applier.sideEffects(caseSnapshot,
                new Transition(processItem.id(), PlanItemState.AVAILABLE,
                        PlanItemState.ACTIVE, "entry criterion"),
                processItem, new Actor("alice", List.of()));

        ArgumentCaptor<String> correlationId = ArgumentCaptor.forClass(String.class);
        verify(processes).insert(correlationId.capture(), eq("eng-a:1"), eq("plan-item-1"),
                eq(null), eq("letter-process"), eq(CaseTask.EngineSync.PENDING));
        ArgumentCaptor<StartProcessByKeyRequest> request =
                ArgumentCaptor.forClass(StartProcessByKeyRequest.class);
        verify(engine).startProcessByKey(request.capture());
        assertThat(request.getValue().correlationId()).isEqualTo(correlationId.getValue());
    }
}
