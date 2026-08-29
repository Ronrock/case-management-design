package org.casemgmt.rest.controller;

import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.PlanItemResponse;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only BPMN projection of engine activities. The engine is the sole authority that changes
 * these rows; this endpoint deliberately exposes no plan-item transition commands.
 */
@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/plan-items")
public class PlanItemController {

    private final CaseService cases;
    private final CallerResolver callers;

    public PlanItemController(CaseService cases, CallerResolver callers) {
        this.cases = cases;
        this.callers = callers;
    }

    @GetMapping
    public List<PlanItemResponse> list(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseSnapshot snapshot = visibleSnapshot(caseId, actor);
        return snapshot.planItems().stream()
                .map(i -> PlanItemResponse.of(i, List.of()))
                .toList();
    }

    private CaseSnapshot visibleSnapshot(String caseId, Actor actor) {
        var instance = cases.get(caseId);
        callers.requireVisible("Case", caseId, instance.tenantId(), actor);
        return cases.snapshot(instance);
    }

}
