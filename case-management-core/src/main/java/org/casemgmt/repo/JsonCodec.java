package org.casemgmt.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Single place where JSON columns are (de)serialised.
 *
 * Jackson 2 (com.fasterxml), NOT Jackson 3 — settled by Task 1: case-management-core
 * has only Jackson 2 on its classpath, declared explicitly in its pom and resolved at
 * the BOM-managed 2.21.5. Jackson 3 (tools.jackson.*) exists only in the web-facing
 * modules, where Spring Boot 4 auto-configures it. Never mix the two in one class.
 *
 * Oracle CLOB binding note: JdbcClient binds these as String, which Oracle JDBC
 * handles for values under 32 KB. Larger documents need a streaming bind — if any
 * test hits ORA-01461, record it in FINDINGS.md rather than silently truncating.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {}

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse JSON column value", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> toList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse JSON column value", e);
        }
    }
}
