package org.casemgmt.permissions;

import java.util.LinkedHashMap;
import java.util.Map;

public record WorkerPermissionResource(String id, Map<String, Object> context) {

    public WorkerPermissionResource {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("permission resource id is required");
        }
        if (context == null || context.isEmpty()) {
            context = Map.of();
        } else {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            context.forEach((key, value) -> {
                if (key != null && value != null) {
                    cleaned.put(key, value);
                }
            });
            context = Map.copyOf(cleaned);
        }
    }
}
