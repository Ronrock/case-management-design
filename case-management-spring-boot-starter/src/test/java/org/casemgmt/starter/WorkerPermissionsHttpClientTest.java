package org.casemgmt.starter;

import com.sun.net.httpserver.HttpServer;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.WorkerPermissionRequest;
import org.casemgmt.permissions.WorkerPermissionResource;
import org.casemgmt.repo.JsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPermissionsHttpClientTest {

    @Test
    void postsBatchRequestWithBearerTokenAndParsesDecisions() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<Map<String, Object>> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/permissions/evaluate", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JsonCodec.toMap(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)));
            byte[] response = JsonCodec.toJson(Map.of("decisions", List.of(
                    Map.of("resourceId", "case-1", "allowed", true,
                            "allowedFields", List.of("*")),
                    Map.of("resourceId", "case-2", "allowed", false)
            ))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WorkerPermissionsHttpClient client = new WorkerPermissionsHttpClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "/permissions/evaluate", () -> "test-token", 1_000, 1_000);

            Map<String, PermissionDecision> decisions = client.evaluate(
                    new WorkerPermissionRequest("t1", "alice", List.of("users"),
                            "case.read", "case", List.of(
                            new WorkerPermissionResource("case-1", Map.of("state", "ACTIVE")),
                            new WorkerPermissionResource("case-2", Map.of("state", "ACTIVE")))));

            assertThat(authorization.get()).isEqualTo("Bearer test-token");
            assertThat(requestBody.get()).containsEntry("tenantId", "t1")
                    .containsEntry("workerId", "alice")
                    .containsEntry("action", "case.read")
                    .containsEntry("resourceType", "case");
            assertThat(decisions.get("case-1").allowed()).isTrue();
            assertThat(decisions.get("case-2").allowed()).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
