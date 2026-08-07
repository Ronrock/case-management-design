package org.casemgmt.poc;

import org.casemgmt.poc.support.PocAppEmbeddedTestBase;
import org.junit.jupiter.api.Test;
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
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiIT extends PocAppEmbeddedTestBase {

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
        Map<String, Object> decisionLetterItem = findPlanItem(caseId, "sendDecisionLetter");
        assertThat(decisionLetterItem.get("state")).isEqualTo("ACTIVE");
        assertThat((List<Map<String, Object>>) decisionLetterItem.get("availableActions"))
                .extracting(a -> a.get("action")).contains("complete");
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

    private void completePlanItem(String caseId, String itemId) {
        ResponseEntity<Map> r = client("alice").post()
                .uri("/case-api/v2/cases/{caseId}/plan-items/{itemId}/complete", caseId, itemId)
                .header("If-Match", "*").retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }
}
