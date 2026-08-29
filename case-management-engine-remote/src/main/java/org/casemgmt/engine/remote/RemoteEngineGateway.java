package org.casemgmt.engine.remote;

import org.casemgmt.engine.*;
import org.casemgmt.orchestration.DeploymentResourceManifest;
import org.casemgmt.orchestration.OrchestrationDeploymentClient;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Talks to a remote Operaton over engine-rest (spec §3.5 remote mode).
 *
 * Calls here are NOT in the case transaction. Callers must therefore reach this
 * class through the command outbox (Task 13), never directly from a request thread.
 */
public class RemoteEngineGateway implements EngineGateway, OrchestrationDeploymentClient,
        EngineCommandTransport {

    public static final String CASE_ID_VARIABLE = "caseId";
    private static final String PLAN_ITEM_VARIABLE = "planItemId";

    /**
     * engine-rest date fields are observed as e.g. {@code "2023-05-13T12:14:12.000+0200"} — a
     * numeric offset with no colon, which {@link OffsetDateTime#parse(CharSequence)}'s default
     * ISO formatter rejects. This formatter also tolerates shapes this engine version could
     * plausibly emit even where not directly observed: no fractional seconds at all,
     * microsecond/nanosecond-precision fractional seconds, and a bare {@code "Z"} zulu suffix
     * in place of a numeric offset. Anything outside these shapes is deliberately left to
     * throw {@link java.time.format.DateTimeParseException} rather than being caught and
     * silently turned into a null/now() createdAt — see {@link #parseCreatedAt}.
     */
    private static final DateTimeFormatter ENGINE_REST_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendOffset("+HHMM", "Z")
            .toFormatter();

    private final RestClient client;
    private final Clock clock;

    public RemoteEngineGateway(RestClient client) {
        this(client, Clock.systemUTC());
    }

    public RemoteEngineGateway(RestClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Production outbox delivery. All failures are reduced to safe policy facts. */
    @Override
    public CommandDispatchOutcome dispatch(ProductionEngineCommandStore.StoredCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            Map<String, Object> payload = EngineCommandPayload.validate(
                    command.state().command(), command.payload());
            if (command.state().command().commandType() == EngineCommand.Type.COMPLETE_TASK) {
                return dispatchCompleteTask(command, payload);
            }
            RemoteResult result = switch (command.state().command().commandType()) {
                case CREATE_TASK -> {
                    TaskDispatchResult task = createHumanTaskForDispatch(new HumanTaskRequest(
                            command.caseId(), text(payload, "planItemId"), text(payload, "name"),
                            nullable(payload, "assignee"), strings(payload.get("candidateGroups")),
                            nullable(payload, "formKey"), map(payload.get("variables")),
                            command.commandId()));
                    yield new RemoteResult(task.task().engineTaskId(),
                            CommandDispatchOutcome.RemoteState.TASK_CREATED,
                            task.primaryHttpStatus());
                }
                case CLAIM_TASK -> {
                    String id = text(payload, "engineTaskId");
                    DispatchHttpResponse response = postSuccessStatus("claimTask",
                            "/task/" + id + "/claim",
                            Map.of("userId", text(payload, "userId")));
                    yield new RemoteResult(id, CommandDispatchOutcome.RemoteState.TASK_CLAIMED,
                            response.status());
                }
                case COMPLETE_TASK -> throw new IllegalStateException("handled before dispatch switch");
                case START_PROCESS -> {
                    ProcessDispatchResult process;
                    if ("ID".equals(text(payload, "selectionType"))) {
                        process = startProcessForDispatch(new StartProcessRequest(command.caseId(),
                                nullable(payload, "planItemId"),
                                text(payload, "processDefinitionId"),
                                nullable(payload, "processDefinitionKey"),
                                nullable(payload, "tenantId"), map(payload.get("variables")),
                                nullable(payload, "correlationId")));
                    } else {
                        process = startProcessByKeyForDispatch(new StartProcessByKeyRequest(command.caseId(),
                                nullable(payload, "planItemId"),
                                text(payload, "processDefinitionKey"),
                                map(payload.get("variables")), nullable(payload, "correlationId"),
                                nullable(payload, "tenantId")));
                    }
                    yield new RemoteResult(process.process().processInstanceId(),
                            CommandDispatchOutcome.RemoteState.PROCESS_STARTED,
                            process.httpStatus());
                }
                case CANCEL_PROCESS -> {
                    String id = text(payload, "processInstanceId");
                    DispatchHttpResponse response = cancelProcessForDispatch(id);
                    yield new RemoteResult(id,
                            CommandDispatchOutcome.RemoteState.PROCESS_CANCELLED,
                            response.status());
                }
                case DEPLOY_ORCHESTRATION -> {
                    DeploymentDispatchResult deployment = deployForDispatch(
                            text(payload, "releaseId"),
                            text(payload, "definitionKey"), nullable(payload, "tenantId"),
                            Base64.getDecoder().decode(text(payload, "contentBase64")),
                            text(payload, "mediaType"));
                    yield new RemoteResult(deployment.identity().deploymentId(),
                            CommandDispatchOutcome.RemoteState.ORCHESTRATION_DEPLOYED,
                            deployment.httpStatus());
                }
                case CORRELATE_MESSAGE -> {
                    DispatchHttpResponse response = correlateMessageForDispatch(
                            new MessageCorrelationRequest(command.caseId(),
                                    text(payload, "messageName"),
                                    map(payload.get("variables"))));
                    yield new RemoteResult(command.expectedTargetIdentity(),
                            CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED,
                            response.status());
                }
            };
            return CommandDispatchOutcome.http(result.httpStatus(),
                    CommandDispatchOutcome.Acceptance.ACCEPTED, null,
                    result.httpStatus() == 202 ? null : confirmation(command, result,
                            "http:" + result.httpStatus() + ":" + command.commandId()));
        } catch (PartiallyCreatedTaskException partial) {
            EngineCommandPolicy.CommandContext context = command.state().command();
            var evidence = new CommandDispatchOutcome.RepairEvidence(
                    context.tenantId(), context.operationId(), context.commandId(),
                    context.commandType(), context.expectedTargetIdentity(), partial.taskId,
                    partial.primaryHttpStatus, partial.repairSource,
                    "create:" + context.commandId());
            RestClientResponseException response = responseCause(partial);
            if (response != null) {
                DispatchHttpResponse failure = new DispatchHttpResponse(
                        response.getStatusCode().value(), response.getResponseHeaders());
                return CommandDispatchOutcome.repairablePartialEffect(evidence,
                        new CommandDispatchOutcome.HttpResult(failure.status(),
                                CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED,
                                retryAfter(failure.headers())));
            }
            RestClientException transport = transportCause(partial);
            return transport == null
                    ? CommandDispatchOutcome.repairablePartialEffect(evidence)
                    : CommandDispatchOutcome.repairablePartialEffect(
                            evidence, classifyTransport(transport));
        } catch (EngineException failure) {
            RestClientResponseException response = responseCause(failure);
            if (response != null) return classifyHttpFailure(command, response);
            RestClientException transport = transportCause(failure);
            if (transport != null) return CommandDispatchOutcome.transportFailure(
                    classifyTransport(transport));
            return CommandDispatchOutcome.malformedResponse();
        } catch (IllegalArgumentException malformed) {
            return CommandDispatchOutcome.malformedResponse();
        }
    }

    private CommandDispatchOutcome dispatchCompleteTask(
            ProductionEngineCommandStore.StoredCommand command, Map<String, Object> payload) {
        String id = text(payload, "engineTaskId");
        DispatchHttpResponse response = completeTaskForDispatch(id, map(payload.get("variables")));
        if (response.status() < 200 || response.status() >= 300) {
            return classifyHttpFailure(response, false);
        }
        RemoteResult result = new RemoteResult(id, CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                response.status());
        return CommandDispatchOutcome.http(result.httpStatus(),
                CommandDispatchOutcome.Acceptance.ACCEPTED, null,
                result.httpStatus() == 202 ? null : confirmation(command, result,
                        "http:" + result.httpStatus() + ":" + command.commandId()));
    }

    private CommandDispatchOutcome classifyHttpFailure(
            ProductionEngineCommandStore.StoredCommand command,
            RestClientResponseException response) {
        return classifyHttpFailure(new DispatchHttpResponse(response.getStatusCode().value(),
                response.getResponseHeaders()), false);
    }

    private CommandDispatchOutcome classifyHttpFailure(
            DispatchHttpResponse response, boolean primaryEffectCompleted) {
        int status = response.status();
        CommandDispatchOutcome.Acceptance acceptance = primaryEffectCompleted
                ? CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED
                : acceptance(status);
        return CommandDispatchOutcome.http(status, acceptance, retryAfter(response.headers()), null);
    }

    private static CommandDispatchOutcome.Acceptance acceptance(int status) {
        return switch (status) {
            case 408 -> CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED;
            case 425, 429 -> CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED;
            default -> status >= 500
                    ? CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED
                    : CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED;
        };
    }

    private Duration retryAfter(RestClientResponseException response) {
        return retryAfter(response.getResponseHeaders());
    }

    private Duration retryAfter(org.springframework.http.HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst("Retry-After");
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Duration result = Duration.between(Instant.now(clock),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return result.isNegative() ? Duration.ZERO : result;
            } catch (java.time.DateTimeException invalid) {
                return null;
            }
        }
    }

    static CommandDispatchOutcome.TransportFailure classifyTransport(
            RestClientException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException) {
                return CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE;
            }
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) {
                return CommandDispatchOutcome.TransportFailure.TIMEOUT;
            }
            if (cause instanceof java.net.SocketException) {
                return CommandDispatchOutcome.TransportFailure.MID_WRITE_FAILURE;
            }
            cause = cause.getCause();
        }
        return CommandDispatchOutcome.TransportFailure.UNKNOWN;
    }

    private static RestClientResponseException responseCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) return response;
        }
        return null;
    }

    private static RestClientException transportCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientException transport) return transport;
        }
        return null;
    }

    private static CommandDispatchOutcome.ConfirmationEvidence confirmation(
            ProductionEngineCommandStore.StoredCommand command, RemoteResult result,
            String evidenceReference) {
        EngineCommandPolicy.CommandContext context = command.state().command();
        return new CommandDispatchOutcome.ConfirmationEvidence(context.tenantId(),
                context.operationId(), context.commandId(), context.commandType(),
                context.expectedTargetIdentity(), result.remoteIdentity(), result.remoteState(),
                CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE, evidenceReference);
    }

    private static String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing command field " + field);
        }
        return value.toString();
    }

    private static String nullable(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> result ? (Map<String, Object>) result : Map.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> result
                ? result.stream().map(String::valueOf).toList() : List.of();
    }

    private record RemoteResult(
            String remoteIdentity, CommandDispatchOutcome.RemoteState remoteState,
            int httpStatus) {
        private RemoteResult {
            if (remoteIdentity == null || remoteIdentity.isBlank()) {
                throw new IllegalArgumentException("Remote response identity is missing");
            }
            if (httpStatus < 200 || httpStatus >= 300) {
                throw new IllegalArgumentException("Remote success status is not 2xx");
            }
        }
    }

    private record TaskDispatchResult(
            EngineTaskRef task, int primaryHttpStatus,
            CommandDispatchOutcome.RepairSource repairSource) {
    }

    private record ProcessDispatchResult(EngineProcessRef process, int httpStatus) {
    }

    private record DeploymentDispatchResult(
            EngineDeploymentIdentity identity, int httpStatus) {
    }

    private record BodyDispatchResponse(
            Map<String, Object> body, int status,
            org.springframework.http.HttpHeaders headers) {
    }

    @Override
    public EngineDeploymentIdentity deploy(String releaseId, String definitionKey, String tenantId,
                                           byte[] content, String mediaType) {
        return deployForDispatch(releaseId, definitionKey, tenantId, content, mediaType).identity();
    }

    private DeploymentDispatchResult deployForDispatch(
            String releaseId, String definitionKey, String tenantId,
            byte[] content, String mediaType) {
        DeploymentResourceManifest approved = approvedManifest(
                definitionKey, content, mediaType);
        String fileName = definitionKey + ("application/zip".equals(mediaType) ? ".zip" : ".bpmn");
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("deployment-name", "case-release:" + releaseId);
        body.add("deployment-source", "case-management");
        body.add("enable-duplicate-filtering", "true");
        body.add("deploy-changed-only", "true");
        if (tenantId != null) {
            body.add("tenant-id", tenantId);
        }
        body.add(fileName, new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        try {
            org.springframework.http.ResponseEntity<Map> response = client.post()
                    .uri("/deployment/create")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            Map<?, ?> responseBody = response.getBody();
            Object id = responseBody == null ? null : responseBody.get("id");
            if (id == null || id.toString().isBlank()) {
                throw new EngineException("deployOrchestration returned no deployment id");
            }
            return new DeploymentDispatchResult(
                    verifyDeployment(id.toString(), definitionKey, tenantId, approved),
                    response.getStatusCode().value());
        } catch (RestClientResponseException e) {
            throw new EngineException("deployOrchestration (POST /deployment/create) failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("deployOrchestration (POST /deployment/create) failed: "
                    + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private EngineDeploymentIdentity verifyDeployment(
            String deploymentId, String definitionKey, String tenantId,
            DeploymentResourceManifest approved) {
        try {
            List<Map<String, Object>> definitions = client.get()
                    .uri(builder -> builder.path("/process-definition")
                            .queryParam("deploymentId", deploymentId).build())
                    .retrieve()
                    .body(List.class);
            List<Map<String, Object>> deployed = definitions == null ? List.of() : definitions;
            if (deployed.isEmpty()) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "expected exactly one executable root but found 0");
            }

            List<Map<String, Object>> matchingKey = deployed.stream()
                    .filter(definition -> definitionKey.equals(string(definition.get("key"))))
                    .toList();
            if (matchingKey.isEmpty()) {
                List<String> actualKeys = deployed.stream()
                        .map(definition -> string(definition.get("key")))
                        .filter(Objects::nonNull).distinct().sorted().toList();
                throw deploymentMismatch(definitionKey, tenantId,
                        "definition key '" + definitionKey + "' was not present; found "
                                + actualKeys);
            }

            List<Map<String, Object>> matchingTenant = matchingKey.stream()
                    .filter(definition -> Objects.equals(tenantId,
                            string(definition.get("tenantId"))))
                    .toList();
            if (matchingTenant.isEmpty()) {
                List<String> actualTenants = matchingKey.stream()
                        .map(definition -> displayTenant(string(definition.get("tenantId"))))
                        .distinct().sorted().toList();
                throw deploymentMismatch(definitionKey, tenantId,
                        "tenant '" + displayTenant(tenantId) + "' did not match; found "
                                + actualTenants);
            }
            if (matchingTenant.size() != 1) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "expected exactly one executable root but found "
                                + matchingTenant.size());
            }

            Map<String, Object> root = matchingTenant.getFirst();
            String actualDeploymentId = string(root.get("deploymentId"));
            if (!deploymentId.equals(actualDeploymentId)) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "engine returned process definition for deployment '"
                                + actualDeploymentId + "'");
            }
            Object version = root.get("version");
            if (!(version instanceof Number number)) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "engine returned no numeric process-definition version");
            }
            EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                    deploymentId, string(root.get("id")),
                    string(root.get("key")), number.intValue(), string(root.get("tenantId")));
            DeploymentResourceManifest deployedManifest = readDeploymentManifest(
                    deploymentId, definitionKey, tenantId);
            if (!approved.matches(deployedManifest)) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "resource manifest mismatch: "
                                + approved.differenceFrom(deployedManifest));
            }
            return identity;
        } catch (RestClientResponseException e) {
            throw new EngineException("verifyOrchestrationDeployment (GET /process-definition) failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("verifyOrchestrationDeployment (GET /process-definition) failed: "
                    + e.getMessage(), e);
        }
    }

    private static DeploymentResourceManifest approvedManifest(
            String definitionKey, byte[] content, String mediaType) {
        try {
            return DeploymentResourceManifest.fromArtifact(definitionKey, content, mediaType);
        } catch (IllegalArgumentException e) {
            throw new EngineException("Approved orchestration resource manifest is invalid: "
                    + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private DeploymentResourceManifest readDeploymentManifest(
            String deploymentId, String definitionKey, String tenantId) {
        List<Map<String, Object>> response;
        try {
            response = client.get().uri(builder -> builder
                            .path("/deployment/{deploymentId}/resources")
                            .build(deploymentId))
                    .retrieve()
                    .body(List.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("verifyOrchestrationResources (GET deployment resources) failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("verifyOrchestrationResources (GET deployment resources) failed: "
                    + e.getMessage(), e);
        }

        List<Map<String, Object>> resources = response == null ? List.of() : response;
        if (resources.size() > DeploymentResourceManifest.MAX_RESOURCES) {
            throw deploymentMismatch(definitionKey, tenantId,
                    "resource manifest exceeds " + DeploymentResourceManifest.MAX_RESOURCES
                            + " resources");
        }
        DeploymentResourceManifest.Builder manifest = DeploymentResourceManifest.builder();
        for (Map<String, Object> resource : resources) {
            String resourceId = string(resource.get("id"));
            String resourceName = string(resource.get("name"));
            String actualDeploymentId = string(resource.get("deploymentId"));
            if (resourceId == null || resourceId.isBlank()
                    || resourceName == null || resourceName.isBlank()) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "resource manifest contains an entry without id or name");
            }
            if (!deploymentId.equals(actualDeploymentId)) {
                throw deploymentMismatch(definitionKey, tenantId,
                        "resource '" + resourceName + "' belongs to deployment '"
                                + actualDeploymentId + "'");
            }
            readRemoteResource(manifest, deploymentId, resourceId, resourceName,
                    definitionKey, tenantId);
        }
        return manifest.build();
    }

    private void readRemoteResource(
            DeploymentResourceManifest.Builder manifest, String deploymentId,
            String resourceId, String resourceName, String definitionKey, String tenantId) {
        try {
            client.get().uri(builder -> builder
                            .path("/deployment/{deploymentId}/resources/{resourceId}/data")
                            .build(deploymentId, resourceId))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new EngineException("verifyOrchestrationResources (GET resource data) "
                                    + "failed: " + response.getStatusCode());
                        }
                        try {
                            manifest.add(resourceName, response.getBody());
                        } catch (java.io.IOException | IllegalArgumentException e) {
                            throw deploymentMismatch(definitionKey, tenantId,
                                    "could not read resource '" + resourceName + "': "
                                            + e.getMessage());
                        }
                        return null;
                    });
        } catch (RestClientException e) {
            throw new EngineException("verifyOrchestrationResources (GET resource data) failed: "
                    + e.getMessage(), e);
        }
    }

    private static EngineException deploymentMismatch(
            String definitionKey, String tenantId, String detail) {
        return new EngineException("Deployment verification failed for definition key '"
                + definitionKey + "' and tenant '" + displayTenant(tenantId) + "': " + detail);
    }

    private static String displayTenant(String tenantId) {
        return tenantId == null ? "<none>" : tenantId;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        return createHumanTaskForDispatch(request).task();
    }

    private TaskDispatchResult createHumanTaskForDispatch(HumanTaskRequest request) {
        // POST /task/create returns 204 No Content — no body, so the created task's id
        // cannot be read back from the response. It DOES accept a client-supplied "id",
        // however, so a client-generated id is used and then confirmed by reading the task
        // straight back (both to recover engine-assigned fields like "created", and to fail
        // fast/loud if the id was silently ignored rather than honoured).
        // Outbox retries carry the command id as requestId, so a crash after the remote POST
        // cannot create a second task on redelivery. Direct synchronous callers still receive
        // a fresh id. Operaton's client-supplied task id makes duplicate delivery fail closed
        // against the same engine resource instead of creating another one.
        String taskId = request.requestId() == null
                ? UUID.randomUUID().toString()
                : CommandDispatchOutcome.deterministicCreateTaskIdentity(request.requestId());
        int primaryHttpStatus;
        CommandDispatchOutcome.RepairSource repairSource;
        if (request.requestId() != null) {
            BodyDispatchResponse existing = getIfPresentForDispatch("/task/" + taskId);
            if (existing == null) {
                Map<String, Object> createBody = new LinkedHashMap<>();
                createBody.put("id", taskId);
                createBody.put("name", request.name());
                if (request.assignee() != null) {
                    createBody.put("assignee", request.assignee());
                }
                DispatchHttpResponse created = postSuccessStatus(
                        "createHumanTask", "/task/create", createBody);
                primaryHttpStatus = created.status();
                repairSource = CommandDispatchOutcome.RepairSource.PRIMARY_HTTP_RESPONSE;
            } else {
                primaryHttpStatus = existing.status();
                repairSource = CommandDispatchOutcome.RepairSource.IDEMPOTENCY_LOOKUP;
            }
        } else {
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("id", taskId);
            createBody.put("name", request.name());
            if (request.assignee() != null) {
                createBody.put("assignee", request.assignee());
            }
            DispatchHttpResponse created = postSuccessStatus(
                    "createHumanTask", "/task/create", createBody);
            primaryHttpStatus = created.status();
            repairSource = CommandDispatchOutcome.RepairSource.PRIMARY_HTTP_RESPONSE;
        }

        try {
            repairCandidateGroups(taskId, request.candidateGroups());
            repairTaskVariables(taskId, request);

            Map<String, Object> readBack = get("createHumanTask (read-back)", "/task/" + taskId);
            verifyTaskReadBack(readBack, taskId, request);
            return new TaskDispatchResult(toRef(readBack, request.caseId()),
                    primaryHttpStatus, repairSource);
        } catch (EngineException afterPrimaryEffect) {
            throw new PartiallyCreatedTaskException(taskId, primaryHttpStatus,
                    repairSource, afterPrimaryEffect);
        }
    }

    private void repairCandidateGroups(String taskId, List<String> candidateGroups) {
        LinkedHashSet<String> expected = new LinkedHashSet<>(
                candidateGroups == null ? List.of() : candidateGroups);
        Set<String> present = candidateGroups(
                getList("createHumanTask (candidate groups read-back)",
                        "/task/" + taskId + "/identity-links"));
        for (String group : expected) {
            if (!present.contains(group)) {
                postSuccessStatus("createHumanTask (candidate group)",
                        "/task/" + taskId + "/identity-links",
                        Map.of("groupId", group, "type", "candidate"));
            }
        }
        if (!present.containsAll(expected)) {
            Set<String> repaired = candidateGroups(
                    getList("createHumanTask (candidate groups verification)",
                            "/task/" + taskId + "/identity-links"));
            if (!repaired.containsAll(expected)) {
                throw new EngineException(
                        "createHumanTask candidate-group verification was incomplete");
            }
        }
    }

    private void repairTaskVariables(String taskId, HumanTaskRequest request) {
        Map<String, Object> expected = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        expected.put(CASE_ID_VARIABLE, request.caseId());
        expected.put(PLAN_ITEM_VARIABLE, request.planItemId());
        Map<String, Object> present = get("createHumanTask (variables read-back)",
                "/task/" + taskId + "/variables");
        Map<String, Object> missing = missingVariables(expected, present);
        if (!missing.isEmpty()) {
            postSuccessStatus("createHumanTask (variables)",
                    "/task/" + taskId + "/variables",
                    Map.of("modifications", typed(missing)));
            Map<String, Object> repaired = get("createHumanTask (variables verification)",
                    "/task/" + taskId + "/variables");
            if (!missingVariables(expected, repaired).isEmpty()) {
                throw new EngineException(
                        "createHumanTask variable verification was incomplete");
            }
        }
    }

    private static Set<String> candidateGroups(List<Map<String, Object>> links) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map<String, Object> link : links) {
            if ("candidate".equals(string(link.get("type")))) {
                String group = string(link.get("groupId"));
                if (group != null && !group.isBlank()) result.add(group);
            }
        }
        return result;
    }

    private static Map<String, Object> missingVariables(
            Map<String, Object> expected, Map<String, Object> actual) {
        Map<String, Object> missing = new LinkedHashMap<>();
        expected.forEach((name, expectedValue) -> {
            Object raw = actual.get(name);
            boolean hasValue = raw instanceof Map<?, ?> value && value.containsKey("value");
            Object actualValue = hasValue ? ((Map<?, ?>) raw).get("value") : null;
            if (!hasValue || !org.casemgmt.repo.JsonCodec.canonicalJson(
                            java.util.Collections.singletonMap("value", expectedValue))
                    .equals(org.casemgmt.repo.JsonCodec.canonicalJson(
                            java.util.Collections.singletonMap("value", actualValue)))) {
                missing.put(name, expectedValue);
            }
        });
        return missing;
    }

    private static void verifyTaskReadBack(
            Map<String, Object> task, String taskId, HumanTaskRequest request) {
        if (!taskId.equals(string(task.get("id")))
                || !request.name().equals(string(task.get("name")))
                || !Objects.equals(request.assignee(), string(task.get("assignee")))) {
            throw new EngineException(
                    "createHumanTask task read-back did not match the command");
        }
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        post("claimTask", "/task/" + engineTaskId + "/claim", Map.of("userId", userId));
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        post("completeTask", "/task/" + engineTaskId + "/complete",
                Map.of("variables", typed(variables == null ? Map.of() : variables)));
    }

    /** Dispatch needs the server's actual status, unlike the compatibility void gateway method. */
    protected DispatchHttpResponse completeTaskForDispatch(
            String engineTaskId, Map<String, Object> variables) {
        return postForDispatch("/task/" + engineTaskId + "/complete",
                Map.of("variables", typed(variables == null ? Map.of() : variables)));
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        return startProcessForDispatch(request).process();
    }

    private ProcessDispatchResult startProcessForDispatch(StartProcessRequest request) {
        Map<String, Object> definition = getExactDefinition(request.processDefinitionId());
        if (!Objects.equals(string(definition.get("tenantId")), request.tenantId())) {
            throw new EngineException("Process definition " + request.processDefinitionId()
                    + " belongs to another tenant");
        }
        if (request.processDefinitionKey() != null
                && !request.processDefinitionKey().equals(string(definition.get("key")))) {
            throw new EngineException("Process definition " + request.processDefinitionId()
                    + " does not match key " + request.processDefinitionKey());
        }
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());

        BodyDispatchResponse response = postExactStartForDispatch(request.processDefinitionId(),
                Map.of("businessKey", request.caseId(), "variables", typed(variables)));

        return new ProcessDispatchResult(processRef(response.body(), request.processDefinitionId(),
                string(definition.get("key")), request.caseId(), false), response.status());
    }

    @Override
    public EngineProcessRef startProcessByKey(StartProcessByKeyRequest request) {
        return startProcessByKeyForDispatch(request).process();
    }

    private ProcessDispatchResult startProcessByKeyForDispatch(StartProcessByKeyRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>(request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());

        BodyDispatchResponse response = postKeyStartForDispatch(
                request.processDefinitionKey(), request.tenantId(),
                Map.of("businessKey", request.caseId(), "variables", typed(variables)));

        return new ProcessDispatchResult(processRef(response.body(), null,
                request.processDefinitionKey(), request.caseId(), true), response.status());
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        cancelProcessForDispatch(processInstanceId);
    }

    private DispatchHttpResponse cancelProcessForDispatch(String processInstanceId) {
        String path = "/process-instance/" + processInstanceId + "?skipCustomListeners=false";
        try {
            org.springframework.http.ResponseEntity<Void> response =
                    client.delete().uri(path).retrieve().toBodilessEntity();
            return new DispatchHttpResponse(
                    response.getStatusCode().value(), response.getHeaders());
        } catch (RestClientResponseException e) {
            throw new EngineException("cancelProcess (DELETE " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // Covers connection failures (e.g. ResourceAccessException: connection refused,
            // timeout) as well as any other non-HTTP-status client failure — the one failure
            // class structurally impossible for the embedded (in-process) gateway. Task 13's
            // command outbox decides retry-vs-dead-letter by catching EngineException, so a
            // transient network blip must surface as one, not escape as a raw Spring exception.
            throw new EngineException("cancelProcess (DELETE " + path + ") failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void correlateMessage(MessageCorrelationRequest request) {
        correlateMessageForDispatch(request);
    }

    private DispatchHttpResponse correlateMessageForDispatch(MessageCorrelationRequest request) {
        return postSuccessStatus("correlateMessage", "/message", Map.of(
                "messageName", request.messageName(),
                "businessKey", request.caseId(),
                "processVariables", typed(request.variables() == null
                        ? Map.of() : request.variables()),
                "resultEnabled", false));
    }

    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (query.assignee() != null) {
            body.put("assignee", query.assignee());
        }
        if (query.candidateGroups() != null && !query.candidateGroups().isEmpty()) {
            body.put("candidateGroups", query.candidateGroups());
            body.put("includeAssignedTasks", true);
        }
        if (query.caseId() != null) {
            // Mirrors the embedded gateway's or() over task-local and process variables (see
            // EmbeddedEngineGateway): a standalone task created via createHumanTask() carries
            // caseId as a *task* variable, while a task spawned by startProcess() inherits it
            // as a *process* variable from the execution. engine-rest exposes these as two
            // separate query fields, "taskVariables" and "processVariables" respectively, and
            // orQueries is required to OR them together rather than getting them ANDed (which
            // would silently match nothing for either kind of task, exactly as an in-process
            // query filtering only one scope would). Other top-level filters (assignee,
            // candidateGroups) remain ANDed against this OR group, not widened by it — proven
            // by findsTasksByCandidateGroupAndCaseIdTogether in the shared contract.
            Map<String, Object> caseIdFilter = Map.of(
                    "taskVariables", List.of(Map.of(
                            "name", CASE_ID_VARIABLE, "value", query.caseId(), "operator", "eq")),
                    "processVariables", List.of(Map.of(
                            "name", CASE_ID_VARIABLE, "value", query.caseId(), "operator", "eq")));
            body.put("orQueries", List.of(caseIdFilter));
        }
        String path = "/task?maxResults=" + (query.maxResults() <= 0 ? 50 : query.maxResults());
        List<Map<String, Object>> tasks;
        try {
            tasks = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(List.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("findTasks (POST " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("findTasks (POST " + path + ") failed: " + e.getMessage(), e);
        }
        if (tasks == null) {
            return List.of();
        }

        // engine-rest's /task query resource returns task DTOs only — it does not include
        // variables. The caseId on each result must therefore be read back per task rather
        // than echoed from the query's own caseId filter: that filter is frequently null/
        // absent (e.g. a candidateGroups-only query), and echoing it would silently return
        // caseId=null for every result of exactly such a query — a field populated on create
        // and dropped on read, invisible in a query that only asserts non-emptiness. This
        // costs one extra HTTP call per returned task, bounded by the same maxResults as the
        // query itself (see findsCreatedTasksByCandidateGroup in the shared contract, and the
        // FINDINGS note this belongs in: N results becomes N+1 HTTP calls).
        return tasks.stream()
                .map(t -> toRef(t, fetchCaseId(String.valueOf(t.get("id")))))
                .toList();
    }

    /** Reads a task's actual caseId variable (task-local or inherited from its process). */
    private String fetchCaseId(String taskId) {
        String path = "/task/" + taskId + "/variables/" + CASE_ID_VARIABLE;
        try {
            Map<String, Object> variable = client.get().uri(path).retrieve().body(Map.class);
            Object value = variable == null ? null : variable.get("value");
            return value == null ? null : value.toString();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new EngineException("fetchCaseId (GET " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("fetchCaseId (GET " + path + ") failed: " + e.getMessage(), e);
        }
    }

    private EngineTaskRef toRef(Map<String, Object> task, String caseId) {
        return new EngineTaskRef(
                String.valueOf(task.get("id")),
                (String) task.get("name"),
                (String) task.get("assignee"),
                caseId,
                parseCreatedAt(task.get("created")));
    }

    /** Package-private (not private) so date-format tolerance can be unit-tested directly. */
    static OffsetDateTime parseCreatedAt(Object value) {
        if (value == null) {
            return null;
        }
        // Deliberately not caught: a DateTimeParseException here must fail the call loudly
        // rather than be swallowed into a null createdAt (see EngineGatewayContract's note on
        // this being exactly how the two modes would diverge unnoticed).
        return OffsetDateTime.parse((String) value, ENGINE_REST_DATE_TIME);
    }

    private Map<String, Object> get(String operation, String path) {
        try {
            return client.get().uri(path)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException(operation + " (GET " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException(operation + " (GET " + path + ") failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String operation, String path) {
        try {
            List<Map<String, Object>> result = client.get().uri(path)
                    .retrieve().body(List.class);
            return result == null ? List.of() : result;
        } catch (RestClientResponseException e) {
            throw new EngineException(operation + " (GET " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException(operation + " (GET " + path + ") failed: "
                    + e.getMessage(), e);
        }
    }

    private Map<String, Object> getExactDefinition(String processDefinitionId) {
        try {
            return client.get().uri(builder -> builder
                            .path("/process-definition/{processDefinitionId}")
                            .build(processDefinitionId))
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("startProcess (definition GET " + processDefinitionId + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("startProcess (definition GET " + processDefinitionId
                    + ") failed: " + e.getMessage(), e);
        }
    }

    private BodyDispatchResponse getIfPresentForDispatch(String path) {
        try {
            org.springframework.http.ResponseEntity<Map> response =
                    client.get().uri(path).retrieve().toEntity(Map.class);
            return new BodyDispatchResponse(response.getBody(),
                    response.getStatusCode().value(), response.getHeaders());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new EngineException("idempotency lookup (GET " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("idempotency lookup (GET " + path + ") failed: "
                    + e.getMessage(), e);
        }
    }

    private Map<String, Object> post(String operation, String path, Object body) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException(operation + " (POST " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // Covers connection failures (ResourceAccessException et al.) and any other
            // non-HTTP-status client failure — see the comment in cancelProcess for why this
            // matters specifically for the remote gateway.
            throw new EngineException(operation + " (POST " + path + ") failed: " + e.getMessage(), e);
        }
    }

    private DispatchHttpResponse postSuccessStatus(
            String operation, String path, Object body) {
        try {
            org.springframework.http.ResponseEntity<Void> response = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new DispatchHttpResponse(
                    response.getStatusCode().value(), response.getHeaders());
        } catch (RestClientResponseException e) {
            throw new EngineException(operation + " (POST " + path + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException(operation + " (POST " + path + ") failed: "
                    + e.getMessage(), e);
        }
    }

    private DispatchHttpResponse postForDispatch(String path, Object body) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> new DispatchHttpResponse(
                            response.getStatusCode().value(), response.getHeaders()));
        } catch (RestClientException e) {
            throw new EngineException("completeTask (POST " + path + ") failed: "
                    + e.getMessage(), e);
        }
    }

    protected record DispatchHttpResponse(
            int status, org.springframework.http.HttpHeaders headers) {
    }

    private static final class PartiallyCreatedTaskException extends EngineException {
        private final String taskId;
        private final int primaryHttpStatus;
        private final CommandDispatchOutcome.RepairSource repairSource;

        private PartiallyCreatedTaskException(
                String taskId, int primaryHttpStatus,
                CommandDispatchOutcome.RepairSource repairSource, EngineException cause) {
            super("createHumanTask primary task '" + taskId
                    + "' was created before a later side effect failed", cause);
            this.taskId = taskId;
            this.primaryHttpStatus = primaryHttpStatus;
            this.repairSource = repairSource;
        }
    }

    private BodyDispatchResponse postExactStartForDispatch(
            String processDefinitionId, Object body) {
        try {
            org.springframework.http.ResponseEntity<Map> response = client.post()
                    .uri(builder -> builder
                            .path("/process-definition/{processDefinitionId}/start")
                            .build(processDefinitionId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            return new BodyDispatchResponse(response.getBody(),
                    response.getStatusCode().value(), response.getHeaders());
        } catch (RestClientResponseException e) {
            throw new EngineException("startProcess (POST exact " + processDefinitionId + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("startProcess (POST exact " + processDefinitionId
                    + ") failed: " + e.getMessage(), e);
        }
    }

    private BodyDispatchResponse postKeyStartForDispatch(
            String processDefinitionKey, String tenantId, Object body) {
        boolean tenantQualified = tenantId != null && !tenantId.isBlank();
        String diagnostic = tenantQualified
                ? "/process-definition/key/{key}/tenant-id/{tenant}/start"
                : "/process-definition/key/{key}/start";
        try {
            org.springframework.http.ResponseEntity<Map> response = client.post()
                    .uri(builder -> tenantQualified
                            ? builder.path("/process-definition/key/{key}/tenant-id/{tenant}/start")
                                    .build(processDefinitionKey, tenantId)
                            : builder.path("/process-definition/key/{key}/start")
                                    .build(processDefinitionKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            return new BodyDispatchResponse(response.getBody(),
                    response.getStatusCode().value(), response.getHeaders());
        } catch (RestClientResponseException e) {
            throw new EngineException("startProcessByKey (POST " + diagnostic + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("startProcessByKey (POST " + diagnostic + ") failed: "
                    + e.getMessage(), e);
        }
    }

    private static EngineProcessRef processRef(
            Map<String, Object> response, String expectedProcessDefinitionId,
            String processDefinitionKey, String caseId, boolean requireResponseDefinitionId) {
        String processInstanceId = response == null ? null : string(response.get("id"));
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw new EngineException("Engine start returned no process-instance id");
        }
        String responseDefinitionId = response == null ? null : string(response.get("definitionId"));
        if (responseDefinitionId != null && responseDefinitionId.isBlank()) {
            responseDefinitionId = null;
        }
        if (expectedProcessDefinitionId != null && responseDefinitionId != null
                && !expectedProcessDefinitionId.equals(responseDefinitionId)) {
            throw new EngineException("Engine start returned an inconsistent process-definition id");
        }
        String processDefinitionId = expectedProcessDefinitionId == null
                ? responseDefinitionId : expectedProcessDefinitionId;
        if (requireResponseDefinitionId && processDefinitionId == null) {
            throw new EngineException("Engine start returned no process-definition id");
        }
        return new EngineProcessRef(processInstanceId, processDefinitionId,
                processDefinitionKey, caseId);
    }

    /** engine-rest wants typed value descriptors rather than plain values. */
    private Map<String, Object> typed(Map<String, Object> variables) {
        Map<String, Object> typed = new LinkedHashMap<>();
        variables.forEach((k, v) -> {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("value", v);
            descriptor.put("type", switch (v) {
                    case Integer i -> "Integer";
                    case Long l -> "Long";
                    case Boolean b -> "Boolean";
                    case Double d -> "Double";
                    case null -> "Null";
                    default -> "String";
                });
            typed.put(k, descriptor);
        });
        return typed;
    }
}
