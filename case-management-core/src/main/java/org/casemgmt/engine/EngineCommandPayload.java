package org.casemgmt.engine;

import org.casemgmt.repo.JsonCodec;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared fail-closed payload contract used by submission, rehydration, and HTTP transport. */
public final class EngineCommandPayload {

    private EngineCommandPayload() { }

    public static Map<String, Object> validate(
            EngineCommandPolicy.CommandContext command, Map<String, Object> payload) {
        Objects.requireNonNull(command, "command");
        Map<String, Object> canonical = JsonCodec.toMap(JsonCodec.canonicalJson(
                Objects.requireNonNull(payload, "payload")));
        Contract contract = contract(command.commandType());
        if (!contract.allowed().containsAll(canonical.keySet())
                || !canonical.keySet().containsAll(contract.required())) {
            throw new IllegalArgumentException("Command payload fields do not match command type "
                    + command.commandType());
        }
        contract.stringFields().forEach(field -> {
            if (canonical.containsKey(field)) string(canonical, field, field.equals("contentBase64")
                    ? 16_000_000 : 10_000, contract.required().contains(field));
        });
        if (canonical.containsKey("variables")) requireMap(canonical, "variables");
        if (canonical.containsKey("candidateGroups")) requireStringList(canonical,
                "candidateGroups");

        String expected = switch (command.commandType()) {
            case CREATE_TASK -> string(canonical, "planItemId", 10_000, true);
            case CLAIM_TASK, COMPLETE_TASK -> string(canonical, "engineTaskId", 10_000, true);
            case START_PROCESS -> startTarget(canonical);
            case CANCEL_PROCESS -> string(canonical, "processInstanceId", 10_000, true);
            case DEPLOY_ORCHESTRATION -> string(canonical, "definitionKey", 10_000, true);
            case CORRELATE_MESSAGE -> string(canonical, "messageName", 10_000, true);
        };
        if (!command.expectedTargetIdentity().equals(expected)) {
            throw new IllegalArgumentException(
                    "Command payload target differs from immutable command target");
        }
        if (canonical.containsKey("tenantId")) {
            String tenant = string(canonical, "tenantId", 64, false);
            if (tenant != null && !tenant.equals(command.tenantId())) {
                throw new IllegalArgumentException(
                        "Command payload tenant differs from immutable command tenant");
            }
        }
        if (command.commandType() == EngineCommand.Type.DEPLOY_ORCHESTRATION) {
            try {
                java.util.Base64.getDecoder().decode(string(
                        canonical, "contentBase64", 16_000_000, true));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Deployment content is not valid Base64", invalid);
            }
        }
        return java.util.Collections.unmodifiableMap(canonical);
    }

    private static String startTarget(Map<String, Object> payload) {
        String selection = string(payload, "selectionType", 8, true);
        return switch (selection) {
            case "ID" -> string(payload, "processDefinitionId", 10_000, true);
            case "KEY" -> string(payload, "processDefinitionKey", 10_000, true);
            default -> throw new IllegalArgumentException(
                    "START_PROCESS selectionType must be ID or KEY");
        };
    }

    private static String string(
            Map<String, Object> payload, String field, int max, boolean required) {
        Object value = payload.get(field);
        if (value == null) {
            if (required) throw new IllegalArgumentException(
                    "Command payload field " + field + " is required");
            return null;
        }
        if (!(value instanceof String text) || text.length() > max
                || required && text.isBlank()) {
            throw new IllegalArgumentException(
                    "Command payload field " + field + " has an invalid string value");
        }
        return text.isBlank() ? null : text;
    }

    private static void requireMap(Map<String, Object> payload, String field) {
        if (!(payload.get(field) instanceof Map<?, ?>)) throw new IllegalArgumentException(
                "Command payload field " + field + " must be an object");
    }

    private static void requireStringList(Map<String, Object> payload, String field) {
        if (!(payload.get(field) instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String text)
                        || text.isBlank() || text.length() > 255)) {
            throw new IllegalArgumentException(
                    "Command payload field " + field + " must be a list of identifiers");
        }
    }

    private static Contract contract(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> new Contract(Set.of("planItemId", "name", "assignee",
                    "candidateGroups", "formKey", "variables"), Set.of("planItemId", "name",
                    "candidateGroups", "variables"), Set.of("planItemId", "name", "assignee",
                    "formKey"));
            case CLAIM_TASK -> new Contract(Set.of("engineTaskId", "userId"),
                    Set.of("engineTaskId", "userId"), Set.of("engineTaskId", "userId"));
            case COMPLETE_TASK -> new Contract(Set.of("engineTaskId", "variables"),
                    Set.of("engineTaskId"), Set.of("engineTaskId"));
            case START_PROCESS -> new Contract(Set.of("planItemId", "selectionType",
                    "processDefinitionId", "processDefinitionKey", "tenantId", "variables",
                    "correlationId"), Set.of("selectionType", "variables"),
                    Set.of("planItemId", "selectionType", "processDefinitionId",
                            "processDefinitionKey", "tenantId", "correlationId"));
            case CANCEL_PROCESS -> new Contract(Set.of("processInstanceId", "reason"),
                    Set.of("processInstanceId"), Set.of("processInstanceId", "reason"));
            case DEPLOY_ORCHESTRATION -> new Contract(Set.of("releaseId", "definitionKey",
                    "tenantId", "contentBase64", "mediaType"), Set.of("releaseId",
                    "definitionKey", "contentBase64", "mediaType"), Set.of("releaseId",
                    "definitionKey", "tenantId", "contentBase64", "mediaType"));
            case CORRELATE_MESSAGE -> new Contract(Set.of("messageName", "variables"),
                    Set.of("messageName", "variables"), Set.of("messageName"));
        };
    }

    private record Contract(
            Set<String> allowed, Set<String> required, Set<String> stringFields) { }
}
