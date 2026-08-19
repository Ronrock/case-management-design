package org.casemgmt.permissions;

import org.casemgmt.error.AuthorizationDeniedException;
import org.casemgmt.error.AuthorizationUnavailableException;
import org.casemgmt.service.Actor;

import java.util.List;
import java.util.Map;

public class WorkerPermissionEvaluator {

    private final WorkerPermissionsClient client;

    public WorkerPermissionEvaluator(WorkerPermissionsClient client) {
        this.client = client;
    }

    public void assertAllowed(Actor actor, String tenantId, String action, String resourceType,
                              String resourceId, Map<String, Object> context) {
        if (!isAllowed(actor, tenantId, action, resourceType, resourceId, context)) {
            throw new AuthorizationDeniedException("Worker '" + actor.userId()
                    + "' is not allowed to perform '" + action + "' on " + resourceType
                    + " '" + resourceId + "'");
        }
    }

    public boolean allowedOrFalse(Actor actor, String tenantId, String action, String resourceType,
                                  String resourceId, Map<String, Object> context) {
        try {
            return isAllowed(actor, tenantId, action, resourceType, resourceId, context);
        } catch (AuthorizationUnavailableException e) {
            return false;
        }
    }

    public Map<String, PermissionDecision> evaluate(Actor actor, String tenantId, String action,
                                                    String resourceType,
                                                    List<WorkerPermissionResource> resources) {
        try {
            return client.evaluate(new WorkerPermissionRequest(tenantId, actor.userId(),
                    actor.groups(), action, resourceType, resources));
        } catch (RuntimeException e) {
            throw new AuthorizationUnavailableException("Authorization is unavailable", e);
        }
    }

    private boolean isAllowed(Actor actor, String tenantId, String action, String resourceType,
                              String resourceId, Map<String, Object> context) {
        Map<String, PermissionDecision> decisions = evaluate(actor, tenantId, action, resourceType,
                List.of(new WorkerPermissionResource(resourceId, context)));
        return decisions.getOrDefault(resourceId, PermissionDecision.deny(resourceId)).allowed();
    }
}
