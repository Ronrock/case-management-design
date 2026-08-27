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
 * Characterization coverage for legacy {@code PLAN_MODEL} case types (Workstream 1, Task 6).
 *
 * <p>Workstream 1 changed the publication path that every case type shares: contract releases now
 * pass JSON Schema validation, BPMN extensions are read from one namespace only, and binding
 * requires an explicitly declared orchestration mode. None of that is supposed to reach a
 * plan-model definition — the global constraint for this workstream is "preserve existing
 * {@code PLAN_MODEL} behavior and public APIs" — but "supposed to" is what characterization tests
 * are for.
 *
 * <p>The assertions below are deliberately about <em>observable behavior through the public API</em>
 * rather than internals: the seeded legacy definition still loads with its plan items, a case
 * still starts from it, the evaluator still instantiates plan items, a task still claims and
 * completes, and the case still reaches {@code CLOSED}. If a later workstream narrows the shared
 * publication path in a way that catches plan-model definitions, this fails on the specific step
 * that broke rather than somewhere far downstream.
 *
 * <p>{@code complaint} is the plan-model definition {@code PocBootstrap} seeds;
 * {@code complaint-bpmn} is its BPMN-first sibling. Both are deployed in the same application, so
 * this also demonstrates the two orchestration modes coexisting, which is the migration story
 * Workstream 1 depends on.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class PlanModelCompatibilityIT extends PocAppEmbeddedTestBase {

    @Test
    void theLegacyPlanModelDefinitionStillLoadsWithItsPlanItems() {
        ResponseEntity<Map> definition = client("alice").get()
                .uri("/case-api/v2/case-definitions/complaint")
                .retrieve().toEntity(Map.class);

        assertThat(definition.getStatusCode().value()).isEqualTo(200);
        assertThat(definition.getBody().get("key")).isEqualTo("complaint");
        // The plan model is the orchestration for this definition; an empty list here would mean
        // the shared publication path had started treating it as BPMN-driven.
        assertThat((List<Map<String, Object>>) definition.getBody().get("planItems"))
                .isNotEmpty()
                .extracting(item -> item.get("defKey"))
                .contains("registerComplaint", "assessComplaint", "closeComplaint");
    }

    /**
     * Both modes are deployed side by side. Workstream 1 selects orchestration per case type, so
     * a legacy definition surviving in isolation is not enough — it has to survive alongside the
     * BPMN-first one it will eventually be migrated to.
     */
    @Test
    void bothOrchestrationModesAreDeployableInTheSameApplication() {
        List<Map<String, Object>> definitions = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/case-definitions?tenantId=t1")
                .retrieve().toEntity(List.class).getBody();

        assertThat(definitions).extracting(definition -> definition.get("key"))
                .contains("complaint", "complaint-bpmn");
    }

    /**
     * The full legacy lifecycle: start, plan-item instantiation, task claim/complete, milestone
     * achievement, and explicit closure. This is the path Workstream 1 must leave untouched.
     */
    @Test
    void aLegacyCaseStartsRunsItsTasksAndCloses() {
        ResponseEntity<Map> created = client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "plan-model-compat-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Legacy compatibility", "priority", "MEDIUM"))
                .retrieve().toEntity(Map.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String caseId = created.getBody().get("id").toString();

        // A plan-model case is driven by the platform's evaluator, so it has no root process
        // instance. That absence is the observable difference from a BPMN-first case.
        assertThat(created.getBody().get("rootProcessInstanceId")).isNull();

        // The evaluator instantiated the plan on creation.
        List<Map<String, Object>> planItems = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class).getBody();
        assertThat(planItems).isNotEmpty();

        claimAndComplete(taskNamed(caseId, "Register complaint"),
                Map.of("channel", "web", "summary", "Legacy path still works"));
        assertThat(milestoneNamed(caseId, "acknowledged").get("achieved")).isEqualTo(true);

        claimAndComplete(taskNamed(caseId, "Assess complaint"), Map.of("outcome", "upheld"));

        // sendDecisionLetter is a PROCESS_TASK whose plan item is completed explicitly — the
        // engine does not close it back (see CaseApiIT's Javadoc on that design gap). Completing
        // it here keeps this test on the documented legacy behavior rather than asserting a link
        // that has never existed.
        Map<String, Object> decisionLetter = planItemNamed(caseId, "sendDecisionLetter");
        completePlanItem(caseId, decisionLetter.get("id").toString());

        claimAndComplete(taskNamed(caseId, "Close complaint"), Map.of("outcome", "resolved"));

        ResponseEntity<Map> closed = client("alice").post()
                .uri("/case-api/v2/cases/{id}/close", caseId).header("If-Match", "*")
                .retrieve().toEntity(Map.class);
        assertThat(closed.getStatusCode().value()).isEqualTo(200);
        assertThat(client("alice").get().uri("/case-api/v2/cases/{id}", caseId)
                .retrieve().toEntity(Map.class).getBody().get("state")).isEqualTo("CLOSED");
    }

    // ---------------------------------------------------------------- helpers

    private String taskNamed(String caseId, String name) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class).getBody();
        return tasks.stream().filter(task -> name.equals(task.get("name")))
                .map(task -> task.get("id").toString()).findFirst()
                .orElseThrow(() -> new AssertionError("No task named '" + name + "' on case "
                        + caseId + "; tasks were: " + tasks));
    }

    private Map<String, Object> planItemNamed(String caseId, String name) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class).getBody();
        return items.stream().filter(item -> name.equals(item.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No plan item named '" + name + "' on case "
                        + caseId + "; items were: " + items));
    }

    private Map<String, Object> milestoneNamed(String caseId, String name) {
        List<Map<String, Object>> milestones = (List<Map<String, Object>>) client("alice").get()
                .uri("/case-api/v2/cases/{id}/milestones", caseId)
                .retrieve().toEntity(List.class).getBody();
        return milestones.stream().filter(milestone -> name.equals(milestone.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No milestone named '" + name + "' on case "
                        + caseId + "; milestones were: " + milestones));
    }

    private void claimAndComplete(String taskId, Map<String, Object> variables) {
        assertThat(client("alice").post().uri("/case-api/v2/tasks/{id}/claim", taskId)
                .header("If-Match", "*").retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(client("alice").post().uri("/case-api/v2/tasks/{id}/complete", taskId)
                .header("If-Match", "*").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", variables))
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(200);
    }

    private void completePlanItem(String caseId, String planItemId) {
        assertThat(client("alice").post()
                .uri("/case-api/v2/cases/{caseId}/plan-items/{itemId}/complete", caseId, planItemId)
                .header("If-Match", "*").retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(200);
    }
}
