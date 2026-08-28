package org.casemgmt.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable intent to change only explicitly mapped canonical fields.
 *
 * <p>Each change retains both the case version and the exact prior target value observed while
 * mapping. The repository compares both before applying any value, so a confirmed engine output
 * cannot silently replace a newer business edit.
 */
public record CanonicalPatch(
        String caseId,
        String taskDefinitionKey,
        long expectedCaseVersion,
        List<FieldChange> changes) {

    public static final String REDACTED = "<redacted>";

    public CanonicalPatch {
        requireNonBlank(caseId, "caseId");
        requireNonBlank(taskDefinitionKey, "taskDefinitionKey");
        if (expectedCaseVersion < 0) {
            throw new IllegalArgumentException("expectedCaseVersion must not be negative");
        }
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** General audit payload: sensitive values are never included, even in-memory. */
    public List<AuditChange> auditSummary() {
        return changes.stream().map(change -> change.sensitive()
                ? new AuditChange(change.fieldId(), change.source(), change.mappingPath(),
                        change.writeMode(), REDACTED, REDACTED, true)
                : new AuditChange(change.fieldId(), change.source(), change.mappingPath(),
                        change.writeMode(), change.expectedValue(), change.value(), false))
                .toList();
    }

    public enum WriteMode { REPLACE, MERGE }

    public record FieldChange(
            String mappingPath,
            String source,
            String fieldId,
            WriteMode writeMode,
            boolean expectedPresent,
            Object expectedValue,
            Object value,
            boolean sensitive) {
        public FieldChange {
            requireNonBlank(mappingPath, "mappingPath");
            requireNonBlank(source, "source");
            requireNonBlank(fieldId, "fieldId");
            if (writeMode == null) {
                throw new IllegalArgumentException("writeMode must not be null");
            }
            expectedValue = immutableJsonValue(expectedValue);
            value = immutableJsonValue(value);
            if (writeMode == WriteMode.MERGE && !(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("MERGE requires an object value");
            }
        }
    }

    public record AuditChange(
            String fieldId,
            String source,
            String mappingPath,
            WriteMode writeMode,
            Object previousValue,
            Object newValue,
            boolean redacted) { }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if ((number instanceof Double d && !Double.isFinite(d))
                    || (number instanceof Float f && !Float.isFinite(f))) {
                throw new IllegalArgumentException("canonical patch numbers must be finite");
            }
            return number instanceof BigDecimal ? number : new BigDecimal(number.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("canonical patch object keys must be strings");
                }
                copy.put(key, immutableJsonValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("canonical patch values must be JSON-compatible");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
