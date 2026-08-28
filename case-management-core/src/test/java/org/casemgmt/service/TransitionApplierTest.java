package org.casemgmt.service;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventTypes;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                eq(null), eq(null), eq("letter-process"), eq(CaseTask.EngineSync.PENDING));
        ArgumentCaptor<StartProcessByKeyRequest> request =
                ArgumentCaptor.forClass(StartProcessByKeyRequest.class);
        verify(engine).startProcessByKey(request.capture());
        assertThat(request.getValue().correlationId()).isEqualTo(correlationId.getValue());
        var ordered = inOrder(processes, engine);
        ordered.verify(processes).insert(correlationId.getValue(), "eng-a:1", "plan-item-1",
                null, null, "letter-process", CaseTask.EngineSync.PENDING);
        ordered.verify(engine).startProcessByKey(any());
        ArgumentCaptor<CaseEvent> published = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events, times(2)).publish(published.capture());
        assertThat(published.getAllValues()).extracting(CaseEvent::type)
                .containsExactly(EventTypes.PROCESS_STARTED, EventTypes.PLAN_ITEM_TRANSITIONED);
        verify(events).audit(eq("eng-a:1"), eq("t1"), eq("alice"), eq("process.start"),
                eq("LinkedProcess"), eq(correlationId.getValue()), eq(null), any());
    }

    @Test
    void synchronousObservationOwnerSuppressesOnlyTheParallelProcessStartEffects() {
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
        when(engine.emitsSynchronousLifecycleObservations()).thenReturn(true);
        when(engine.startProcessByKey(any())).thenReturn(new EngineProcessRef(
                "process-42", "letter-process:9", "letter-process", "eng-a:1"));
        EventPublisher events = mock(EventPublisher.class);
        when(events.engineId()).thenReturn("eng-test");
        TransitionApplier applier = new TransitionApplier(
                mock(PlanItemRepository.class), mock(CaseTaskRepository.class), processes,
                mock(MilestoneRepository.class), engine, events);

        applier.sideEffects(caseSnapshot,
                new Transition(processItem.id(), PlanItemState.AVAILABLE,
                        PlanItemState.ACTIVE, "entry criterion"),
                processItem, new Actor("alice", List.of()));

        ArgumentCaptor<CaseEvent> published = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().type()).isEqualTo(EventTypes.PLAN_ITEM_TRANSITIONED);
        verify(events, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
