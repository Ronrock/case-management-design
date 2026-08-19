package org.casemgmt.permissions;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Port for the enterprise Worker Permissions API.
 *
 * <p>The case-management modules use this interface as a policy decision point client without
 * coupling core code to any ING-specific transport, token exchange or entitlement shape.
 */
@FunctionalInterface
public interface WorkerPermissionsClient {

    Map<String, PermissionDecision> evaluate(WorkerPermissionRequest request);

    static WorkerPermissionsClient denyAll() {
        return request -> request.resources().stream()
                .collect(Collectors.toMap(WorkerPermissionResource::id,
                        resource -> PermissionDecision.deny(resource.id())));
    }

    static WorkerPermissionsClient allowAll() {
        return request -> request.resources().stream()
                .collect(Collectors.toMap(WorkerPermissionResource::id,
                        resource -> PermissionDecision.allow(resource.id())));
    }
}
