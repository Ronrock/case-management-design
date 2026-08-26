package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.service.Actor;
import org.casemgmt.service.AdHocActionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/ad-hoc-actions")
public class AdHocActionController {

    private final AdHocActionService actions;
    private final CaseRepository cases;
    private final CallerResolver callers;
    private final WorkerPermissionEvaluator permissions;

    public AdHocActionController(AdHocActionService actions, CaseRepository cases,
                                 CallerResolver callers, WorkerPermissionEvaluator permissions) {
        this.actions = actions;
        this.cases = cases;
        this.callers = callers;
        this.permissions = permissions;
    }

    @PostMapping("/{actionId}")
    public ResponseEntity<Map<String, Object>> execute(
            @PathVariable String caseId, @PathVariable String actionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> input,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = cases.require(caseId);
        callers.requireVisible("Case", caseId, c.tenantId(), actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.CASE_UPDATE,
                ResourceTypes.CASE, c.id(), Map.of("caseId", c.id(), "actionId", actionId));
        long expected = ETagSupport.expectedVersion(ifMatch, "case " + caseId,
                () -> OptionalLong.of(c.version()));
        AdHocActionService.Result result = actions.execute(caseId, actionId, expected,
                input == null ? Map.of() : input, actor);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", result.actionId());
        body.put("type", result.type());
        body.put("planItemId", result.planItemId());
        body.put("taskId", result.taskId());
        body.put("linkedProcessId", result.linkedProcessId());
        body.put("engineSync", result.engineSync().name());
        body.put("status", result.engineSync() == CaseTask.EngineSync.PENDING
                ? "PENDING" : "CURRENT");
        HttpStatus status = result.engineSync() == CaseTask.EngineSync.PENDING
                ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }
}
