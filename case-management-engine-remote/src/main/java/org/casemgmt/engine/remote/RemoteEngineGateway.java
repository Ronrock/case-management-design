package org.casemgmt.engine.remote;

import org.casemgmt.engine.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Talks to a remote Operaton over engine-rest (spec §3.5 remote mode).
 *
 * Calls here are NOT in the case transaction. Callers must therefore reach this
 * class through the command outbox (Task 13), never directly from a request thread.
 */
public class RemoteEngineGateway implements EngineGateway {

    public static final String CASE_ID_VARIABLE = "caseId";
    private static final String PLAN_ITEM_VARIABLE = "planItemId";

    /**
     * engine-rest serialises date fields like {@code "2023-05-13T12:14:12.000+0200"} — an
     * offset with no colon between hours and minutes. {@link OffsetDateTime#parse(CharSequence)}
     * uses {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} by default, which requires the colon
     * and rejects this format. An explicit pattern is required; a parse failure here must
     * propagate (never be swallowed into a null createdAt — see EngineGatewayContract).
     */
    private static final DateTimeFormatter ENGINE_REST_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final RestClient client;

    public RemoteEngineGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        // POST /task/create returns 204 No Content — no body, so the created task's id
        // cannot be read back from the response. It DOES accept a client-supplied "id",
        // however, so a client-generated id is used and then confirmed by reading the task
        // straight back (both to recover engine-assigned fields like "created", and to fail
        // fast/loud if the id was silently ignored rather than honoured).
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("id", taskId);
        createBody.put("name", request.name());
        if (request.assignee() != null) {
            createBody.put("assignee", request.assignee());
        }
        post("/task/create", createBody);

        if (request.candidateGroups() != null) {
            for (String group : request.candidateGroups()) {
                post("/task/" + taskId + "/identity-links",
                        Map.of("groupId", group, "type", "candidate"));
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        post("/task/" + taskId + "/variables", Map.of("modifications", typed(variables)));

        Map<String, Object> readBack = get("/task/" + taskId);
        return toRef(readBack, request.caseId());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        post("/task/" + engineTaskId + "/claim", Map.of("userId", userId));
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        post("/task/" + engineTaskId + "/complete",
                Map.of("variables", typed(variables == null ? Map.of() : variables)));
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());

        Map<String, Object> response = post(
                "/process-definition/key/" + request.processDefinitionKey() + "/start",
                Map.of("businessKey", request.caseId(), "variables", typed(variables)));

        return new EngineProcessRef(String.valueOf(response.get("id")), request.processDefinitionKey());
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        try {
            client.delete()
                    .uri("/process-instance/{id}?skipCustomListeners=false", processInstanceId)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new EngineException("Could not cancel process " + processInstanceId
                    + ": " + e.getStatusCode(), e);
        }
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
            // query filtering only one scope would).
            Map<String, Object> caseIdFilter = Map.of(
                    "taskVariables", List.of(Map.of(
                            "name", CASE_ID_VARIABLE, "value", query.caseId(), "operator", "eq")),
                    "processVariables", List.of(Map.of(
                            "name", CASE_ID_VARIABLE, "value", query.caseId(), "operator", "eq")));
            body.put("orQueries", List.of(caseIdFilter));
        }
        try {
            List<Map<String, Object>> tasks = client.post()
                    .uri("/task?maxResults={max}", query.maxResults() <= 0 ? 50 : query.maxResults())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(List.class);

            return tasks == null ? List.of() : tasks.stream()
                    .map(t -> toRef(t, query.caseId()))
                    .toList();
        } catch (RestClientResponseException e) {
            throw new EngineException("Task query failed: " + e.getStatusCode(), e);
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

    private OffsetDateTime parseCreatedAt(Object value) {
        if (value == null) {
            return null;
        }
        // Deliberately not caught: a DateTimeParseException here must fail the call loudly
        // rather than be swallowed into a null createdAt (see EngineGatewayContract's note on
        // this being exactly how the two modes would diverge unnoticed).
        return OffsetDateTime.parse((String) value, ENGINE_REST_DATE_TIME);
    }

    private Map<String, Object> get(String path) {
        try {
            return client.get().uri(path)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("Engine call " + path + " failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private Map<String, Object> post(String path, Object body) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("Engine call " + path + " failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
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
