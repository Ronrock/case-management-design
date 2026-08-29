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
