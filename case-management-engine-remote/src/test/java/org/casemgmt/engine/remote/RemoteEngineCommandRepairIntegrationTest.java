package org.casemgmt.engine.remote;

import org.casemgmt.OracleTestBase;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteEngineCommandRepairIntegrationTest extends OracleTestBase {

    private static final Instant NOW = Instant.parse("2026-08-29T08:00:00Z");

    @Test
    void dispatcherPersistsLeasesRepairsAndConfirmsAPartiallyCreatedTask() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String taskId = "cm-command-command-1";
        server.expect(requestTo("http://engine.test/task/" + taskId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://engine.test/task/create"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        server.expect(requestTo("http://engine.test/task/" + taskId))
                .andRespond(withSuccess("{\"id\":\"" + taskId
                                + "\",\"name\":\"Review\",\"assignee\":null}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/identity-links"))
                .andRespond(withSuccess("""
                        [{"groupId":"reviewers","type":"candidate"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/variables"))
                .andRespond(withSuccess("""
                        {"caseId":{"value":"case-1","type":"String"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/variables"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://engine.test/task/" + taskId + "/variables"))
                .andRespond(withSuccess("""
                        {"caseId":{"value":"case-1","type":"String"},
                         "planItemId":{"value":"plan-1","type":"String"},
                         "priority":{"value":"high","type":"String"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://engine.test/task/" + taskId))
                .andRespond(withSuccess("{\"id\":\"" + taskId
                                + "\",\"name\":\"Review\",\"assignee\":null}",
                        MediaType.APPLICATION_JSON));

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EngineCommandRepository repository = new EngineCommandRepository(dataSource(), clock);
        repository.submit(new ProductionEngineCommandStore.ProductionCommandRequest(
                "command-1", "case-1", "tenant-1", "operation-1", "idem-1",
                EngineCommand.Type.CREATE_TASK,
                Map.of("planItemId", "plan-1", "name", "Review",
                        "candidateGroups", List.of("reviewers"),
                        "variables", Map.of("priority", "high")),
                "plan-1", null, null, null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        EngineCommandDispatcher dispatcher = new EngineCommandDispatcher(
                repository, new RemoteEngineGateway(builder.build()), "worker-1", clock,
                Duration.ofMinutes(5));

        assertThat(dispatcher.drainOnce()).isEqualTo(2);

        var stored = repository.require("tenant-1", "operation-1");
        assertThat(stored.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThat(stored.state().committedDecision().terminalConfirmation().remoteIdentity())
                .isEqualTo(taskId);
        assertThat(jdbc().sql("""
                SELECT OUTCOME_FORMAT_ || ':' || OUTCOME_KIND_ || ':' || TO_STATUS_
                FROM CM_ENGINE_COMMAND_TRANSITION
                WHERE COMMAND_ID_='command-1' AND VERSION_>0
                ORDER BY VERSION_
                """).query(String.class).list()).containsExactly(
                "2:DISPATCH_REQUESTED:DISPATCHING",
                "2:REPAIRABLE_PARTIAL_EFFECT:RETRYABLE",
                "2:DISPATCH_REQUESTED:DISPATCHING",
                "2:HTTP_RESPONSE:CONFIRMED");
        server.verify();
    }
}
