package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.engine.StartProcessRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class RemoteEngineGatewayResponseTest {

    @Test
    void legacyKeyStartReturnsTheRemoteExactDefinitionIdentity() {
        server.expect(once(), requestTo("http://engine.test/process-definition/key/orders/start"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"id\":\"process-42\",\"definitionId\":\"orders:9:exact\"}",
                        MediaType.APPLICATION_JSON));

        var ref = gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders", Map.of(), null));

        assertThat(ref.processDefinitionId()).isEqualTo("orders:9:exact");
        assertThat(ref.processDefinitionKey()).isEqualTo("orders");
        server.verify();
    }

    private MockRestServiceServer server;
    private RemoteEngineGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RemoteEngineGateway(builder.build());
    }

    @Test
    void exactStartRejectsAMissingProcessInstanceId() {
        server.expect(once(), requestTo("http://engine.test/process-definition/definition-1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"id\":\"definition-1\",\"key\":\"orders\",\"tenantId\":null}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "http://engine.test/process-definition/definition-1/start"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.startProcess(new StartProcessRequest(
                "case-1", null, "definition-1", "orders", null, Map.of(), null)))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("process-instance id");
        server.verify();
    }

    @Test
    void legacyStartRejectsABlankProcessInstanceId() {
        server.expect(once(), requestTo("http://engine.test/process-definition/key/orders/start"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"id\":\"   \"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.startProcessByKey(new StartProcessByKeyRequest(
                "case-1", null, "orders", Map.of(), null)))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("process-instance id");
        server.verify();
    }
}
