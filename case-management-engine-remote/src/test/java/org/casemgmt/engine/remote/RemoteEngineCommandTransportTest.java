package org.casemgmt.engine.remote;

import org.casemgmt.engine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteEngineCommandTransportTest {

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void everyCommandTypeReturnsCommandBoundConfirmation(EngineCommand.Type type) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var command = command(type, payload(type));
        int expectedStatus = switch (type) {
            case CLAIM_TASK, COMPLETE_TASK, CANCEL_PROCESS, CORRELATE_MESSAGE -> 204;
            default -> 200;
        };
        expectSuccessfulDispatch(server, type, HttpStatus.valueOf(expectedStatus));

        CommandDispatchOutcome outcome = new RemoteEngineGateway(builder.build())
                .dispatch(command);

        assertThat(outcome.kind()).isEqualTo(CommandDispatchOutcome.Kind.HTTP_RESPONSE);
        assertThat(outcome.httpResult().status()).isEqualTo(expectedStatus);
        assertThat(outcome.confirmationEvidence()).satisfies(evidence -> {
            assertThat(evidence.tenantId()).isEqualTo("tenant-1");
            assertThat(evidence.operationId()).isEqualTo("operation-1");
            assertThat(evidence.commandId()).isEqualTo("command-1");
            assertThat(evidence.commandType()).isEqualTo(type);
            assertThat(evidence.expectedTargetIdentity()).isEqualTo(target(type, payload(type)));
            assertThat(evidence.evidenceReference())
                    .isEqualTo("http:" + expectedStatus + ":command-1");
        });
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void everyCommandTypePreservesTheActualSuccessfulHttpStatus(EngineCommand.Type type) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectSuccessfulDispatch(server, type, HttpStatus.CREATED);

        CommandDispatchOutcome outcome = new RemoteEngineGateway(builder.build()).dispatch(
                command(type, payload(type)));

        assertThat(outcome.httpResult()).isEqualTo(new CommandDispatchOutcome.HttpResult(
                201, CommandDispatchOutcome.Acceptance.ACCEPTED, null));
        assertThat(outcome.confirmationEvidence()).isNotNull();
        server.verify();
    }

    @Test
    void httpFailuresPreserveStatusAcceptanceAndRetryAfterWithoutRawBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://engine.test/task/task-1/complete"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "90")
                        .contentType(MediaType.TEXT_PLAIN).body("password=do-not-store"));
        RemoteEngineGateway gateway = new RemoteEngineGateway(builder.build());

        CommandDispatchOutcome outcome = gateway.dispatch(command(
                EngineCommand.Type.COMPLETE_TASK,
                Map.of("engineTaskId", "task-1", "variables", Map.of())));

        assertThat(outcome).isEqualTo(CommandDispatchOutcome.http(429,
                CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED,
                Duration.ofSeconds(90), null));
        assertThat(outcome.toString()).doesNotContain("password");
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("httpFailureMatrix")
    void classifiesTheCompleteHttpFailureMatrixConservatively(
            HttpStatus status, CommandDispatchOutcome.Acceptance acceptance) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://engine.test/task/task-1/complete"))
                .andRespond(withStatus(status));

        CommandDispatchOutcome outcome = new RemoteEngineGateway(builder.build()).dispatch(
                command(EngineCommand.Type.COMPLETE_TASK,
                        Map.of("engineTaskId", "task-1", "variables", Map.of())));

        assertThat(outcome.httpResult()).isEqualTo(
                new CommandDispatchOutcome.HttpResult(status.value(), acceptance, null));
        server.verify();
    }

    static Stream<Arguments> httpFailureMatrix() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST,
                        CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED),
                Arguments.of(HttpStatus.NOT_FOUND,
                        CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED),
                Arguments.of(HttpStatus.CONFLICT,
                        CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED),
                Arguments.of(HttpStatus.REQUEST_TIMEOUT,
                        CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED),
                Arguments.of(HttpStatus.TOO_EARLY,
                        CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS,
                        CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED));
    }

    @Test
    void connectionRefusalIsTheOnlyOrdinaryFailureClassifiedBeforeConnect() {
        assertThat(RemoteEngineGateway.classifyTransport(new ResourceAccessException(
                "safe", new ConnectException("refused"))))
                .isEqualTo(CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE);
        assertThat(RemoteEngineGateway.classifyTransport(new ResourceAccessException(
                "safe", new java.net.SocketTimeoutException("timed out"))))
                .isEqualTo(CommandDispatchOutcome.TransportFailure.TIMEOUT);
        assertThat(RemoteEngineGateway.classifyTransport(new ResourceAccessException(
                "safe", new java.net.SocketException("reset"))))
                .isEqualTo(CommandDispatchOutcome.TransportFailure.MID_WRITE_FAILURE);
        assertThat(RemoteEngineGateway.classifyTransport(new ResourceAccessException("safe")))
                .isEqualTo(CommandDispatchOutcome.TransportFailure.UNKNOWN);
    }

    private static ProductionEngineCommandStore.StoredCommand command(
            EngineCommand.Type type, Map<String, Object> payload) {
        ProductionEngineCommandStore.StoredCommand command =
                mock(ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        when(command.commandId()).thenReturn("command-1");
        when(command.operationId()).thenReturn("operation-1");
        when(command.caseId()).thenReturn("case-1");
        when(command.expectedTargetIdentity()).thenReturn("target-1");
        when(command.payload()).thenReturn(payload);
        when(command.state()).thenReturn(state);
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext(
                "tenant-1", "operation-1", "command-1", type, target(type, payload)));
        return command;
    }

    private static String target(EngineCommand.Type type, Map<String, Object> payload) {
        return switch (type) {
            case CREATE_TASK -> (String) payload.get("planItemId");
            case CLAIM_TASK, COMPLETE_TASK -> (String) payload.get("engineTaskId");
            case START_PROCESS -> "ID".equals(payload.get("selectionType"))
                    ? (String) payload.get("processDefinitionId")
                    : (String) payload.get("processDefinitionKey");
            case CANCEL_PROCESS -> (String) payload.get("processInstanceId");
            case DEPLOY_ORCHESTRATION -> (String) payload.get("definitionKey");
            case CORRELATE_MESSAGE -> (String) payload.get("messageName");
        };
    }

    private static Map<String, Object> payload(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> Map.of("planItemId", "plan-1", "name", "Approve",
                    "assignee", "", "candidateGroups", List.of(), "formKey", "",
                    "variables", Map.of());
            case CLAIM_TASK -> Map.of("engineTaskId", "task-1", "userId", "alice");
            case COMPLETE_TASK -> Map.of("engineTaskId", "task-1", "variables", Map.of());
            case START_PROCESS -> Map.of("selectionType", "ID",
                    "processDefinitionId", "definition-1", "processDefinitionKey", "orders",
                    "tenantId", "tenant-1", "planItemId", "", "correlationId", "corr-1",
                    "variables", Map.of());
            case CANCEL_PROCESS -> Map.of("processInstanceId", "process-1", "reason", "done");
            case DEPLOY_ORCHESTRATION -> Map.of("releaseId", "release-1",
                    "definitionKey", "orders", "tenantId", "tenant-1",
                    "contentBase64", "YQ==", "mediaType", "application/xml");
            case CORRELATE_MESSAGE -> Map.of("messageName", "continue", "variables", Map.of());
        };
    }

    private static void expectSuccessfulDispatch(
            MockRestServiceServer server, EngineCommand.Type type, HttpStatus primaryStatus) {
        switch (type) {
            case CREATE_TASK -> {
                String taskId = "cm-command-command-1";
                server.expect(requestTo("http://engine.test/task/" + taskId))
                        .andRespond(withStatus(HttpStatus.NOT_FOUND));
                server.expect(requestTo("http://engine.test/task/create"))
                        .andExpect(org.springframework.test.web.client.match
                                .MockRestRequestMatchers.method(HttpMethod.POST))
                        .andRespond(withStatus(primaryStatus));
                server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
                server.expect(requestTo("http://engine.test/task/" + taskId + "/variables"))
                        .andRespond(withSuccess("""
                                {"caseId":{"value":"case-1","type":"String"},
                                 "planItemId":{"value":"plan-1","type":"String"}}
                                """, MediaType.APPLICATION_JSON));
                server.expect(requestTo("http://engine.test/task/" + taskId))
                        .andRespond(withSuccess("{\"id\":\"" + taskId
                                + "\",\"name\":\"Approve\"}", MediaType.APPLICATION_JSON));
            }
            case CLAIM_TASK -> server.expect(requestTo(
                            "http://engine.test/task/task-1/claim"))
                    .andRespond(withStatus(primaryStatus));
            case COMPLETE_TASK -> server.expect(requestTo(
                            "http://engine.test/task/task-1/complete"))
                    .andRespond(withStatus(primaryStatus));
            case START_PROCESS -> {
                server.expect(requestTo("http://engine.test/process-definition/definition-1"))
                        .andRespond(withSuccess("""
                                {"id":"definition-1","key":"orders","tenantId":"tenant-1"}
                                """, MediaType.APPLICATION_JSON));
                server.expect(requestTo(
                                "http://engine.test/process-definition/definition-1/start"))
                        .andRespond(withStatus(primaryStatus)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("""
                                        {"id":"process-1","definitionId":"definition-1"}
                                        """));
            }
            case CANCEL_PROCESS -> server.expect(requestTo(
                            "http://engine.test/process-instance/process-1?skipCustomListeners=false"))
                    .andExpect(org.springframework.test.web.client.match
                            .MockRestRequestMatchers.method(HttpMethod.DELETE))
                    .andRespond(withStatus(primaryStatus));
            case DEPLOY_ORCHESTRATION -> {
                server.expect(requestTo("http://engine.test/deployment/create"))
                        .andRespond(withStatus(primaryStatus)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"id\":\"deployment-1\"}"));
                server.expect(requestTo(
                                "http://engine.test/process-definition?deploymentId=deployment-1"))
                        .andRespond(withSuccess("""
                                [{"id":"definition-1","key":"orders","version":1,
                                  "deploymentId":"deployment-1","tenantId":"tenant-1"}]
                                """, MediaType.APPLICATION_JSON));
                server.expect(requestTo(
                                "http://engine.test/deployment/deployment-1/resources"))
                        .andRespond(withSuccess("""
                                [{"id":"resource-1","name":"orders.bpmn",
                                  "deploymentId":"deployment-1"}]
                                """, MediaType.APPLICATION_JSON));
                server.expect(requestTo("http://engine.test/deployment/deployment-1/resources/"
                                + "resource-1/data"))
                        .andRespond(withSuccess("a".getBytes(StandardCharsets.UTF_8),
                                MediaType.APPLICATION_OCTET_STREAM));
            }
            case CORRELATE_MESSAGE -> server.expect(requestTo("http://engine.test/message"))
                    .andRespond(withStatus(primaryStatus));
        }
    }

}
