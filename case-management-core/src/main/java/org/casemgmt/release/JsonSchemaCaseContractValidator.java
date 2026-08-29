package org.casemgmt.release;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.orchestration.OrchestrationMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes {@code case-contract-v1.schema.json} against a contract release and maps the result
 * to {@link ValidatedCaseContract}.
 *
 * <p>The schema ships on this module's classpath rather than being read from {@code docs/}: a
 * deployed artifact must validate with the schema it was built with, not with whatever the
 * working tree happens to contain.
 *
 * <p>Jackson 2 ({@code com.fasterxml}) is deliberate — it is the only Jackson on this module's
 * classpath, for the reason recorded on {@code JsonCodec}.
 */
public final class JsonSchemaCaseContractValidator implements CaseContractValidator {

    static final String SCHEMA_RESOURCE = "/schemas/case-contract-v1.schema.json";
    static final int MAX_CONTRACT_BYTES = 25 * 1024 * 1024;

    /**
     * Root properties that decide sequence or task activation. In {@code BPMN} mode the process
     * model already decides them; a contract that declares them too creates the second process
     * authority review comment 4 raised. The schema rejects each as an unknown property, and the
     * diagnostic is rewritten here so the author is told which side wins rather than being left
     * to guess why a familiar property is "not defined".
     */
    private static final Set<String> BPMN_RESERVED_LIFECYCLE = Set.of(
            "planItems", "planModel", "stages", "sentries", "entryCriteria", "exitCriteria",
            "milestones", "timers", "lifecycle", "taskActivation", "transitions",
            "repetitionRule", "requiredRule", "manualActivation", "autoComplete");

    /**
     * Diagnostics are read by an author and copied into logs and problem responses, so they are
     * bounded on both axes. An author fixes the first handful of violations and revalidates; the
     * hundredth line helps nobody and a contract can carry customer-shaped content.
     */
    private static final int MAX_REPORTED_VIOLATIONS = 12;
    private static final int MAX_VIOLATION_LENGTH = 180;

    private static final JsonSchema SCHEMA = compileSchema();

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    @Override
    public ValidatedCaseContract validate(String definitionKey, byte[] utf8Json) {
        JsonNode root = parse(definitionKey, utf8Json);
        OrchestrationMode mode = declaredMode(definitionKey, root);

        List<ValidationMessage> violations = new ArrayList<>(SCHEMA.validate(root));
        if (!violations.isEmpty()) {
            throw new InvalidCaseDefinitionException(definitionKey, report(violations, mode));
        }
        rejectUnsupportedTransforms(definitionKey, root, mode);
        rejectDuplicateEngineOutputTargets(definitionKey, root, mode);

        ValidatedCaseContract contract = map(root, mode);
        checkIdentity(definitionKey, contract);
        return contract;
    }

    // ------------------------------------------------------------------ parsing

    private JsonNode parse(String definitionKey, byte[] utf8Json) {
        if (utf8Json == null || utf8Json.length == 0) {
            throw invalid(definitionKey, "Contract release is empty");
        }
        if (utf8Json.length > MAX_CONTRACT_BYTES) {
            throw invalid(definitionKey, "Contract release exceeds " + MAX_CONTRACT_BYTES
                    + " bytes");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8Json))
                    .toString();
        } catch (Exception e) {
            throw invalid(definitionKey, "Contract release is not valid UTF-8");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            // The parser's own message quotes the offending source line; keep it out of the
            // response and out of the logs.
            throw invalid(definitionKey, "Contract release is not well-formed JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid(definitionKey, "Contract release must be a JSON object");
        }
        return root;
    }

    /**
     * The only supported mode is read explicitly from the release.  Missing data is not guessed:
     * treating an old release as BPMN would start a process whose authority was never published.
     */
    private static OrchestrationMode declaredMode(String definitionKey, JsonNode root) {
        JsonNode declared = root.get("orchestrationMode");
        if (declared == null || !"BPMN".equals(declared.asText())) {
            throw invalid(definitionKey,
                    "Contract release must explicitly declare orchestrationMode BPMN");
        }
        return OrchestrationMode.BPMN;
    }

    // --------------------------------------------------------------- diagnostics

    private static String report(List<ValidationMessage> violations, OrchestrationMode mode) {
        List<String> lines = violations.stream()
                .map(violation -> line(violation, mode))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        StringBuilder message = new StringBuilder("Contract release is invalid:");
        lines.stream().limit(MAX_REPORTED_VIOLATIONS)
                .forEach(line -> message.append("\n  ").append(line));
        if (lines.size() > MAX_REPORTED_VIOLATIONS) {
            message.append("\n  ...and ").append(lines.size() - MAX_REPORTED_VIOLATIONS)
                    .append(" further violations");
        }
        return message.toString();
    }

    /**
     * JSON Schema reports {@code required} and {@code additionalProperties} against the
     * <em>containing</em> object, which leaves an author reading {@code /fields/amount} and
     * guessing which property to edit. The offending property is appended so every diagnostic
     * names the exact location.
     */
    private static String line(ValidationMessage violation, OrchestrationMode mode) {
        String location = String.valueOf(violation.getInstanceLocation());
        String property = violation.getProperty();
        String path = property == null || property.isBlank()
                ? location
                : location + "/" + property;

        if (mode == OrchestrationMode.BPMN && location.isEmpty()
                && "additionalProperties".equals(violation.getType())
                && BPMN_RESERVED_LIFECYCLE.contains(property)) {
            return path + ": BPMN orchestration is authoritative for lifecycle and task "
                    + "activation; remove '" + property + "' from the contract";
        }

        String text = violation.getMessage();
        String prefix = location + ": ";
        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length());
        }
        if (text.length() > MAX_VIOLATION_LENGTH) {
            text = text.substring(0, MAX_VIOLATION_LENGTH) + "...";
        }
        return path + ": " + text;
    }

    private static void checkIdentity(String definitionKey, ValidatedCaseContract contract) {
        if (!definitionKey.equals(contract.key())) {
            throw invalid(definitionKey, "Contract key '" + contract.key()
                    + "' must equal case-definition key '" + definitionKey + "'");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (var action : contract.adHocActions()) {
            if (!ids.add(action.id())) {
                throw invalid(definitionKey, "Duplicate ad-hoc action id '" + action.id() + "'");
            }
        }
    }

    private static void rejectUnsupportedTransforms(
            String definitionKey, JsonNode root, OrchestrationMode mode) {
        if (mode != OrchestrationMode.BPMN) {
            return;
        }
        rejectTransformInMappings(definitionKey, root.get("mappings"), "/mappings");
        JsonNode actions = root.get("adHocActions");
        if (actions == null || !actions.isArray()) {
            return;
        }
        for (int index = 0; index < actions.size(); index++) {
            rejectTransformInMappings(definitionKey, actions.get(index).get("mappings"),
                    "/adHocActions/" + index + "/mappings");
        }
    }

    private static void rejectTransformInMappings(
            String definitionKey, JsonNode mappings, String path) {
        if (mappings == null || !mappings.isArray()) {
            return;
        }
        for (int index = 0; index < mappings.size(); index++) {
            if (mappings.get(index).hasNonNull("transformRef")) {
                throw invalid(definitionKey, "Contract release is invalid:\n  " + path + "/"
                        + index + "/transformRef: transforms are not supported");
            }
        }
    }

    private static void rejectDuplicateEngineOutputTargets(
            String definitionKey, JsonNode root, OrchestrationMode mode) {
        if (mode != OrchestrationMode.BPMN) {
            return;
        }
        JsonNode mappings = root.get("mappings");
        if (mappings == null || !mappings.isArray()) {
            return;
        }
        Map<String, Integer> firstIndexByTarget = new LinkedHashMap<>();
        for (int index = 0; index < mappings.size(); index++) {
            JsonNode mapping = mappings.get(index);
            if (!"ENGINE_TO_CASE".equals(text(mapping, "direction"))) {
                continue;
            }
            String target = text(mapping, "target");
            Integer firstIndex = firstIndexByTarget.putIfAbsent(target, index);
            if (firstIndex != null) {
                throw invalid(definitionKey, "Contract release is invalid:\n  /mappings/" + index
                        + "/target: duplicate ENGINE_TO_CASE target; first declared at /mappings/"
                        + firstIndex + "/target");
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private ValidatedCaseContract map(JsonNode root, OrchestrationMode mode) {
        return new ValidatedCaseContract(
                text(root, "key"),
                mode,
                fields(root),
                forms(root),
                mode == OrchestrationMode.BPMN ? mappings(root) : List.of(),
                slaBindings(root),
                adHocActions(root),
                new LinkedHashSet<>(strings(root.get("candidateGroups"))),
                new LinkedHashSet<>(strings(root.get("roles"))),
                names(root.get("searchProfiles")));
    }

    private List<ValidatedCaseContract.MappingDefinition> mappings(JsonNode root) {
        List<ValidatedCaseContract.MappingDefinition> result = new ArrayList<>();
        JsonNode mappings = root.get("mappings");
        if (mappings == null || !mappings.isArray()) {
            return result;
        }
        for (JsonNode node : mappings) {
            String type = text(node, "type");
            String writeMode = text(node, "writeMode");
            result.add(new ValidatedCaseContract.MappingDefinition(
                    ValidatedCaseContract.MappingDirection.valueOf(text(node, "direction")),
                    text(node, "source"),
                    text(node, "target"),
                    type == null ? null : ValidatedCaseContract.MappingType.valueOf(
                            type.toUpperCase(java.util.Locale.ROOT)),
                    writeMode == null ? ValidatedCaseContract.MappingWriteMode.REPLACE
                            : ValidatedCaseContract.MappingWriteMode.valueOf(writeMode),
                    node.path("required").asBoolean(false),
                    text(node, "transformRef"),
                    strings(node.get("submitRoles")),
                    objectMap(node.get("extensions"))));
        }
        return result;
    }

    private Map<String, ValidatedCaseContract.FieldDefinition> fields(JsonNode root) {
        Map<String, ValidatedCaseContract.FieldDefinition> result = new LinkedHashMap<>();
        forEachProperty(root.get("fields"), (id, node) -> result.put(id,
                new ValidatedCaseContract.FieldDefinition(id, objectMap(node.get("schema")),
                        strings(node.get("readRoles")), strings(node.get("writeRoles")))));
        return result;
    }

    /**
     * A form is represented as {@code {schema, uiSchema}} and normalized here for consumers.
     */
    private Map<String, ValidatedCaseContract.FormDefinition> forms(JsonNode root) {
        Map<String, ValidatedCaseContract.FormDefinition> result = new LinkedHashMap<>();
        forEachProperty(root.get("forms"), (id, node) -> {
            result.put(id, new ValidatedCaseContract.FormDefinition(id,
                    objectMap(node.get("schema")), objectMap(node.get("uiSchema"))));
        });
        return result;
    }

    private List<ValidatedCaseContract.SlaBindingDefinition> slaBindings(JsonNode root) {
        List<ValidatedCaseContract.SlaBindingDefinition> result = new ArrayList<>();
        forEachProperty(root.get("slaBindings"), (id, node) -> result.add(
                new ValidatedCaseContract.SlaBindingDefinition(id,
                        ValidatedCaseContract.SlaScope.valueOf(text(node, "scope")),
                        text(node, "calendarId"),
                        text(node, "duration"),
                        text(node, "dueDateExpression"),
                        text(node, "startAnchor"),
                        text(node, "meetAnchor"),
                        text(node, "cancelAnchor"),
                        strings(node.get("warnings")))));
        return result;
    }

    private List<ValidatedCaseContract.AdHocActionDefinition> adHocActions(JsonNode root) {
        List<ValidatedCaseContract.AdHocActionDefinition> result = new ArrayList<>();
        JsonNode actions = root.get("adHocActions");
        if (actions == null || !actions.isArray()) {
            return result;
        }
        for (JsonNode node : actions) {
            String id = text(node, "id");
            String name = text(node, "name");
            List<String> roles = strings(node.get("roles"));
            String formRef = text(node, "formRef");
            List<String> groups = strings(node.get("candidateGroups"));
            String availability = text(node, "availabilityExpression");
            result.add(switch (text(node, "type")) {
                case "PROCESS" -> new ValidatedCaseContract.ProcessAction(id, name, roles,
                        formRef, groups, availability, text(node, "processDefinitionKey"));
                case "MESSAGE" -> new ValidatedCaseContract.MessageAction(id, name, roles,
                        formRef, groups, availability, text(node, "messageName"));
                default -> new ValidatedCaseContract.TaskAction(id, name, roles, formRef,
                        groups, availability);
            });
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private interface PropertyConsumer {
        void accept(String name, JsonNode value);
    }

    private static void forEachProperty(JsonNode node, PropertyConsumer consumer) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            consumer.accept(name, node.get(name));
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        forEachProperty(node, (name, ignored) -> result.add(name));
        return result;
    }

    private Map<String, Object> objectMap(JsonNode node) {
        return node == null || !node.isObject()
                ? Map.of()
                : mapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String property) {
        JsonNode value = node == null ? null : node.get(property);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }

    private static JsonSchema compileSchema() {
        try (InputStream schema = JsonSchemaCaseContractValidator.class
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schema == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the classpath");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schema, SchemaValidatorsConfig.builder()
                            // RFC 6901 pointers, not networknt's JSONPath-flavoured default:
                            // the path is what an author edits and what a renderer resolves.
                            .pathType(PathType.JSON_POINTER)
                            .build());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SCHEMA_RESOURCE, e);
        }
    }
}
