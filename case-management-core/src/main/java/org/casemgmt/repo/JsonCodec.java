package org.casemgmt.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

    /**
     * Stable semantic JSON used by durable idempotency. Object keys are sorted, array order is
     * retained, and numerically equal values such as {@code 1} and {@code 1.0} have one form.
     */
    public static String canonicalJson(Object value) {
        try {
            JsonNode tree = value instanceof String json
                    ? MAPPER.readTree(json) : MAPPER.valueToTree(value);
            return MAPPER.writeValueAsString(canonicalNode(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value is not valid JSON", e);
        }
    }

    public static String canonicalSha256(Object value) {
        return sha256(canonicalJson(value));
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static JsonNode canonicalNode(JsonNode node) {
        if (node == null || node.isNull() || node.isBoolean() || node.isTextual()) {
            return node;
        }
        if (node.isNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            if (number.signum() == 0) number = BigDecimal.ZERO;
            return DecimalNode.valueOf(number);
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(child -> result.add(canonicalNode(child)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonicalNode(node.get(name))));
            return result;
        }
        throw new IllegalArgumentException("Unsupported JSON node: " + node.getNodeType());
    }
}
