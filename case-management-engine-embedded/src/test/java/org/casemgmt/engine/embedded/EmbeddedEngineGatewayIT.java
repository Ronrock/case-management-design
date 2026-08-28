package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineGatewayContract;
import org.casemgmt.engine.StartProcessRequest;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = EmbeddedEngineGatewayIT.TestApp.class)
class EmbeddedEngineGatewayIT extends EngineGatewayContract {

    @SpringBootApplication
    static class TestApp {}

    @Autowired TaskService taskService;
    @Autowired RuntimeService runtimeService;
    @Autowired RepositoryService repositoryService;

    @Override
    protected EngineGateway gateway() {
        return new EmbeddedEngineGateway(taskService, runtimeService, repositoryService);
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
                .isInstanceOf(org.casemgmt.engine.EngineException.class)
                .hasMessageContaining("another tenant");
    }
}
