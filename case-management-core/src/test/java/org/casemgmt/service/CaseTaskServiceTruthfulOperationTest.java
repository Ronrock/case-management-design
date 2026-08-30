package org.casemgmt.service;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.CaseTask.EngineSync;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.TaskState;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseTaskServiceTruthfulOperationTest {

    @Test
    void remoteClaimRecordsRequestedIntentWithoutChangingConfirmedTask() {
        CaseTaskRepository tasks = mock(CaseTaskRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        CaseTask task = task(TaskState.OPEN, null);
        CaseInstance instance = instance();
        when(tasks.require(task.id())).thenReturn(task);
        when(cases.require(task.caseId())).thenReturn(instance);
        when(engine.defersTaskMutations()).thenReturn(true);
        EngineOperationService.Operation operation = new EngineOperationService.Operation(
                "operation-1", "command-1", task.caseId(), "CLAIM_TASK", task.engineTaskId(),
                "PENDING", task.version(), null, null, List.of("reconcile", "cancel"));
        when(operations.submitClaim(eq(instance), eq(task), eq(7L),
                eq(new Actor("alice", List.of())), eq("idem-1"))).thenReturn(operation);
        CaseTaskService service = new CaseTaskService(tasks, cases,
                mock(CaseDefinitionRepository.class), engine, new FormValidator(),
                mock(EventPublisher.class), operations);

        CaseTaskService.TaskOperation result = service.claimOperation(
                task.id(), 7L, new Actor("alice", List.of()), "idem-1");

        assertThat(result.confirmedTask()).isEqualTo(task);
        assertThat(result.operation()).isEqualTo(operation);
        verify(engine, never()).claimTask(any(), any());
        verify(tasks, never()).update(any(), any(Long.class));
    }

    @Test
    void embeddedClaimRemainsConfirmedAndSynchronous() {
        CaseTaskRepository tasks = mock(CaseTaskRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        CaseTask task = task(TaskState.OPEN, null);
        CaseTask claimed = task(TaskState.CLAIMED, "alice");
        when(tasks.require(task.id())).thenReturn(task);
        when(cases.require(task.caseId())).thenReturn(instance());
        when(tasks.update(any(), eq(7L))).thenReturn(claimed);
        CaseTaskService service = new CaseTaskService(tasks, cases,
                mock(CaseDefinitionRepository.class), engine, new FormValidator(),
                mock(EventPublisher.class));

        CaseTaskService.TaskOperation result = service.claimOperation(
                task.id(), 7L, new Actor("alice", List.of()), "ignored");

        assertThat(result.operation()).isNull();
        assertThat(result.confirmedTask()).isEqualTo(claimed);
        verify(engine).claimTask(task.engineTaskId(), "alice");
        verify(tasks).update(any(), eq(7L));
    }

    private static CaseTask task(TaskState state, String assignee) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseTask("task-1", "case-1", "item-1", "engine-task-1", "Review",
                null, state, assignee, null, List.of("handlers"), null, 50, null, null,
                EngineSync.SYNCED, 7L, now, now, null);
    }

    private static CaseInstance instance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Example", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, null, Map.of(), 3L, now, now, null);
    }
}
