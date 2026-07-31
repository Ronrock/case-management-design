package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineGatewayContract;
import org.operaton.bpm.engine.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

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
    protected String deployTestProcess() {
        repositoryService.createDeployment()
                .addClasspathResource("processes/test-process.bpmn")
                .enableDuplicateFiltering(true)
                .deploy();
        return "test-fragment";
    }
}
