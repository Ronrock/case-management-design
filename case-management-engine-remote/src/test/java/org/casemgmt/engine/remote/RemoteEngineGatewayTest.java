package org.casemgmt.engine.remote;

import org.casemgmt.engine.CommandDispatchOutcome;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandPolicy;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteEngineGatewayTest {

    @Test
    void malformedCommandPayloadProducesNoHttpCall() {
        RestClient client = mock(RestClient.class);
        ProductionEngineCommandStore.StoredCommand command = mock(
                ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        when(command.payload()).thenReturn(Map.of("engineTaskId", "other-task", "variables", Map.of()));
        when(command.state()).thenReturn(state);
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext(
                "tenant-1", "operation-1", "command-1", EngineCommand.Type.COMPLETE_TASK,
                "task-1"));

        CommandDispatchOutcome outcome = new RemoteEngineGateway(client).dispatch(command);

        assertThat(outcome).isEqualTo(CommandDispatchOutcome.malformedResponse());
        verifyNoInteractions(client);
    }

    @Test
    void retryAfterHttpDateUsesTheInjectedClock() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://engine.test/task/task-1/complete"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "Sat, 29 Aug 2026 00:01:00 GMT"));

        CommandDispatchOutcome outcome = new RemoteEngineGateway(builder.build(),
                Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)).dispatch(
                command("task-1", Map.of("engineTaskId", "task-1", "variables", Map.of())));

        assertThat(outcome.httpResult().retryAfter()).isEqualTo(Duration.ofMinutes(1));
        server.verify();
    }

    @Test
    void completeTaskPreservesAnAccepted202WithoutTerminalConfirmation() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://engine.test/task/task-1/complete"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        CommandDispatchOutcome outcome = new RemoteEngineGateway(builder.build()).dispatch(
                command("task-1", Map.of("engineTaskId", "task-1", "variables", Map.of())));

        assertThat(outcome.httpResult()).isEqualTo(new CommandDispatchOutcome.HttpResult(
                202, CommandDispatchOutcome.Acceptance.ACCEPTED, null));
        assertThat(outcome.confirmationEvidence()).isNull();
        server.verify();
    }

    @Test
    void createTaskFailureAfterPrimaryEffectIsUncertainAndRetryRepairsEverySideEffect() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String taskId = "cm-command-command-1";
        server.expect(requestTo("http://engine.test/task/" + taskId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://engine.test/task/create"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("http://engine.test/task/" + taskId))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\",\"name\":\"Review\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/variables"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://engine.test/task/" + taskId))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\",\"name\":\"Review\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        RemoteEngineGateway gateway = new RemoteEngineGateway(builder.build());
        ProductionEngineCommandStore.StoredCommand command = createTaskCommand();

        CommandDispatchOutcome interrupted = gateway.dispatch(command);
        CommandDispatchOutcome repaired = gateway.dispatch(command);

        assertThat(interrupted.httpResult()).isEqualTo(new CommandDispatchOutcome.HttpResult(
                429, CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED, null));
        assertThat(repaired.confirmationEvidence()).isNotNull();
        server.verify();
    }

    private static ProductionEngineCommandStore.StoredCommand createTaskCommand() {
        ProductionEngineCommandStore.StoredCommand command = mock(
                ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        when(command.commandId()).thenReturn("command-1");
        when(command.caseId()).thenReturn("case-1");
        when(command.payload()).thenReturn(Map.of("planItemId", "plan-1", "name", "Review",
                "candidateGroups", java.util.List.of("reviewers"), "variables", Map.of()));
        when(command.state()).thenReturn(state);
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext(
                "tenant-1", "operation-1", "command-1", EngineCommand.Type.CREATE_TASK,
                "plan-1"));
        return command;
    }

    private static ProductionEngineCommandStore.StoredCommand command(
            String target, Map<String, Object> payload) {
        ProductionEngineCommandStore.StoredCommand command = mock(
                ProductionEngineCommandStore.StoredCommand.class);
        EngineCommandPolicy.CommandState state = mock(EngineCommandPolicy.CommandState.class);
        when(command.payload()).thenReturn(payload);
        when(command.state()).thenReturn(state);
        when(state.command()).thenReturn(new EngineCommandPolicy.CommandContext(
                "tenant-1", "operation-1", "command-1", EngineCommand.Type.COMPLETE_TASK,
                target));
        return command;
    }
}
