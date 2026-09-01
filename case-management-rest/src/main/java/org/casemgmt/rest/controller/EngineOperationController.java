package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.EngineOperationResponse;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.EngineOperationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.OptionalLong;

/** Tenant-safe inspection and administrator-only recovery of durable engine operations. */
@RestController
@RequestMapping("/case-api/v2/operations")
public class EngineOperationController {

    public record SupportRequest(String actionId, String auditReference, String evidenceReference) { }

    private final EngineOperationService operations;
    private final CaseRepository cases;
    private final CallerResolver callers;
    private final ActionPolicy policy;
    private final WorkerPermissionEvaluator permissions;

    public EngineOperationController(EngineOperationService operations, CaseRepository cases,
                                     CallerResolver callers, ActionPolicy policy,
                                     WorkerPermissionEvaluator permissions) {
        this.operations = operations;
        this.cases = cases;
        this.callers = callers;
        this.policy = policy;
        this.permissions = permissions;
    }

    @GetMapping("/{operationId}")
    public ResponseEntity<EngineOperationResponse> get(@PathVariable String operationId,
                                                        Authentication authentication) {
        EngineOperationService.Operation operation = visible(operationId, callers.actor(authentication));
        return ResponseEntity.ok().eTag(ETagSupport.format(operation.version()))
                .body(EngineOperationResponse.of(operation));
    }

    @PostMapping("/{operationId}/{action}")
    public ResponseEntity<EngineOperationResponse> support(
            @PathVariable String operationId, @PathVariable String action,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) SupportRequest request,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        EngineOperationService.Operation current = visible(operationId, actor);
        policy.assertMayAdminister(callers.groups(actor), "support-engine-operation");
        EngineOperationService.SupportAction requested = parse(action);
        if (!current.availableActions().contains(action.toLowerCase(java.util.Locale.ROOT))) {
            throw new CaseConflictException("operation-action-not-available",
                    "Action '" + action + "' is not available on operation " + operationId,
                    current.availableActions());
        }
        long version = ETagSupport.expectedVersion(ifMatch, "operation " + operationId,
                () -> OptionalLong.of(current.version()));
        SupportRequest safe = request == null ? new SupportRequest(null, null, null) : request;
        EngineOperationService.Operation updated = operations.support(callers.tenantId(actor),
                operationId, version, requested, actor,
                required(safe.actionId(), "actionId"), required(safe.auditReference(), "auditReference"),
                safe.evidenceReference());
        return ResponseEntity.ok().eTag(ETagSupport.format(updated.version()))
                .body(EngineOperationResponse.of(updated));
    }

    private EngineOperationService.Operation visible(String operationId, Actor actor) {
        String tenant = callers.tenantId(actor);
        EngineOperationService.Operation operation = operations.find(tenant, operationId)
                .orElseThrow(() -> new NotFoundException("EngineOperation", operationId));
        CaseInstance instance = cases.require(operation.caseId());
        callers.requireVisible("EngineOperation", operationId, instance.tenantId(), actor);
        permissions.assertAllowed(actor, tenant, PermissionActions.CASE_READ, ResourceTypes.CASE,
                instance.id(), Map.of("caseDefinitionKey", instance.caseDefKey(),
                        "state", instance.state().name()));
        return operation;
    }

    private static EngineOperationService.SupportAction parse(String action) {
        try {
            return EngineOperationService.SupportAction.valueOf(action.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            throw new org.casemgmt.rest.error.InvalidRequestException("Unsupported operation action: " + action);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new org.casemgmt.rest.error.InvalidRequestException(field + " is required");
        }
        return value;
    }
}
