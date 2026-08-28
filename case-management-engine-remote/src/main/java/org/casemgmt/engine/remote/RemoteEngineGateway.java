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
public class RemoteEngineGateway implements EngineGateway, OrchestrationDeploymentClient {

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

    public RemoteEngineGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public EngineDeploymentIdentity deploy(String releaseId, String definitionKey, String tenantId,
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
            Map<String, Object> response = client.post().uri("/deployment/create")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object id = response == null ? null : response.get("id");
            if (id == null || id.toString().isBlank()) {
                throw new EngineException("deployOrchestration returned no deployment id");
            }
            return verifyDeployment(id.toString(), definitionKey, tenantId, approved);
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
                ? UUID.randomUUID().toString() : "cm-command-" + request.requestId();
        if (request.requestId() != null) {
            Map<String, Object> existing = getIfPresent("/task/" + taskId);
            if (existing != null) {
                return toRef(existing, request.caseId());
            }
        }
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("id", taskId);
        createBody.put("name", request.name());
        if (request.assignee() != null) {
            createBody.put("assignee", request.assignee());
        }
        post("createHumanTask", "/task/create", createBody);

        if (request.candidateGroups() != null) {
            for (String group : request.candidateGroups()) {
                post("createHumanTask (candidate group)", "/task/" + taskId + "/identity-links",
                        Map.of("groupId", group, "type", "candidate"));
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        post("createHumanTask (variables)", "/task/" + taskId + "/variables",
                Map.of("modifications", typed(variables)));

        Map<String, Object> readBack = get("createHumanTask (read-back)", "/task/" + taskId);
        return toRef(readBack, request.caseId());
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

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
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

        Map<String, Object> response = postExactStart(request.processDefinitionId(),
                Map.of("businessKey", request.caseId(), "variables", typed(variables)));

        return processRef(response, request.processDefinitionId(),
                string(definition.get("key")), request.caseId(), false);
    }

    @Override
    public EngineProcessRef startProcessByKey(StartProcessByKeyRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>(request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());

        Map<String, Object> response = postKeyStart(
                request.processDefinitionKey(), request.tenantId(),
                Map.of("businessKey", request.caseId(), "variables", typed(variables)));

        return processRef(response, null, request.processDefinitionKey(), request.caseId(), true);
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        String path = "/process-instance/" + processInstanceId + "?skipCustomListeners=false";
        try {
            client.delete().uri(path).retrieve().toBodilessEntity();
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
        post("correlateMessage", "/message", Map.of(
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

    private Map<String, Object> getIfPresent(String path) {
        try {
            return client.get().uri(path).retrieve().body(Map.class);
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

    private Map<String, Object> postExactStart(String processDefinitionId, Object body) {
        try {
            return client.post().uri(builder -> builder
                            .path("/process-definition/{processDefinitionId}/start")
                            .build(processDefinitionId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("startProcess (POST exact " + processDefinitionId + ") failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new EngineException("startProcess (POST exact " + processDefinitionId
                    + ") failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> postKeyStart(String processDefinitionKey,
                                             String tenantId, Object body) {
        boolean tenantQualified = tenantId != null && !tenantId.isBlank();
        String diagnostic = tenantQualified
                ? "/process-definition/key/{key}/tenant-id/{tenant}/start"
                : "/process-definition/key/{key}/start";
        try {
            return client.post().uri(builder -> tenantQualified
                            ? builder.path("/process-definition/key/{key}/tenant-id/{tenant}/start")
                                    .build(processDefinitionKey, tenantId)
                            : builder.path("/process-definition/key/{key}/start")
                                    .build(processDefinitionKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
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

    /** engine-rest wants {"name": {"value": v, "type": "String"}} rather than plain values. */
    private Map<String, Object> typed(Map<String, Object> variables) {
        Map<String, Object> typed = new LinkedHashMap<>();
        variables.forEach((k, v) -> typed.put(k, Map.of(
                "value", v == null ? "" : v,
                "type", switch (v) {
                    case Integer i -> "Integer";
                    case Long l -> "Long";
                    case Boolean b -> "Boolean";
                    case Double d -> "Double";
                    case null, default -> "String";
                })));
        return typed;
    }
}
