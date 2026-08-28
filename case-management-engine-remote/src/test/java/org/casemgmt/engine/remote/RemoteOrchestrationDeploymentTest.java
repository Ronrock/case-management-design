package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteOrchestrationDeploymentTest {

    @Test
    void returnsIdentityOnlyAfterReadingBackTheExactRemoteProcessDefinition() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("http://engine.test/deployment/create"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"deployment-7\"}", MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(
                        "http://engine.test/process-definition?deploymentId=deployment-7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":"invoice:4:991","key":"invoice","version":4,
                          "deploymentId":"deployment-7","tenantId":"tenant-a"}]
                        """, MediaType.APPLICATION_JSON));
        expectResources(fixture, """
                [{"id":"resource-1","name":"invoice.bpmn","deploymentId":"deployment-7"}]
                """, new ResourceData("resource-1", bpmn()));

        EngineDeploymentIdentity identity = fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml");

        assertThat(identity).isEqualTo(new EngineDeploymentIdentity(
                "deployment-7", "invoice:4:991", "invoice", 4, "tenant-a"));
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmARemoteDefinitionFromAnotherTenant() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("http://engine.test/deployment/create"))
                .andRespond(withSuccess("{\"id\":\"deployment-7\"}", MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(
                        "http://engine.test/process-definition?deploymentId=deployment-7"))
                .andRespond(withSuccess("""
                        [{"id":"invoice:4:991","key":"invoice","version":4,
                          "deploymentId":"deployment-7","tenantId":"tenant-b"}]
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("tenant-a")
                .hasMessageContaining("tenant-b");
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmADeploymentWithNoExecutableRoot() {
        Fixture fixture = deploymentReturning("[]");

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("exactly one")
                .hasMessageContaining("found 0");
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmADeploymentWithMultipleMatchingRoots() {
        Fixture fixture = deploymentReturning("""
                [{"id":"invoice:4:991","key":"invoice","version":4,
                  "deploymentId":"deployment-7","tenantId":"tenant-a"},
                 {"id":"invoice:5:992","key":"invoice","version":5,
                  "deploymentId":"deployment-7","tenantId":"tenant-a"}]
                """);

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("exactly one")
                .hasMessageContaining("found 2");
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmADeploymentWithTheWrongDefinitionKey() {
        Fixture fixture = deploymentReturning("""
                [{"id":"credit:1:12","key":"credit","version":1,
                  "deploymentId":"deployment-7","tenantId":"tenant-a"}]
                """);

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("definition key 'invoice'")
                .hasMessageContaining("credit");
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmADeploymentWithAnAddedResource() {
        Fixture fixture = deploymentReturning(validDefinition());
        expectResources(fixture, """
                [{"id":"resource-1","name":"invoice.bpmn","deploymentId":"deployment-7"},
                 {"id":"resource-2","name":"unexpected.dmn","deploymentId":"deployment-7"}]
                """,
                new ResourceData("resource-1", bpmn()),
                new ResourceData("resource-2", "<definitions/>".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("unexpected.dmn");
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmADeploymentWithAMissingResource() {
        Fixture fixture = deploymentReturning(validDefinition());
        expectResources(fixture, "[]");

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("invoice.bpmn");
        fixture.server.verify();
    }

    @Test
    void refusesToConfirmADeploymentWithChangedResourceBytes() {
        Fixture fixture = deploymentReturning(validDefinition());
        expectResources(fixture, """
                [{"id":"resource-1","name":"invoice.bpmn","deploymentId":"deployment-7"}]
                """, new ResourceData("resource-1",
                "<definitions changed='true'/>".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> fixture.gateway.deploy(
                "release-1", "invoice", "tenant-a", bpmn(), "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("invoice.bpmn");
        fixture.server.verify();
    }

    private static Fixture deploymentReturning(String definitions) {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("http://engine.test/deployment/create"))
                .andRespond(withSuccess("{\"id\":\"deployment-7\"}", MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(
                        "http://engine.test/process-definition?deploymentId=deployment-7"))
                .andRespond(withSuccess(definitions, MediaType.APPLICATION_JSON));
        return fixture;
    }

    private static String validDefinition() {
        return """
                [{"id":"invoice:4:991","key":"invoice","version":4,
                  "deploymentId":"deployment-7","tenantId":"tenant-a"}]
                """;
    }

    private static void expectResources(Fixture fixture, String resources,
                                        ResourceData... resourceData) {
        fixture.server.expect(once(), requestTo(
                        "http://engine.test/deployment/deployment-7/resources"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resources, MediaType.APPLICATION_JSON));
        for (ResourceData data : resourceData) {
            fixture.server.expect(once(), requestTo(
                            "http://engine.test/deployment/deployment-7/resources/"
                                    + data.id() + "/data"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(data.content(), MediaType.APPLICATION_OCTET_STREAM));
        }
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new RemoteEngineGateway(builder.build()), server);
    }

    private static byte[] bpmn() {
        return "<definitions/>".getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(RemoteEngineGateway gateway, MockRestServiceServer server) { }

    private record ResourceData(String id, byte[] content) { }
}
