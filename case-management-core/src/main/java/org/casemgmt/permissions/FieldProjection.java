package org.casemgmt.permissions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Shared fail-closed field projection used by HTTP and search representations. */
public final class FieldProjection {

    private FieldProjection() {}

    public static <T> T value(PermissionDecision decision, String field, T value) {
        return decision != null && decision.allowsField(field) ? value : null;
    }

    /**
     * A decision may grant the complete variables object, or individual canonical field IDs.
     * Both {@code customerName} and {@code variables.customerName} are accepted so permission
     * providers can use either canonical IDs or response paths without duplicating values.
     */
    public static Map<String, Object> variables(PermissionDecision decision,
                                                Map<String, Object> variables) {
        if (decision == null || variables == null || variables.isEmpty()) {
            return Map.of();
        }
        if (decision.allowsField("variables")) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        variables.forEach((field, value) -> {
            if (decision.allowsField(field) || decision.allowsField("variables." + field)) {
                projected.put(field, value);
            }
        });
        return Collections.unmodifiableMap(projected);
    }
}
