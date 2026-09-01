package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.TaskState;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.EngineOperationResponse;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseTaskService;
import org.casemgmt.service.EngineOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaskOperationControllerTest {

    @Test
    void remoteClaimReturnsAcceptedOperationAndLocation() {
        CaseTaskService tasks = mock(CaseTaskService.class);
        CaseTaskRepository taskRepo = mock(CaseTaskRepository.class);
        CaseRepository caseRepo = mock(CaseRepository.class);
        CallerResolver callers = mock(CallerResolver.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        CaseTask task = task();
        CaseInstance instance = instance();
        EngineOperationService.Operation operation = new EngineOperationService.Operation(
                "operation-1", "command-1", task.caseId(), "CLAIM_TASK", task.engineTaskId(),
                "PENDING", 0L, null, null, List.of("cancel"));
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.tenantId(actor)).thenReturn("tenant-a");
        when(callers.roles(task.caseId(), actor)).thenReturn(Set.of("owner"));
        when(callers.groups(actor)).thenReturn(Set.of("tenant-a"));
        when(taskRepo.require(task.id())).thenReturn(task);
        when(taskRepo.findById(task.id())).thenReturn(Optional.of(task));
        when(caseRepo.require(task.caseId())).thenReturn(instance);
        when(caseRepo.findById(task.caseId())).thenReturn(Optional.of(instance));
        when(operations.hasActiveCommand("tenant-a", task)).thenReturn(false);
        when(tasks.claimOperation(task.id(), task.version(), actor, "idem-1"))
                .thenReturn(new CaseTaskService.TaskOperation(task, operation));
        TaskController controller = new TaskController(tasks, taskRepo, caseRepo,
                mock(ActionPolicy.class), callers, mock(WorkerPermissionEvaluator.class), operations);

        ResponseEntity<?> response = controller.claim(task.id(), "\"7\"", "idem-1", authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation()).hasToString("/case-api/v2/operations/operation-1");
        assertThat(response.getBody()).isInstanceOf(EngineOperationResponse.class);
        assertThat(((EngineOperationResponse) response.getBody()).status()).isEqualTo("PENDING");
    }

    @Test
    void activeRemoteClaimWithTheSameIdempotencyKeyReplaysTheOriginalOperation() {
        CaseTaskService tasks = mock(CaseTaskService.class);
        CaseTaskRepository taskRepo = mock(CaseTaskRepository.class);
        CaseRepository caseRepo = mock(CaseRepository.class);
        CallerResolver callers = mock(CallerResolver.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        CaseTask task = task();
        CaseInstance instance = instance();
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.tenantId(actor)).thenReturn("tenant-a");
        when(callers.roles(task.caseId(), actor)).thenReturn(Set.of("owner"));
        when(callers.groups(actor)).thenReturn(Set.of("tenant-a"));
        when(taskRepo.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepo.require(task.id())).thenReturn(task);
        when(caseRepo.findById(task.caseId())).thenReturn(Optional.of(instance));
        when(caseRepo.require(task.caseId())).thenReturn(instance);
        when(operations.hasActiveCommand("tenant-a", task)).thenReturn(true);
        EngineOperationService.Operation operation = new EngineOperationService.Operation(
                "operation-1", "command-1", task.caseId(), "CLAIM_TASK", task.engineTaskId(),
                "PENDING", 0L, null, null, List.of("cancel"));
        when(tasks.claimOperation(task.id(), task.version(), actor, "idem-1"))
                .thenReturn(new CaseTaskService.TaskOperation(task, operation));
        TaskController controller = new TaskController(tasks, taskRepo, caseRepo,
                mock(ActionPolicy.class), callers, mock(WorkerPermissionEvaluator.class), operations);

        ResponseEntity<?> response = controller.claim(task.id(), "\"7\"", "idem-1", authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation()).hasToString("/case-api/v2/operations/operation-1");
        verify(tasks).claimOperation(task.id(), task.version(), actor, "idem-1");
    }

    @Test
    void remoteCompleteReturnsAcceptedWithoutClaimingCompletion() {
        CaseTaskService tasks = mock(CaseTaskService.class);
        CaseTaskRepository taskRepo = mock(CaseTaskRepository.class);
        CaseRepository caseRepo = mock(CaseRepository.class);
        CallerResolver callers = mock(CallerResolver.class);
        EngineOperationService operations = mock(EngineOperationService.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        CaseTask task = task(TaskState.CLAIMED, "alice");
        CaseInstance instance = instance();
        EngineOperationService.Operation operation = new EngineOperationService.Operation(
                "operation-2", "command-2", task.caseId(), "COMPLETE_TASK", task.engineTaskId(),
                "PENDING", 0L, null, null, List.of("cancel"));
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.tenantId(actor)).thenReturn("tenant-a");
        when(callers.roles(task.caseId(), actor)).thenReturn(Set.of("owner"));
        when(callers.groups(actor)).thenReturn(Set.of("tenant-a"));
        when(taskRepo.require(task.id())).thenReturn(task);
        when(taskRepo.findById(task.id())).thenReturn(Optional.of(task));
        when(caseRepo.require(task.caseId())).thenReturn(instance);
        when(caseRepo.findById(task.caseId())).thenReturn(Optional.of(instance));
        when(operations.hasActiveCommand("tenant-a", task)).thenReturn(false);
        when(tasks.completeOperation(eq(task.id()), eq(task.version()), eq(Map.of("outcome", "approve")),
                eq(actor), eq("idem-2"))).thenReturn(new CaseTaskService.TaskOperation(task, operation));
        TaskController controller = new TaskController(tasks, taskRepo, caseRepo,
                mock(ActionPolicy.class), callers, mock(WorkerPermissionEvaluator.class), operations);

        ResponseEntity<?> response = controller.complete(task.id(), "\"7\"", "idem-2",
                new org.casemgmt.rest.dto.Dtos.CompleteTaskRequest(Map.of("outcome", "approve")),
                authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(((EngineOperationResponse) response.getBody()).commandType())
                .isEqualTo("COMPLETE_TASK");
    }

    private static CaseTask task() {
        return task(TaskState.OPEN, null);
    }

    private static CaseTask task(TaskState state, String assignee) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseTask("task-1", "case-1", "item-1", "engine-task-1", "Review",
                null, state, assignee, null, List.of("handlers"), null, 50, null, null,
                CaseTask.EngineSync.SYNCED, 7L, now, now, null);
    }

    private static CaseInstance instance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Example", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, null, Map.of(), 3L, now, now, null);
    }
}
