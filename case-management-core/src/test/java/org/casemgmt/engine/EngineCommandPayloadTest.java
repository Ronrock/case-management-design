package org.casemgmt.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineCommandPayloadTest {

    @ParameterizedTest
    @MethodSource("validCommands")
    void validatesAndCanonicalizesEveryCommandType(
            EngineCommandPolicy.CommandContext context, Map<String, Object> payload) {
        assertThat(EngineCommandPayload.validate(context, payload))
                .isEqualTo(payload);
    }

    @Test
    void rejectsTargetTenantAndTypeShapeMismatchBeforeDispatch() {
        var correlate = context(EngineCommand.Type.CORRELATE_MESSAGE, "different-message");
        assertThatThrownBy(() -> EngineCommandPayload.validate(correlate,
                Map.of("messageName", "continue", "variables", Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("target");

        var start = context(EngineCommand.Type.START_PROCESS, "definition-1");
        assertThatThrownBy(() -> EngineCommandPayload.validate(start,
                Map.of("selectionType", "ID", "processDefinitionId", "definition-1",
                        "processDefinitionKey", "orders", "tenantId", "another-tenant",
                        "variables", Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant");

        var claim = context(EngineCommand.Type.CLAIM_TASK, "task-1");
        assertThatThrownBy(() -> EngineCommandPayload.validate(claim,
                Map.of("engineTaskId", "task-1", "userId", "alice",
                        "processInstanceId", "process-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fields");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> validCommands() {
        return Stream.of(
                command(EngineCommand.Type.CREATE_TASK, "plan-1", Map.of(
                        "planItemId", "plan-1", "name", "Approve", "assignee", "",
                        "candidateGroups", List.of("reviewers"), "formKey", "",
                        "variables", Map.of("x", 1))),
                command(EngineCommand.Type.CLAIM_TASK, "task-1",
                        Map.of("engineTaskId", "task-1", "userId", "alice")),
                command(EngineCommand.Type.COMPLETE_TASK, "task-1",
                        Map.of("engineTaskId", "task-1", "variables", Map.of())),
                command(EngineCommand.Type.START_PROCESS, "definition-1", Map.of(
                        "selectionType", "ID", "processDefinitionId", "definition-1",
                        "processDefinitionKey", "orders", "tenantId", "tenant-1",
                        "planItemId", "", "correlationId", "correlation-1",
                        "variables", Map.of())),
                command(EngineCommand.Type.CANCEL_PROCESS, "process-1",
                        Map.of("processInstanceId", "process-1", "reason", "cancelled")),
                command(EngineCommand.Type.DEPLOY_ORCHESTRATION, "orders", Map.of(
                        "releaseId", "release-1", "definitionKey", "orders",
                        "tenantId", "tenant-1", "contentBase64", "YQ==",
                        "mediaType", "application/xml")),
                command(EngineCommand.Type.CORRELATE_MESSAGE, "continue",
                        Map.of("messageName", "continue", "variables", Map.of())));
    }

    private static org.junit.jupiter.params.provider.Arguments command(
            EngineCommand.Type type, String target, Map<String, Object> payload) {
        return org.junit.jupiter.params.provider.Arguments.of(context(type, target), payload);
    }

    private static EngineCommandPolicy.CommandContext context(
            EngineCommand.Type type, String target) {
        return new EngineCommandPolicy.CommandContext(
                "tenant-1", "operation-1", "command-1", type, target);
    }
}
