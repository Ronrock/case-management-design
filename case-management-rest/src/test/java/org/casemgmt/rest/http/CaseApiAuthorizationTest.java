package org.casemgmt.rest.http;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.Actor;
import org.casemgmt.sla.SlaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Autowired SlaRepository slaRepo;
    @Autowired SlaService slaService;
    @Autowired MilestoneRepository milestoneRepo;

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
                .contentType(MERGE_PATCH).body(Map.of("title", "Hijacked"))
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
                .contentType(MERGE_PATCH).body(Map.of("title", "Hijacked"))
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

    // ---------------------------------------------------------------------------------
    // Fix round 1, Critical 1: six mutating endpoints had no ActionPolicy gate at all.
    // carol is authenticated, in the right tenant, and holds nothing else; alice's identical
    // request on the same URL with the same body must succeed, so a refusal can never be a
    // routing artefact or a filter-chain 403.
    // ---------------------------------------------------------------------------------

    @Test
    void aNonParticipantCannotCommentOnACase() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        Map<String, Object> body = Map.of("text", "Injected", "visibility", "internal");

        ResponseEntity<Map> refused = client("carol").post().uri("/cases/{id}/comments", caseId)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");

        // Nothing was written.
        assertThat((List<?>) alice().get().uri("/cases/{id}/comments", caseId)
                .retrieve().toEntity(List.class).getBody()).isEmpty();

        ResponseEntity<Map> allowed = alice().post().uri("/cases/{id}/comments", caseId)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(allowed.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void aNonParticipantCannotAchieveAMilestone() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        String planItemId = (String) planItems(caseId).stream()
                .filter(i -> "MILESTONE".equals(i.get("type"))).findFirst().orElseThrow().get("id");
        String milestoneId = "ms-" + UUID.randomUUID();
        milestoneRepo.insert(milestoneId, caseId, planItemId, "Checkpoint");

        ResponseEntity<Map> refused = client("carol").post()
                .uri("/cases/{c}/milestones/{m}/achieve", caseId, milestoneId)
                .header("If-Match", "\"0\"").retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");

        assertThat(milestone(caseId, milestoneId)).containsEntry("achieved", false);

        ResponseEntity<Map> allowed = alice().post()
                .uri("/cases/{c}/milestones/{m}/achieve", caseId, milestoneId)
                .header("If-Match", "\"0\"").retrieve().toEntity(Map.class);
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody()).containsEntry("achieved", true);
    }

    @Test
    void aNonParticipantCannotStartABpmnProcessAgainstACase() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        Map<String, Object> body = Map.of("processDefinitionKey", "some-process");

        ResponseEntity<Map> refused = client("carol").post().uri("/cases/{id}/processes", caseId)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");

        assertThat((List<?>) alice().get().uri("/cases/{id}/processes", caseId)
                .retrieve().toEntity(List.class).getBody()).isEmpty();

        ResponseEntity<Map> allowed = alice().post().uri("/cases/{id}/processes", caseId)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(allowed.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void aNonParticipantCannotPauseOrResumeAnSlaClock() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        givenARunningSlaClock(caseId);
        Map<String, Object> record = slaRecords(caseId).get(0);
        String slaId = (String) record.get("id");
        String etag = "\"" + ((Number) record.get("version")).longValue() + "\"";

        ResponseEntity<Map> refused = client("carol").post()
                .uri("/cases/{c}/slas/{s}/pause", caseId, slaId).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "no"))
                .retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");
        assertThat(slaRecords(caseId).get(0)).containsEntry("status", "RUNNING");

        ResponseEntity<Map> paused = alice().post()
                .uri("/cases/{c}/slas/{s}/pause", caseId, slaId).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "waiting"))
                .retrieve().toEntity(Map.class);
        assertThat(paused.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> refusedResume = client("carol").post()
                .uri("/cases/{c}/slas/{s}/resume", caseId, slaId)
                .header("If-Match", paused.getHeaders().getETag())
                .retrieve().toEntity(Map.class);
        assertThat(refusedResume.getStatusCode().value()).isEqualTo(409);
        assertThat(slaRecords(caseId).get(0)).containsEntry("status", "PAUSED");

        assertThat(alice().post().uri("/cases/{c}/slas/{s}/resume", caseId, slaId)
                .header("If-Match", paused.getHeaders().getETag())
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(200);
    }

    /**
     * The two administration endpoints — the sharper half of Critical 1. Neither is scoped to a
     * case, so no participant rule could ever have covered them: deploying rewrites how every
     * future case of a type behaves, and subscribing opens a continuous outbound stream of case
     * events. carol is a perfectly ordinary user of the right tenant; alice is {@code admin}.
     */
    @Test
    void anOrdinaryUserCannotDeployACaseDefinitionOrSubscribeAWebhook() {
        deployDefinition();

        ResponseEntity<Map> deploy = client("carol").post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON).body(definitionJson())
                .retrieve().toEntity(Map.class);
        assertThat(deploy.getStatusCode().value()).isEqualTo(409);
        assertThat(deploy.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(deploy.getBody()).containsEntry("code", "action-not-available");

        // Still at version 1: carol's deploy did not publish a second version.
        ResponseEntity<Map> definition = alice().get()
                .uri("/case-definitions/{k}?tenantId={t}", DEFINITION_KEY, TENANT)
                .retrieve().toEntity(Map.class);
        assertThat(definition.getBody()).containsEntry("version", 1);
        assertThat((List<Map<String, Object>>) definition.getBody().get("availableActions"))
                .extracting(a -> a.get("action"))
                .contains("deploy-case-definition", "subscribe-webhook");

        ResponseEntity<Map> subscribe = client("carol").post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://carol.test/hook", "eventTypes", List.of("case.created")))
                .retrieve().toEntity(Map.class);
        assertThat(subscribe.getStatusCode().value()).isEqualTo(409);
        assertThat(subscribe.getBody()).containsEntry("code", "action-not-available");
        assertThat((List<?>) alice().get().uri("/webhooks").retrieve().toEntity(List.class).getBody())
                .isEmpty();

        // alice, who is admin, does both on the same URLs with the same bodies.
        assertThat(alice().post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON).body(definitionJson())
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(201);
        assertThat(alice().post().uri("/webhooks").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://alice.test/hook", "eventTypes", List.of("case.created")))
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(201);
    }

    /**
     * The THIRD administration endpoint, added in the final review's fix wave and shipped with
     * its gate unasserted (corrective round). {@code EventController.deadLetters} makes two
     * claims in its own Javadoc — administration-gated, and tenant-scoped so a foreign id is
     * reported absent rather than forbidden — and the test above was titled "the TWO
     * administration endpoints" and never extended to three. A new authorization surface added
     * during a wave whose whole theme was "one door guarded, the other open" should not ship
     * with its own door unchecked.
     *
     * <p>Both halves are asserted the way this class asserts everything: alice does the same
     * thing on the same URL and gets 200, so a refusal is the gate and not a dead route; and the
     * foreign-tenant id is checked to be INDISTINGUISHABLE from a nonexistent one, not merely
     * refused — "both are refused" is satisfied by a 403/404 split, which is precisely the
     * existence oracle the 404 exists to avoid.
     */
    @Test
    void anOrdinaryUserCannotReadADeadLetterQueueAndAnotherTenantsIdDoesNotExist() {
        ResponseEntity<Map> subscribed = alice().post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://alice.test/hook", "eventTypes", List.of("case.created")))
                .retrieve().toEntity(Map.class);
        assertThat(subscribed.getStatusCode().value()).isEqualTo(201);
        String webhookId = (String) subscribed.getBody().get("id");

        // Bodies are read as raw String, not Map, on purpose: a refusal that stops working
        // returns the endpoint's JSON ARRAY, and deserialising that into a Map fails with a
        // Jackson error that names neither the status nor this gate. Reading the text keeps the
        // failure legible as "expected 409, got 200" when the mechanism is stripped.
        //
        // carol is a legitimate, authenticated user of the right tenant with no admin group.
        ResponseEntity<String> carol = client("carol").get()
                .uri("/webhooks/{id}/dead-letters", webhookId).retrieve().toEntity(String.class);
        assertThat(carol.getStatusCode().value()).isEqualTo(409);
        assertThat(carol.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(carol.getBody()).contains("\"code\":\"action-not-available\"");
        assertThat(carol.getBody()).contains("view-webhook-dead-letters");

        // dave IS an administrator — of tenant t2. Administration is not a cross-tenant licence,
        // and alice's subscription must be as invisible to him as an id that was never minted.
        ResponseEntity<String> foreign = client("dave").get()
                .uri("/webhooks/{id}/dead-letters", webhookId).retrieve().toEntity(String.class);
        ResponseEntity<String> nonexistent = client("dave").get()
                .uri("/webhooks/{id}/dead-letters", "webhook-that-never-existed")
                .retrieve().toEntity(String.class);
        assertThat(foreign.getStatusCode().value())
                .as("another tenant's subscription and a made-up id must be indistinguishable")
                .isEqualTo(nonexistent.getStatusCode().value());
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        assertThat(foreign.getBody()).contains("\"code\":\"not-found\"");
        assertThat(nonexistent.getBody()).contains("\"code\":\"not-found\"");
        // Compared with each id's own echo removed. The two bodies are not byte-identical — the
        // problem `detail` names the id that was asked for — but that echoes back only what this
        // caller already supplied, so it discloses nothing. Everything else must match, or the
        // difference is the oracle.
        assertThat(foreign.getBody().replace(webhookId, "<id>"))
                .as("beyond the caller's own echoed id, the two answers must be identical")
                .isEqualTo(nonexistent.getBody().replace("webhook-that-never-existed", "<id>"));

        // Positive control: the owning administrator reads it successfully, so neither refusal
        // above is a broken route or a missing bean.
        ResponseEntity<List> owner = alice().get()
                .uri("/webhooks/{id}/dead-letters", webhookId).retrieve().toEntity(List.class);
        assertThat(owner.getStatusCode().value()).isEqualTo(200);
        assertThat(owner.getBody()).isEmpty();
    }

    @Test
    void anOwningAdministratorCanRedeliverDeadLettersButOtherCallersCannot() {
        ResponseEntity<Map> subscribed = alice().post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://alice.test/hook", "eventTypes", List.of("case.created")))
                .retrieve().toEntity(Map.class);
        assertThat(subscribed.getStatusCode().value()).isEqualTo(201);
        String webhookId = (String) subscribed.getBody().get("id");
        seedDeadWebhookDelivery(webhookId);

        ResponseEntity<String> carol = client("carol").post()
                .uri("/webhooks/{id}/dead-letters/redeliver", webhookId)
                .retrieve().toEntity(String.class);
        assertThat(carol.getStatusCode().value()).isEqualTo(409);
        assertThat(carol.getBody()).contains("\"code\":\"action-not-available\"");
        assertThat(carol.getBody()).contains("redeliver-webhook-dead-letters");
        assertThat(webhookDeliveryStatus(webhookId)).isEqualTo("DEAD:5");

        ResponseEntity<String> foreign = client("dave").post()
                .uri("/webhooks/{id}/dead-letters/redeliver", webhookId)
                .retrieve().toEntity(String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        assertThat(foreign.getBody()).contains("\"code\":\"not-found\"");
        assertThat(webhookDeliveryStatus(webhookId)).isEqualTo("DEAD:5");

        ResponseEntity<Void> owner = alice().post()
                .uri("/webhooks/{id}/dead-letters/redeliver", webhookId)
                .retrieve().toEntity(Void.class);
        assertThat(owner.getStatusCode().value()).isEqualTo(202);
        assertThat(webhookDeliveryStatus(webhookId)).isEqualTo("PENDING:0");
    }

    /**
     * Fix round 1, review finding I3, over the wire. mallory's IDENTITY groups are named
     * {@code owner} and {@code handler} — participant-role names. Under the merged role set the
     * first cut used, that gave her claim and complete on every task in every case with no
     * participant row anywhere.
     */
    @Test
    void identityGroupsNamedLikeMutatingRolesConferNothing() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        Map<String, Object> task = openTask(caseId);
        String etag = "\"" + ((Number) task.get("version")).longValue() + "\"";

        ResponseEntity<List> mallorysView = client("mallory").get().uri("/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class);
        assertThat(mallorysView.getStatusCode().value()).as("the route works for mallory").isEqualTo(200);
        assertThat((List<Map<String, Object>>) mallorysView.getBody()).singleElement()
                .satisfies(t -> assertThat((List<?>) t.get("availableActions")).isEmpty());

        ResponseEntity<Map> refused = client("mallory").post().uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", etag).retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");

        // She cannot patch the case either, for the same reason.
        ResponseEntity<Map> patch = client("mallory").patch().uri("/cases/{id}", caseId)
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH).body(Map.of("title", "Escalated"))
                .retrieve().toEntity(Map.class);
        assertThat(patch.getStatusCode().value()).isEqualTo(409);

        // bob, whose group genuinely IS the task's candidate group, claims it.
        assertThat(client("bob").post().uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", etag).retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(200);
    }

    /**
     * Fix round 2, review finding Important 1. Exposing {@code planItemId} on
     * {@code POST /cases/{id}/processes} made it caller-controlled, and nothing downstream
     * validates it: {@code LinkedProcessService.start} only loads the case, and
     * {@code CM_LINKED_PROCESS.PLAN_ITEM_ID_} has no foreign key. alice is {@code owner} of BOTH
     * cases here, so the policy passes on either — the refusal can only be the ownership check,
     * not authorization.
     */
    @Test
    void aProcessCannotBeCorrelatedToAPlanItemOfADifferentCase() {
        deployDefinition();
        String caseA = (String) createCase("case A").getBody().get("id");
        String caseB = (String) createCase("case B").getBody().get("id");

        String foreignPlanItemId = (String) planItems(caseB).get(0).get("id");
        String ownPlanItemId = (String) planItems(caseA).get(0).get("id");

        ResponseEntity<Map> refused = alice().post().uri("/cases/{id}/processes", caseA)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process",
                        "planItemId", foreignPlanItemId))
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getBody()).containsEntry("code", "wrong-case");

        // No row was written to either case.
        assertThat((List<?>) alice().get().uri("/cases/{id}/processes", caseA)
                .retrieve().toEntity(List.class).getBody()).isEmpty();
        assertThat((List<?>) alice().get().uri("/cases/{id}/processes", caseB)
                .retrieve().toEntity(List.class).getBody()).isEmpty();

        // An id that names no plan item at all gets the identical answer — no existence oracle.
        ResponseEntity<Map> unknown = alice().post().uri("/cases/{id}/processes", caseA)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process", "planItemId", "no-such-item"))
                .retrieve().toEntity(Map.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(409);
        assertThat(unknown.getBody()).containsEntry("code", "wrong-case");

        // The case's OWN plan item is accepted, so the refusal is the correlation and not the
        // field being rejected outright...
        ResponseEntity<Map> accepted = alice().post().uri("/cases/{id}/processes", caseA)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process", "planItemId", ownPlanItemId))
                .retrieve().toEntity(Map.class);
        assertThat(accepted.getStatusCode().value()).isEqualTo(201);
        assertThat(accepted.getBody()).containsEntry("planItemId", ownPlanItemId);

        // ...and omitting it entirely stays legal, as the spec declares.
        assertThat(alice().post().uri("/cases/{id}/processes", caseA).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process"))
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(201);
    }

    private void givenARunningSlaClock(String caseId) {
        List<Map<String, Object>> allDay = List.of(Map.of("from", "00:00", "to", "23:59"));
        slaRepo.insertCalendar("cal-auth", Map.of("timezone", "Europe/Amsterdam",
                "workingHours", Map.of("MONDAY", allDay, "TUESDAY", allDay, "WEDNESDAY", allDay,
                        "THURSDAY", allDay, "FRIDAY", allDay, "SATURDAY", allDay, "SUNDAY", allDay),
                "holidays", List.of()));
        slaRepo.insertPolicy("pol-auth", "Standard", null, "cal-auth");
        slaRepo.insertTarget("tgt-auth", "pol-auth", "firstResponse", "First response",
                "PT4H", "PT3H", List.of(), List.of("EMIT_EVENT"));
        slaService.startClocks(caseId, "pol-auth", new Actor("alice", List.of()));
    }

    private List<Map<String, Object>> slaRecords(String caseId) {
        return (List<Map<String, Object>>) alice().get().uri("/cases/{id}/slas", caseId)
                .retrieve().toEntity(List.class).getBody();
    }

    private List<Map<String, Object>> planItems(String caseId) {
        return (List<Map<String, Object>>) alice().get().uri("/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class).getBody();
    }

    private Map<String, Object> milestone(String caseId, String milestoneId) {
        return ((List<Map<String, Object>>) alice().get().uri("/cases/{id}/milestones", caseId)
                .retrieve().toEntity(List.class).getBody()).stream()
                .filter(m -> milestoneId.equals(m.get("id"))).findFirst().orElseThrow();
    }

    private void seedDeadWebhookDelivery(String webhookId) {
        long seq = new EventRepository(jdbc()).append(new CaseEvent(CaseIds.newId(),
                "/engines/eng-test/cases", "org.example.cm.case.created", "eng-test:redeliver",
                TENANT, OffsetDateTime.now(), Map.of("state", "ACTIVE")));
        jdbc().sql("""
                INSERT INTO CM_WEBHOOK_DELIVERY (ID_, WEBHOOK_ID_, EVENT_SEQ_, STATUS_,
                    ATTEMPTS_, LAST_STATUS_CODE_, LAST_ERROR_, FAILED_AT_)
                VALUES (:id, :webhookId, :seq, 'DEAD', 5, 500, 'downstream failed', SYSTIMESTAMP)""")
            .param("id", CaseIds.newId())
            .param("webhookId", webhookId)
            .param("seq", seq)
            .update();
    }

    private String webhookDeliveryStatus(String webhookId) {
        return jdbc().sql("""
                SELECT STATUS_ || ':' || ATTEMPTS_
                FROM CM_WEBHOOK_DELIVERY
                WHERE WEBHOOK_ID_ = :webhookId""")
            .param("webhookId", webhookId)
            .query(String.class)
            .single();
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
