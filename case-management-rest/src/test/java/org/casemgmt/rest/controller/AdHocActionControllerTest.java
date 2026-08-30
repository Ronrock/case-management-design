package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.service.Actor;
import org.casemgmt.service.AdHocActionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdHocActionControllerTest {

    @Test
    void remoteActionReturnsAcceptedOperationLocation() {
        AdHocActionService actions = mock(AdHocActionService.class);
        CaseRepository cases = mock(CaseRepository.class);
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = new Actor("alice", List.of("tenant-a"));
        CaseInstance c = instance();
        when(callers.actor(authentication)).thenReturn(actor);
        when(cases.require(c.id())).thenReturn(c);
        when(actions.execute(any(), any(), anyLong(), any(), any(), any())).thenReturn(
                new AdHocActionService.Result("letter", "PROCESS", null, null, "link-1",
                        CaseTask.EngineSync.PENDING, "operation-1", "PENDING"));
        AdHocActionController controller = new AdHocActionController(actions, cases, callers,
                mock(WorkerPermissionEvaluator.class));

        ResponseEntity<Map<String, Object>> response = controller.execute(c.id(), "letter", "\"4\"",
                "idem-1", Map.of(), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/case-api/v2/operations/operation-1");
    }

    private static CaseInstance instance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T12:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Case", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, null, Map.of(), 4L, now, now, null);
    }
}
