package org.casemgmt.permissions;

import java.util.List;

public record WorkerPermissionRequest(String tenantId, String workerId, List<String> groups,
                                      String action, String resourceType,
                                      List<WorkerPermissionResource> resources) {

    public WorkerPermissionRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("permission tenant is required");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("permission worker id is required");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("permission action is required");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("permission resource type is required");
        }
        groups = groups == null ? List.of() : List.copyOf(groups);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
