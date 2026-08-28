package org.casemgmt.service;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.rules.PlanModelFixtures.caseInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

class LinkedProcessServiceTest {

    @Test
    void exactConfirmationRejectsMissingDefinitionIdentityBeforePersistence() {
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        when(cases.require("eng-a:1")).thenReturn(caseInstance(Map.of()));
        LinkedProcessService service = new LinkedProcessService(
                processes, cases, mock(EngineGateway.class), mock(EventPublisher.class));

        assertThatThrownBy(() -> service.confirmStarted(
                "eng-a:1", "correlation-1", "process-42", " ", "letter-process",
                java.time.OffsetDateTime.parse("2026-08-28T07:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");

        org.mockito.Mockito.verifyNoInteractions(processes);
    }

    @Test
    void synchronousStartPersistsExactDefinitionIdentity() {
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EventPublisher events = mock(EventPublisher.class);
        AtomicReference<String> insertedId = new AtomicReference<>();
        when(cases.require("eng-a:1")).thenReturn(caseInstance(Map.of()));
        when(engine.startProcessByKey(any())).thenReturn(new EngineProcessRef(
                "process-42", "letter-process:9", "letter-process", "eng-a:1"));
        doAnswer(invocation -> { insertedId.set(invocation.getArgument(0)); return null; })
                .when(processes).insert(any(), eq("eng-a:1"), eq(null),
                        org.mockito.ArgumentMatchers.<String>any(),
                        org.mockito.ArgumentMatchers.<String>any(),
                        eq("letter-process"), any());
        when(processes.findByCase("eng-a:1")).thenAnswer(invocation -> List.of(
                new LinkedProcessRepository.LinkedProcessRow(insertedId.get(), "eng-a:1", null,
                        insertedId.get(), "process-42", "letter-process:9", "letter-process",
                        "ACTIVE", CaseTask.EngineSync.SYNCED, false)));

        var result = new LinkedProcessService(processes, cases, engine, events).start(
                "eng-a:1", null, "letter-process", Map.of(),
                new Actor("alice", List.of()));

        assertThat(result.processDefinitionId()).isEqualTo("letter-process:9");
        InOrder ordered = inOrder(processes, engine);
        ordered.verify(processes).insert(result.id(), "eng-a:1", null, null,
                null, "letter-process", CaseTask.EngineSync.PENDING);
        ordered.verify(engine).startProcessByKey(any());
        ordered.verify(processes).confirmStarted(eq("eng-a:1"), eq(result.id()),
                eq("process-42"), eq("letter-process:9"), eq("letter-process"), any());
        verify(events).publish(org.mockito.ArgumentMatchers.argThat(event ->
                EventTypes.PROCESS_STARTED.equals(event.type())));
        verify(events).audit(eq("eng-a:1"), eq("t1"), eq("alice"), eq("process.start"),
                eq("LinkedProcess"), eq(result.id()), eq(null), any());
    }

    @Test
    void synchronousObservationOwnerSuppressesTheParallelServiceStartEffects() {
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EventPublisher events = mock(EventPublisher.class);
        AtomicReference<String> insertedId = new AtomicReference<>();
        when(cases.require("eng-a:1")).thenReturn(caseInstance(Map.of()));
        when(engine.emitsSynchronousLifecycleObservations()).thenReturn(true);
        when(engine.startProcessByKey(any())).thenReturn(new EngineProcessRef(
                "process-42", "letter-process:9", "letter-process", "eng-a:1"));
        doAnswer(invocation -> { insertedId.set(invocation.getArgument(0)); return null; })
                .when(processes).insert(any(), eq("eng-a:1"), eq(null), eq(null), eq(null),
                        eq("letter-process"), eq(CaseTask.EngineSync.PENDING));
        when(processes.findByCase("eng-a:1")).thenAnswer(invocation -> List.of(
                new LinkedProcessRepository.LinkedProcessRow(insertedId.get(), "eng-a:1", null,
                        insertedId.get(), "process-42", "letter-process:9", "letter-process",
                        "ACTIVE", CaseTask.EngineSync.SYNCED, false)));

        new LinkedProcessService(processes, cases, engine, events).start(
                "eng-a:1", null, "letter-process", Map.of(),
                new Actor("alice", List.of()));

        verifyNoInteractions(events);
    }

    @Test
    void asynchronousStartPersistsOnlyCorrelationUntilEngineConfirms() {
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EventPublisher events = mock(EventPublisher.class);
        AtomicReference<String> insertedId = new AtomicReference<>();
        when(cases.require("eng-a:1")).thenReturn(caseInstance(Map.of()));
        when(engine.startProcessByKey(any()))
                .thenReturn(new EngineProcessRef(null, "letter-process", "eng-a:1"));
        doAnswer(invocation -> {
            insertedId.set(invocation.getArgument(0));
            return null;
        }).when(processes).insert(any(), eq("eng-a:1"), eq(null), eq(null), eq(null),
                eq("letter-process"), eq(CaseTask.EngineSync.PENDING));
        when(processes.findByCase("eng-a:1")).thenAnswer(invocation -> List.of(
                new LinkedProcessRepository.LinkedProcessRow(insertedId.get(), "eng-a:1", null,
                        insertedId.get(), null, "letter-process", "ACTIVE",
                        CaseTask.EngineSync.PENDING, false)));
        LinkedProcessService service = new LinkedProcessService(processes, cases, engine, events);

        LinkedProcessRepository.LinkedProcessRow result = service.start(
                "eng-a:1", null, "letter-process", Map.of(), new Actor("alice", List.of()));

        assertThat(result.correlationId()).isEqualTo(result.id());
        assertThat(result.processInstanceId()).isNull();
        verify(processes).insert(result.id(), "eng-a:1", null, null, null,
                "letter-process", CaseTask.EngineSync.PENDING);
        verify(events).publish(org.mockito.ArgumentMatchers.argThat(event ->
                EventTypes.PROCESS_STARTED.equals(event.type())));
        verify(events).audit(eq("eng-a:1"), eq("t1"), eq("alice"), eq("process.start"),
                eq("LinkedProcess"), eq(result.id()), eq(null), any());
    }
}
