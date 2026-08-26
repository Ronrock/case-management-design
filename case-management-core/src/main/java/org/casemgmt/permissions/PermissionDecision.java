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

    /**
     * Returns whether an allowed resource decision also permits the named response field.
     * An absent or empty field decision is deliberately fail-closed: callers must receive
     * either an explicit field name or the wildcard.
     */
    public boolean allowsField(String field) {
        return allowed && (allowedFields.contains("*") || allowedFields.contains(field));
    }
}
