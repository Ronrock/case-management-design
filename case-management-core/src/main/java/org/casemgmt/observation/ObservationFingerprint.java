package org.casemgmt.observation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** A stable SHA-256 identity for a single engine observation. */
public record ObservationFingerprint(String value) {

    private static final String FORMAT = "observation-fingerprint-v1";

    public ObservationFingerprint {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be a lowercase SHA-256 hex digest");
        }
    }

    /**
     * Fingerprints engine-side identity and payload, intentionally excluding {@code receivedAt}.
     */
    public static ObservationFingerprint of(EngineObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        var canonical = new StringBuilder(FORMAT);
        appendField(canonical, "observationVersion", Integer.toString(observation.observationVersion()));
        appendField(canonical, "observationType", observationType(observation));
        appendField(canonical, "source", observation.source());
        appendField(canonical, "tenantId", observation.tenantId());
        appendField(canonical, "caseId", observation.caseId());
        appendField(canonical, "processInstanceId", observation.processInstanceId());
        appendField(canonical, "entityId", observation.entityId());
        appendField(canonical, "entityRevision", nullableString(observation.entityRevision()));
        appendField(canonical, "eventType", observation.eventType().name());
        appendField(canonical, "engineOccurredAt", observation.engineOccurredAt().toString());
        appendField(canonical, "attributes", canonicalJson(observation.attributes()));
        return new ObservationFingerprint(sha256(canonical.toString()));
    }

    private static String observationType(EngineObservation observation) {
        return switch (observation) {
            case ProcessObservation ignored -> "process";
            case UserTaskObservation ignored -> "user-task";
            case ActivityLifecycleObservation ignored -> "activity-lifecycle";
            case MilestoneObservation ignored -> "milestone";
        };
    }

    private static void appendField(StringBuilder target, String name, String value) {
        appendValue(target, name);
        appendValue(target, value);
    }

    private static void appendValue(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static String nullableString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String canonicalJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "string(" + lengthPrefixed(string) + ')';
        }
        if (value instanceof Boolean bool) {
            return "boolean(" + bool + ')';
        }
        if (value instanceof Number number) {
            return "number(" + normalizedNumber(number) + ')';
        }
        if (value instanceof List<?> list) {
            var canonical = new StringBuilder("list(").append(list.size()).append(':');
            for (Object item : list) {
                canonical.append(lengthPrefixed(canonicalJson(item)));
            }
            return canonical.append(')').toString();
        }
        if (value instanceof Map<?, ?> map) {
            var entries = map.entrySet().stream()
                    .map(entry -> Map.entry((String) entry.getKey(), entry.getValue()))
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .toList();
            var canonical = new StringBuilder("map(").append(entries.size()).append(':');
            for (var entry : entries) {
                canonical.append(lengthPrefixed(entry.getKey()));
                canonical.append(lengthPrefixed(canonicalJson(entry.getValue())));
            }
            return canonical.append(')').toString();
        }
        throw new IllegalArgumentException("attribute values must be JSON-friendly");
    }

    private static String lengthPrefixed(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static String normalizedNumber(Number value) {
        if ((value instanceof Double doubleValue && !Double.isFinite(doubleValue))
                || (value instanceof Float floatValue && !Float.isFinite(floatValue))) {
            throw new IllegalArgumentException("attribute numbers must be finite");
        }
        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
