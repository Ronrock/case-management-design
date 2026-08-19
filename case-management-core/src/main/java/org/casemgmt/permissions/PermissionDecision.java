package org.casemgmt.permissions;

import java.util.List;

public record PermissionDecision(String resourceId, boolean allowed, List<String> allowedFields) {

    public PermissionDecision {
        allowedFields = allowedFields == null ? List.of() : List.copyOf(allowedFields);
    }

    public static PermissionDecision allow(String resourceId) {
        return new PermissionDecision(resourceId, true, List.of("*"));
    }

    public static PermissionDecision deny(String resourceId) {
        return new PermissionDecision(resourceId, false, List.of());
    }
}
