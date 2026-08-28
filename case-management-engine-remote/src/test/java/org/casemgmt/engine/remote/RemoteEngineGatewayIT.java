package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineGatewayContract;
import org.casemgmt.engine.StartProcessRequest;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link RemoteEngineGateway} against the same contract the embedded gateway passes
 * (spec §9). The "remote engine" here is a second, engine-only Spring context in this same
 * JVM with engine-rest exposed on a random port — real HTTP, real JSON serialization, just
 * no Docker (H2 instead of the production database).
 */
@SpringBootTest(classes = RemoteEngineGatewayIT.EngineOnlyApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteEngineGatewayIT extends EngineGatewayContract {

    /** An Operaton app with NO case management on it — the "remote engine". */
    @SpringBootApplication
    static class EngineOnlyApp {}

    @LocalServerPort int port;
    @Autowired RepositoryService repositoryService;

    @Override
    protected EngineGateway gateway() {
        return new RemoteEngineGateway(RestClient.builder()
                .baseUrl("http://localhost:" + port + "/engine-rest")
                .build());
    }

    @Override
    protected DeployedProcess deployTestProcess(int version) {
        var deployment = repositoryService.createDeployment()
                .addClasspathResource("exact-start/test-process-v" + version + ".bpmn")
                .deploy();
        var definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionKey("test-fragment")
                .singleResult();
        return new DeployedProcess(definition.getId(), definition.getKey(), definition.getTenantId());
    }

    @Test
    void rejectsAnExactDefinitionOwnedByAnotherTenant() {
        var deployment = repositoryService.createDeployment()
                .tenantId("tenant-a")
                .addClasspathResource("exact-start/test-process-v1.bpmn")
                .deploy();
        var definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();

        assertThatThrownBy(() -> gateway().startProcess(new StartProcessRequest(
                "tenant-case", null, definition.getId(), definition.getKey(), "tenant-b",
                Map.of(), null)))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("another tenant");
    }

    /**
     * Remote-only failure mode: the embedded gateway has no network to fail on, so this is
     * not (and cannot be) part of the shared {@link EngineGatewayContract}. Points a fresh
     * gateway at a port nothing is listening on so the underlying HTTP client throws
     * {@code ResourceAccessException} ("Connection refused") rather than an HTTP-status
     * exception, and asserts it still surfaces as {@link EngineException} — the one exception
     * type Task 13's command outbox catches to decide retry-vs-dead-letter. A connection
     * failure escaping unwrapped would silently bypass that retry design.
     */
    @Test
    void connectionFailureSurfacesAsEngineException() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        // The socket above is closed again immediately, so nothing is listening on
        // deadPort — a connection attempt to it fails fast with "connection refused".
        EngineGateway unreachable = new RemoteEngineGateway(RestClient.builder()
                .baseUrl("http://localhost:" + deadPort + "/engine-rest")
                .build());

        assertThatThrownBy(() -> unreachable.claimTask("any-task", "alice"))
                .isInstanceOf(EngineException.class)
                .hasCauseInstanceOf(org.springframework.web.client.RestClientException.class);
    }
}
