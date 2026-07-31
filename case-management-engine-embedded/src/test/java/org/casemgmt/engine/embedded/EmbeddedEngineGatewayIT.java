package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineGatewayContract;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = EmbeddedEngineGatewayIT.TestApp.class)
class EmbeddedEngineGatewayIT extends EngineGatewayContract {

    @SpringBootApplication
    static class TestApp {}

    @Autowired TaskService taskService;
    @Autowired RuntimeService runtimeService;
    @Autowired RepositoryService repositoryService;

    @Override
    protected EngineGateway gateway() {
        return new EmbeddedEngineGateway(taskService, runtimeService);
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
