package org.casemgmt.rest.controller;

import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.BindingResponseFields;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CombinedCaseDefinitionDeploymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2/case-definitions")
public class CombinedCaseDefinitionController {

    private final CombinedCaseDefinitionDeploymentService service;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public CombinedCaseDefinitionController(CombinedCaseDefinitionDeploymentService service,
                                            ActionPolicy policy, CallerResolver callers) {
        this.service = service;
        this.policy = policy;
        this.callers = callers;
    }

    @PostMapping(consumes = "application/zip", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deploy(
            @RequestBody byte[] archive, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "deploy-case-definition");
        String tenantId = callers.requireTenant(actor, null);
        var binding = service.deploy(tenantId, archive, actor.userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseDefinitionId", binding.caseDefinitionId());
        body.put("orchestrationMode", "BPMN");
        BindingResponseFields.put(body, binding, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
