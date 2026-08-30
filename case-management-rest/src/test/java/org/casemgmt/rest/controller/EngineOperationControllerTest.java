package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.EngineOperationResponse;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EngineOperationControllerTest {

    @Test
    void lookupUsesTheCallersTenantAndReturnsOnlyRedactedOperationFields() {
        EngineOperationService operations = mock(EngineOperationService.class);
        CaseRepository cases = mock(CaseRepository.class);
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        EngineOperationService.Operation operation = new EngineOperationService.Operation(
                "operation-1", "command-1", "case-1", "COMPLETE_TASK", "engine-task-1",
                "AWAITING_CONFIRMATION", 3L, "transport.possibly_sent",
                "Remote request may have been sent", List.of("reconcile", "retry"));
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.tenantId(actor)).thenReturn("tenant-a");
        when(operations.find("tenant-a", "operation-1")).thenReturn(Optional.of(operation));
        when(cases.require("case-1")).thenReturn(instance());
        EngineOperationController controller = new EngineOperationController(operations, cases,
                callers, mock(ActionPolicy.class), mock(WorkerPermissionEvaluator.class));

        ResponseEntity<EngineOperationResponse> response = controller.get("operation-1", authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).extracting(EngineOperationResponse::id,
                        EngineOperationResponse::status, EngineOperationResponse::availableActions)
                .containsExactly("operation-1", "AWAITING_CONFIRMATION", List.of("reconcile", "retry"));
        assertThat(response.getBody().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("payload", "remoteBody", "credentials", "commandId");
    }

    @Test
    void lookupDoesNotProbeAnotherTenant() {
        EngineOperationService operations = mock(EngineOperationService.class);
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.tenantId(actor)).thenReturn("tenant-a");
        when(operations.find("tenant-a", "operation-b")).thenReturn(Optional.empty());
        EngineOperationController controller = new EngineOperationController(operations,
                mock(CaseRepository.class), callers, mock(ActionPolicy.class),
                mock(WorkerPermissionEvaluator.class));

        assertThatThrownBy(() -> controller.get("operation-b", authentication))
                .hasMessageContaining("EngineOperation not found: operation-b");
    }

    @Test
    void supportActionRequiresAnAdministratorGroupBeforeItTouchesTheCommand() {
        EngineOperationService operations = mock(EngineOperationService.class);
        CaseRepository cases = mock(CaseRepository.class);
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        EngineOperationService.Operation operation = new EngineOperationService.Operation(
                "operation-1", "command-1", "case-1", "CLAIM_TASK", "engine-task-1",
                "PENDING", 0L, null, null, List.of("cancel"));
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.tenantId(actor)).thenReturn("tenant-a");
        when(callers.groups(actor)).thenReturn(Set.of("tenant-a"));
        when(operations.find("tenant-a", "operation-1")).thenReturn(Optional.of(operation));
        when(cases.require("case-1")).thenReturn(instance());
        EngineOperationController controller = new EngineOperationController(operations, cases,
                callers, new ActionPolicy(), mock(WorkerPermissionEvaluator.class));

        assertThatThrownBy(() -> controller.support("operation-1", "cancel", "\"0\"",
                new EngineOperationController.SupportRequest("a-1", "audit-1", null), authentication))
                .hasMessageContaining("support-engine-operation");

        verify(operations, never()).support(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static CaseInstance instance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Example", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, null, Map.of(), 3L, now, now, null);
    }
}
