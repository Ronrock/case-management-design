package org.casemgmt.poc;

import org.casemgmt.poc.support.PocAppEmbeddedTestBase;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PoC's own definition of done, embedded-mode half (spec's headline claim, O2): the
 * {@code complaint} case type — the real definition {@link PocBootstrap} deploys from
 * {@code definitions/complaint-v1.json}, the real seeded users/groups, the real
 * {@code decision-letter} BPMN process — runs end to end over real HTTP, against a real Operaton
 * engine embedded in the real, unmodified {@link PocApplication}, against real Oracle.
 *
 * <p>This is the one file in the whole reactor allowed to say {@code complaint}
 * (case-type vocabulary is scoped to this module only) — see {@link GenericConsumerIT} for the
 * companion test that proves the API needs none of this knowledge at all.
 *
 * <p>Supersedes Task 24's deferred, never-committed draft of the same name (see that task's
 * report, deviation D5): that draft could not run before this module had an
 * {@code @SpringBootApplication}, and its scenario is reproduced and extended here against the
 * real complaint plan model rather than a placeholder.
 *
 * <p><b>Design gap, not a defect (Fix round 1, review Important 1):</b> a {@code PROCESS_TASK}
 * plan item's {@code processDefinitionKey} is parsed, stored on {@code PlanItemDefinition} and
 * never read again — nothing in {@code TransitionApplier}, {@code CaseService} or {@code
 * PlanModelInstantiator} reacts to {@code sendDecisionLetter} becoming {@code ACTIVE} by starting
 * it. The plan never specified that a {@code PROCESS_TASK} auto-starts its process (checked
 * before writing this note), so this is not something to wire up here. It does mean the ONLY way
 * {@code decision-letter.bpmn} is ever instantiated is an explicit {@code POST
 * /cases/{caseId}/processes} — which {@link #theComplaintCasePathRunsEndToEndInEmbeddedModeAndCloses()}
 * below now genuinely calls, driving both of the process's own user tasks to completion through
 * the embedded engine's {@link org.operaton.bpm.engine.TaskService} — and that completing the
 * process does NOT complete {@code sendDecisionLetter}'s plan item either; the test completes it
 * separately, standing in for whatever a real deployment would use (a BPMN completion listener,
 * an operator, a webhook consumer) to close that loop. Recorded here for Task 27's
 * {@code FINDINGS.md}.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiIT extends PocAppEmbeddedTestBase {

    // Real Operaton beans from the SAME embedded-mode context — used only to drive
    // decision-letter.bpmn's own two user tasks (see theDecisionLetterProcessIsActuallyStartedAndRuns
    // below). Nothing in this module's production code reacts to a BPMN process completing —
    // see that test's Javadoc — so this is the only way to prove the process itself runs.
    @Autowired TaskService taskService;
    @Autowired RuntimeService runtimeService;

    @Test
    void pocBootstrapSeedsTheComplaintDefinitionUsersAndGroups() {
        ResponseEntity<List> definitions = client("alice").get()
                .uri("/case-api/v2/case-definitions?tenantId=t1")
                .retrieve().toEntity(List.class);
        assertThat(definitions.getStatusCode().value()).isEqualTo(200);
        assertThat(definitions.getBody()).extracting(d -> ((Map) d).get("key")).contains("complaint");

        ResponseEntity<Map> detail = client("alice").get()
                .uri("/case-api/v2/case-definitions/complaint")
                .retrieve().toEntity(Map.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat((List) detail.getBody().get("roles"))
                .containsExactlyInAnyOrder("owner", "handler", "reviewer", "watcher");
        assertThat((List) detail.getBody().get("formKeys"))
                .containsExactlyInAnyOrder("registerForm", "assessForm", "investigateForm", "closeForm");

        // Users seeded by PocBootstrap can authenticate at all — a bad password is refused,
        // proving checkPassword is genuinely consulted rather than any authenticated-looking
        // request being waved through.
        ResponseEntity<Map> wrongPassword = client("alice-does-not-exist").get()
                .uri("/case-api/v2/cases").retrieve().toEntity(Map.class);
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
    }

    /**
     * Guards the human-ruled fix that {@code /engine-rest} must require authentication (Task 26,
     * Important 4) against silent reversion — the obligation carried into Task 27.
     *
     * <p>The pre-existing coverage could not do this. {@code RemoteModeComplaintIT}'s
     * wrong-password test proves credentials are CHECKED, but it would pass identically under the
     * old {@code .anyRequest().permitAll()} configuration: {@code BasicAuthenticationFilter}
     * rejects a malformed-credential {@code Authorization} header with 401 before any
     * authorization rule is consulted, so the request never reaches the matcher. Revert
     * {@code PocSecurityConfig}'s matcher to {@code permitAll} and every other test in the module
     * stays green. This one does not: with no {@code Authorization} header at all there is nothing
     * for the authentication filter to reject, and only the matcher can produce a 401.
     *
     * <p>The second half is attribution, not coverage: the SAME request with valid credentials is
     * NOT refused, so the 401 above is Spring Security turning away an anonymous caller and not a
     * missing endpoint, an unmapped {@code /engine-rest} servlet or a broken engine — any of which
     * would also produce a non-200 and satisfy a bare "not authorized" assertion.
     *
     * <p>It asserts "not 401" rather than "200" deliberately. Operaton's own answer to a bare
     * {@code GET /task} depends on what is in the shared engine database when it runs: 200 when
     * this class runs alone, 400 in a full-module run after the other classes have created tasks
     * against the same schema. That variability is Operaton's business, not this test's — pinning
     * it would make this test fail for a reason that has nothing to do with the matcher it guards.
     * The authentication outcome is stable in both cases and is the whole subject here.
     */
    @Test
    void engineRestRefusesUnauthenticatedRequests() {
        var anonymous = org.springframework.web.client.RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        ResponseEntity<String> unauthenticated = anonymous.get().uri("/engine-rest/task")
                .retrieve().toEntity(String.class);
        assertThat(unauthenticated.getStatusCode().value())
                .as("GET /engine-rest/task with no credentials must be refused by the security "
                        + "matcher; a 200 here means /engine-rest is open to the world again")
                .isEqualTo(401);

        ResponseEntity<String> authenticated = client("admin").get().uri("/engine-rest/task")
                .retrieve().toEntity(String.class);
        assertThat(authenticated.getStatusCode().value())
                .as("the identical request with valid credentials is not refused, so the 401 above "
                        + "is attributable to authentication and nothing else; body was: %s",
                        authenticated.getBody())
                .isNotEqualTo(401);
    }

    @Test
    void engineRestWritesAreReservedForTheEngineIntegrationCredential() {
        ResponseEntity<String> ordinaryUser = client("alice").post()
                .uri("/engine-rest/task/not-a-real-task/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().toEntity(String.class);
        assertThat(ordinaryUser.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> integrationCredential = client("admin").post()
                .uri("/engine-rest/task/not-a-real-task/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().toEntity(String.class);
        assertThat(integrationCredential.getStatusCode().value())
                .as("admin must reach Operaton rather than be stopped by the case API security layer")
                .isNotIn(401, 403);
    }

    @Test
    void theComplaintCasePathRunsEndToEndInEmbeddedModeAndCloses() {
        // ---- brief's Step 6, automated ----
        ResponseEntity<Map> created = client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "poc-it-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Broken widget", "priority", "HIGH"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getETag()).isNotNull();
        String caseId = (String) created.getBody().get("id");

        List<Map<String, Object>> createActions = (List<Map<String, Object>>) created.getBody().get("availableActions");
        assertThat(createActions).extracting(a -> a.get("action")).contains("update", "cancel");
        assertThat(createActions).extracting(a -> a.get("action")).doesNotContain("close");

        Map<String, Object> registerTask = findTask(caseId, "Register complaint");
        assertThat(registerTask.get("engineSync")).isEqualTo("SYNCED");
        assertThat((List) registerTask.get("candidateGroups")).contains("intake");

        // ---- registerComplaint ----
        claimAndComplete((String) registerTask.get("id"),
                Map.of("channel", "web", "summary", "Item arrived broken"));
        // Milestone/plan-item "name" is the definition's defKey, not its display "name" — a
        // real quirk of PlanModelInstantiator (see its Javadoc / Task 24 fix round 3's report),
        // not a typo here: CM_TASK rows use the display name (TransitionApplier.createHumanTask
        // passes def.name()), but PlanItem/milestone rows are built from item.name(), which
        // PlanModelInstantiator sets to the plan item's defKey.
        assertThat(findMilestone(caseId, "acknowledged").get("achieved")).isEqualTo(true);

        // ---- assessComplaint ----
        Map<String, Object> assessTask = findTask(caseId, "Assess complaint");
        assertThat((List) assessTask.get("candidateGroups")).contains("handlers");
        claimAndComplete((String) assessTask.get("id"), Map.of("outcome", "upheld"));

        // ---- sendDecisionLetter: a PROCESS_TASK plan item, no CM_TASK behind it ----
        //
        // Fix round 1, Important 1 (review): nothing in TransitionApplier, CaseService or
        // PlanModelInstantiator reacts to a PROCESS_TASK plan item becoming ACTIVE — the plan
        // never specified that link (see this class's own Javadoc and the report's design-gap
        // section). An earlier draft of this test just called completePlanItem() directly here,
        // which proved nothing about decision-letter.bpmn beyond "it deployed without throwing".
        // This now genuinely starts the linked process through the one endpoint that exists for
        // it (POST /cases/{caseId}/processes) and drives its own two user tasks to completion
        // through Operaton's own TaskService — the same embedded engine this whole test already
        // runs against — before completing the plan item by the same manual mechanism as before.
        Map<String, Object> decisionLetterItem = findPlanItem(caseId, "sendDecisionLetter");
        assertThat(decisionLetterItem.get("state")).isEqualTo("ACTIVE");
        assertThat((List<Map<String, Object>>) decisionLetterItem.get("availableActions"))
                .extracting(a -> a.get("action")).contains("complete");

        ResponseEntity<Map> startedProcess = client("alice").post()
                .uri("/case-api/v2/cases/{caseId}/processes", caseId)
                .header("If-Match", "*").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "decision-letter",
                        "planItemId", decisionLetterItem.get("id")))
                .retrieve().toEntity(Map.class);
        assertThat(startedProcess.getStatusCode().value()).isEqualTo(201);
        assertThat(startedProcess.getBody().get("engineSync")).isEqualTo("SYNCED"); // embedded: synchronous
        String processInstanceId = (String) startedProcess.getBody().get("processInstanceId");
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isEqualTo(1);

        Task draft = onlyActiveTask(processInstanceId);
        assertThat(draft.getName()).isEqualTo("Draft decision letter");
        taskService.complete(draft.getId());
        Task send = onlyActiveTask(processInstanceId);
        assertThat(send.getName()).isEqualTo("Send letter");
        taskService.complete(send.getId());

        // The BPMN process genuinely ran to completion on the real engine — independent of
        // anything this test wrote into CM_ tables.
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isEqualTo(0);

        // Completing the process does NOT complete the plan item — see the Javadoc above, and
        // the report's design-gap section: that link does not exist. Driving it here is this
        // test standing in for whatever, in a real deployment, would call this endpoint (a BPMN
        // completion listener, an operator, a webhook consumer) once the letter is actually sent.
        completePlanItem(caseId, (String) decisionLetterItem.get("id"));
        assertThat(findMilestone(caseId, "decided").get("achieved")).isEqualTo(true);

        // ---- closeComplaint ----
        Map<String, Object> closeTask = findTask(caseId, "Close complaint");
        claimAndComplete((String) closeTask.get("id"), Map.of("outcome", "resolved"));

        // Every required item is now finished, so "close" is offered — but closing is a
        // distinct, explicit action (spec: caseCanClose only governs whether it is OFFERED),
        // not an automatic side effect of the last required item finishing.
        Map<String, Object> beforeClose = client("alice").get()
                .uri("/case-api/v2/cases/{id}", caseId).retrieve().toEntity(Map.class).getBody();
        assertThat((List<Map<String, Object>>) beforeClose.get("availableActions"))
                .extracting(a -> a.get("action")).contains("close");
        ResponseEntity<Map> closed = client("alice").post()
                .uri("/case-api/v2/cases/{id}/close", caseId).header("If-Match", "*")
                .retrieve().toEntity(Map.class);
        assertThat(closed.getStatusCode().value()).isEqualTo(200);

        // ---- the case is now closed, and offers nothing further ----
        ResponseEntity<Map> finalCase = client("alice").get()
                .uri("/case-api/v2/cases/{id}", caseId).retrieve().toEntity(Map.class);
        assertThat(finalCase.getBody().get("state")).isEqualTo("CLOSED");
        assertThat((List) finalCase.getBody().get("availableActions")).isEmpty();
    }

    @Test
    void bpmnComplaintClosesAutomaticallyWhenItsRootProcessEnds() {
        ResponseEntity<Map> created = client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "bpmn-poc-it-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint-bpmn", "tenantId", "t1",
                        "title", "BPMN complaint", "priority", "HIGH"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String caseId = created.getBody().get("id").toString();
        assertThat(created.getBody().get("rootProcessInstanceId")).isNotNull();
        assertThat((List<Map<String, Object>>) created.getBody().get("availableActions"))
                .extracting(action -> action.get("action")).doesNotContain("close");

        Map<String, Object> register = findTask(caseId, "Register complaint");
        assertThat(register.get("formKey")).isEqualTo("registerForm");
        assertThat((List<String>) register.get("candidateGroups")).contains("intake");
        claimAndComplete(register.get("id").toString(),
                Map.of("channel", "web", "summary", "BPMN journey"));

        assertThat(findMilestone(caseId, "Acknowledged").get("achieved")).isEqualTo(true);
        Map<String, Object> assess = findTask(caseId, "Assess complaint");
        claimAndComplete(assess.get("id").toString(), Map.of("outcome", "upheld"));
        assertThat(findMilestone(caseId, "Decided").get("achieved")).isEqualTo(true);

        Map<String, Object> close = findTask(caseId, "Close complaint");
        claimAndComplete(close.get("id").toString(), Map.of("outcome", "resolved"));

        Map<String, Object> finished = client("alice").get()
                .uri("/case-api/v2/cases/{id}", caseId).retrieve().toEntity(Map.class).getBody();
        assertThat(finished.get("state")).isEqualTo("CLOSED");
        assertThat(finished.get("projectionStatus")).isEqualTo("CURRENT");
    }

    // ---- helpers (this file, and only this file, may know complaint's own shape) ----

    private Map<String, Object> findTask(String caseId, String name) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/tasks", caseId).retrieve().toEntity(List.class).getBody();
        return tasks.stream().filter(t -> name.equals(t.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No task named '" + name + "' on case " + caseId
                        + "; tasks were: " + tasks));
    }

    private Map<String, Object> findPlanItem(String caseId, String name) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/plan-items", caseId).retrieve().toEntity(List.class).getBody();
        return items.stream().filter(i -> name.equals(i.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No plan item named '" + name + "' on case " + caseId
                        + "; items were: " + items));
    }

    private Map<String, Object> findMilestone(String caseId, String name) {
        List<Map<String, Object>> milestones = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/milestones", caseId).retrieve().toEntity(List.class).getBody();
        return milestones.stream().filter(m -> name.equals(m.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No milestone named '" + name + "' on case " + caseId
                        + "; milestones were: " + milestones));
    }

    /** {@code If-Match: *} throughout — legal per RFC 7232 §3.1, and simpler than tracking a
     * per-resource version through a chain of calls this test does not otherwise care about. */
    private void claimAndComplete(String taskId, Map<String, Object> variables) {
        ResponseEntity<Map> claimed = client("alice").post()
                .uri("/case-api/v2/tasks/{id}/claim", taskId)
                .header("If-Match", "*").retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> completed = client("alice").post()
                .uri("/case-api/v2/tasks/{id}/complete", taskId)
                .header("If-Match", "*").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", variables))
                .retrieve().toEntity(Map.class);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
    }

    /** Exactly one task should ever be active on decision-letter.bpmn's own sequential flow. */
    private Task onlyActiveTask(String processInstanceId) {
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        assertThat(tasks).as("decision-letter.bpmn's active task for process %s", processInstanceId)
                .hasSize(1);
        return tasks.get(0);
    }

    private void completePlanItem(String caseId, String itemId) {
        ResponseEntity<Map> r = client("alice").post()
                .uri("/case-api/v2/cases/{caseId}/plan-items/{itemId}/complete", caseId, itemId)
                .header("If-Match", "*").retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }
}
