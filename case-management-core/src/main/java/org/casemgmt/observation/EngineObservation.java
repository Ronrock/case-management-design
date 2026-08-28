package org.casemgmt.observation;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-neutral lifecycle evidence received from either an embedded engine or a remote adapter.
 *
 * <p>The source, tenant, case, process, entity, revision, and occurrence time identify the engine
 * fact. {@link #receivedAt()} records transport timing only and is deliberately excluded from the
 * fingerprint so redelivery cannot create a second effect.
 */
public sealed interface EngineObservation permits ProcessObservation, UserTaskObservation,
        ActivityLifecycleObservation, MilestoneObservation {

    String observationId();

    int observationVersion();

    String source();

    String tenantId();

    String caseId();

    String processInstanceId();

    String entityId();

    Long entityRevision();

    Enum<?> eventType();

    Instant engineOccurredAt();

    Instant receivedAt();

    Map<String, Object> attributes();

    /** Returns the deterministic engine-fact digest used for application-level claiming. */
    default String fingerprint() {
        return ObservationFingerprint.of(this).value();
    }

    /** Validates the identity fields shared by every concrete observation. */
    static void validateIdentity(
            String observationId,
            int observationVersion,
            String source,
            String tenantId,
            String caseId,
            String processInstanceId,
            String entityId,
            Enum<?> eventType,
            Instant engineOccurredAt,
            Instant receivedAt) {
        requireNonBlank(observationId, "observationId");
        if (observationVersion < 1) {
            throw new IllegalArgumentException("observationVersion must be positive");
        }
        requireNonBlank(source, "source");
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(caseId, "caseId");
        requireNonBlank(processInstanceId, "processInstanceId");
        requireNonBlank(entityId, "entityId");
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (engineOccurredAt == null) {
            throw new IllegalArgumentException("engineOccurredAt must not be null");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }
    }

    /**
     * Returns a recursively immutable copy containing only values that can be represented in JSON.
     */
    static Map<String, Object> immutableAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : attributes.entrySet()) {
            requireNonBlank(entry.getKey(), "attribute key");
            copy.put(entry.getKey(), immutableJsonValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return immutableJsonNumber(number);
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("attribute map keys must be strings");
                }
                requireNonBlank(key, "attribute key");
                copy.put(key, immutableJsonValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(list.size());
            for (var item : list) {
                copy.add(immutableJsonValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("attribute values must be JSON-friendly");
    }

    private static BigDecimal immutableJsonNumber(Number value) {
        if ((value instanceof Double doubleValue && !Double.isFinite(doubleValue))
                || (value instanceof Float floatValue && !Float.isFinite(floatValue))) {
            throw new IllegalArgumentException("attribute numbers must be finite");
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("attribute numbers must be JSON-friendly", exception);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
