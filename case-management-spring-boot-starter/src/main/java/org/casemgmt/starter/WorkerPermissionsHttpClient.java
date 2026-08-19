package org.casemgmt.starter;

import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.WorkerPermissionRequest;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.permissions.WorkerPermissionsTokenProvider;
import org.casemgmt.repo.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WorkerPermissionsHttpClient implements WorkerPermissionsClient {

    private final HttpClient http;
    private final URI endpoint;
    private final WorkerPermissionsTokenProvider tokenProvider;
    private final Duration readTimeout;

    public WorkerPermissionsHttpClient(String baseUrl, String path,
                                       WorkerPermissionsTokenProvider tokenProvider,
                                       long connectTimeoutMs, long readTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Worker Permissions baseUrl is required");
        }
        this.endpoint = URI.create(trimTrailingSlash(baseUrl) + normalizePath(path));
        this.tokenProvider = tokenProvider == null ? WorkerPermissionsTokenProvider.none()
                : tokenProvider;
        this.readTimeout = Duration.ofMillis(Math.max(readTimeoutMs, 1));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1)))
                .build();
    }

    @Override
    public Map<String, PermissionDecision> evaluate(WorkerPermissionRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(toWire(request))));
        String token = tokenProvider.bearerToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try {
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Worker Permissions returned HTTP "
                        + response.statusCode());
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Worker Permissions request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker Permissions request was interrupted", e);
        }
    }

    private static Map<String, Object> toWire(WorkerPermissionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", request.tenantId());
        body.put("workerId", request.workerId());
        body.put("groups", request.groups());
        body.put("action", request.action());
        body.put("resourceType", request.resourceType());
        body.put("resources", request.resources().stream()
                .map(resource -> Map.of("id", resource.id(), "context", resource.context()))
                .toList());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PermissionDecision> parse(String body) {
        Map<String, Object> parsed = JsonCodec.toMap(body);
        Object raw = parsed.get("decisions");
        if (!(raw instanceof List<?>)) {
            raw = parsed.get("resources");
        }
        if (!(raw instanceof List<?> list)) {
            return Map.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(WorkerPermissionsHttpClient::decision)
                .collect(Collectors.toMap(PermissionDecision::resourceId, d -> d,
                        (left, right) -> right));
    }

    private static PermissionDecision decision(Map<String, Object> item) {
        String resourceId = stringValue(item.getOrDefault("resourceId", item.get("id")));
        boolean allowed = Boolean.TRUE.equals(item.get("allowed"));
        Object rawFields = item.get("allowedFields");
        List<String> allowedFields = rawFields instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
        return new PermissionDecision(resourceId, allowed, allowedFields);
    }

    private static String stringValue(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("Worker Permissions decision has no resource id");
        }
        return value.toString();
    }

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "/permissions/evaluate";
        }
        return value.startsWith("/") ? value : "/" + value;
    }
}
