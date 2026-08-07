package org.casemgmt.rest.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carried finding C1 — the authorization the services deliberately do not perform.
 *
 * <p>{@code CaseService}, {@code PlanItemService} and {@code CaseTaskService} check state and
 * version and nothing about identity, by design. {@code ActionPolicy}, wired in by the
 * controllers, is the whole of the enforcement. Task 23's own review found a Critical here
 * precisely because every test in that task passed a privileged role, so these tests are
 * built the other way round: each one contrasts a caller who must be refused against a caller
 * who must succeed, on the same URL, with the same body.
 *
 * <p><b>Every refusal below is traced to the code that produced it.</b> The refusals are 409
 * {@code action-not-available} — {@code ActionPolicy}'s own {@code CaseConflictException} — and
 * each test also shows the same caller getting a 200 from a read on the same resource. That
 * rules out the three ways an authorization test can pass vacuously: a 404 from an unmatched
 * route (the route matches, the read proves it), a 403 from the security filter chain (the test
 * configuration authorizes every authenticated request and nothing else), and a 401 (the caller
 * authenticates fine).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiAuthorizationTest extends CaseApiHttpTestBase {

    @Test
    void aNonParticipantCanReadACaseButIsOfferedNoActionsAndRefusedThemAll() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        // The route matches, carol authenticates, and she can read the case. Anything refused
        // below is therefore refused by the policy, not by routing or by the filter chain.
        ResponseEntity<Map> read = client("carol").get().uri("/cases/{id}", id)
                .retrieve().toEntity(Map.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody()).containsEntry("id", id);
        assertThat((List<?>) read.getBody().get("availableActions"))
                .as("the projection and the enforcement must agree: no actions offered")
                .isEmpty();

        ResponseEntity<Map> patch = client("carol").patch().uri("/cases/{id}", id)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "Hijacked"))
                .retrieve().toEntity(Map.class);
        assertThat(patch.getStatusCode().value()).isEqualTo(409);
        assertThat(patch.getBody()).containsEntry("code", "action-not-available");
        assertThat((List<?>) patch.getBody().get("availableActions")).isEmpty();

        ResponseEntity<Map> cancel = client("carol").post().uri("/cases/{id}/cancel", id)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "because"))
                .retrieve().toEntity(Map.class);
        assertThat(cancel.getStatusCode().value()).isEqualTo(409);
        assertThat(cancel.getBody()).containsEntry("code", "action-not-available");

        // Nothing was written: the owner still sees the original title at the original version.
        ResponseEntity<Map> ownersView = alice().get().uri("/cases/{id}", id)
                .retrieve().toEntity(Map.class);
        assertThat(ownersView.getBody()).containsEntry("title", created.get("title"))
                .containsEntry("state", "ACTIVE").containsEntry("version", 0);

        // The same PATCH from the owner succeeds — so the 409 above is about who asked, not
        // about the request being malformed or the case being unpatchable.
        ResponseEntity<Map> ownersPatch = alice().patch().uri("/cases/{id}", id)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "Hijacked"))
                .retrieve().toEntity(Map.class);
        assertThat(ownersPatch.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aNonParticipantCannotDrivePlanItems() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        Map<String, Object> item = activePlanItem(caseId);
        String itemId = (String) item.get("id");
        String etag = "\"" + ((Number) item.get("version")).longValue() + "\"";

        ResponseEntity<List> carolsView = client("carol").get()
                .uri("/cases/{id}/plan-items", caseId).retrieve().toEntity(List.class);
        assertThat(carolsView.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) carolsView.getBody())
                .allSatisfy(i -> assertThat((List<?>) i.get("availableActions")).isEmpty());

        ResponseEntity<Map> terminate = client("carol").post()
                .uri("/cases/{c}/plan-items/{i}/terminate", caseId, itemId)
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "no"))
                .retrieve().toEntity(Map.class);
        assertThat(terminate.getStatusCode().value()).isEqualTo(409);
        assertThat(terminate.getBody()).containsEntry("code", "action-not-available");

        // Still ACTIVE — the refusal stopped the write, it did not merely report on it.
        assertThat(activePlanItem(caseId)).containsEntry("id", itemId);

        // And the owner's identical call goes through.
        ResponseEntity<Map> ownersTerminate = alice().post()
                .uri("/cases/{c}/plan-items/{i}/terminate", caseId, itemId)
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "no"))
                .retrieve().toEntity(Map.class);
        assertThat(ownersTerminate.getStatusCode().value()).isEqualTo(200);
        assertThat(ownersTerminate.getBody()).containsEntry("state", "TERMINATED");
    }

    /**
     * The exact hole Task 23's review closed: before its fix, {@code listForTask} took
     * {@code callerRoles} and never read it, so <em>any</em> authenticated caller could claim
     * <em>any</em> open synced task. carol is that caller — authenticated, no participant row,
     * no candidate group.
     */
    @Test
    void aCallerWithNeitherAMutatingRoleNorACandidateGroupCannotClaimATask() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        Map<String, Object> task = openTask(caseId);
        String taskId = (String) task.get("id");
        String etag = "\"" + ((Number) task.get("version")).longValue() + "\"";

        // carol can see the task through the per-case listing — the route works for her — and is
        // offered nothing on it.
        ResponseEntity<List> carolsView = client("carol").get().uri("/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class);
        assertThat(carolsView.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) carolsView.getBody()).singleElement()
                .satisfies(t -> assertThat((List<?>) t.get("availableActions")).isEmpty());

        ResponseEntity<Map> refused = client("carol").post().uri("/tasks/{id}/claim", taskId)
                .header("If-Match", etag).retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");

        // The task is untouched: still OPEN, still unassigned, still at the same version.
        assertThat(openTask(caseId)).containsEntry("state", "OPEN")
                .containsEntry("assignee", null)
                .containsEntry("version", task.get("version"));

        // bob holds no participant role either, but IS in the task's candidate group — the other
        // half of ActionPolicy.mayActOnTask — and the identical request succeeds for him.
        ResponseEntity<Map> allowed = client("bob").post().uri("/tasks/{id}/claim", taskId)
                .header("If-Match", etag).retrieve().toEntity(Map.class);
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody()).containsEntry("assignee", "bob");
    }

    /**
     * The identity half of the rule: {@code complete} is offered only to the user the task is
     * actually assigned to. alice is the case owner — she passes the role gate outright — and
     * still cannot complete work claimed by someone else.
     */
    @Test
    void evenTheCaseOwnerCannotCompleteATaskClaimedBySomeoneElse() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        Map<String, Object> task = openTask(caseId);
        String taskId = (String) task.get("id");

        ResponseEntity<Map> claimed = client("bob").post().uri("/tasks/{id}/claim", taskId)
                .header("If-Match", "\"" + ((Number) task.get("version")).longValue() + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);
        assertThat(claimed.getBody()).containsEntry("assignee", "bob");

        ResponseEntity<Map> refused = alice().post().uri("/tasks/{id}/complete", taskId)
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", Map.of("outcome", "approve")))
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");

        // bob's own completion of the same task, with the same body, succeeds — so the refusal
        // is about the assignee, not about the payload or the task's state.
        ResponseEntity<Map> completed = client("bob").post().uri("/tasks/{id}/complete", taskId)
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", Map.of("outcome", "approve")))
                .retrieve().toEntity(Map.class);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody()).containsEntry("state", "COMPLETED");
    }

    @Test
    void anUnauthenticatedCallerNeverReachesAControllerAtAll() {
        Map<String, Object> created = deployAndCreateCase();

        ResponseEntity<String> response = anonymous().get().uri("/cases/{id}", created.get("id"))
                .retrieve().toEntity(String.class);

        // 401, not 409: this is the security filter chain, and it is deliberately a different
        // answer from every ActionPolicy refusal above — which is what makes those refusals
        // attributable to the policy.
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private RestClient anonymous() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private Map<String, Object> openTask(String caseId) {
        ResponseEntity<List> tasks = alice().get().uri("/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class);
        return (Map<String, Object>) ((List<?>) tasks.getBody()).get(0);
    }

    private Map<String, Object> activePlanItem(String caseId) {
        ResponseEntity<List> items = alice().get().uri("/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class);
        return ((List<Map<String, Object>>) items.getBody()).stream()
                .filter(i -> "ACTIVE".equals(i.get("state")))
                .findFirst().orElseThrow();
    }
}
