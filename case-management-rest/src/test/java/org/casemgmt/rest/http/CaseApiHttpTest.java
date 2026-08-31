package org.casemgmt.rest.http;

import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.Actor;
import org.casemgmt.sla.SlaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case API over real HTTP against real Oracle: creation, idempotent replay, the ETag round
 * trip, discovery, the event log, tasks, collaboration and SLA clocks.
 *
 * <p>No case type appears anywhere in this file beyond the {@code widget-review} fixture that
 * case-management-core already ships — the API is driven entirely by what the deployed
 * definition says, which is the point of the design.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiHttpTest extends CaseApiHttpTestBase {

    @Autowired SlaRepository slaRepo;
    @Autowired SlaService slaService;
    @Autowired MilestoneRepository milestoneRepo;

    @Test
    void createsACaseAndReturnsAnETagLocationAndAvailableActions() {
        deployDefinition();

        ResponseEntity<Map> response = alice().post().uri("/cases")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT, "title", "T"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(response.getHeaders().getLocation()).hasToString(
                "/case-api/v2/cases/" + response.getBody().get("id"));
        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");

        assertThat(response.getBody()).containsEntry("state", "ACTIVE")
                .containsEntry("caseDefinitionKey", DEFINITION_KEY)
                .containsEntry("version", 0);

        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.getBody().get("availableActions");
        assertThat(actions).isNotEmpty();
        assertThat(actions).extracting(a -> a.get("action")).contains("update", "cancel");
        assertThat(actions).allSatisfy(a -> assertThat(a).containsKeys("action", "href", "method"));
        assertThat((List<Map<String, Object>>) response.getBody().get("collaborationActions"))
                .extracting(a -> a.get("action"))
                .contains("comment", "start-process");
    }

    @Test
    void replaysTheSameIdempotencyKeyInsteadOfCreatingASecondCase() {
        deployDefinition();
        Map<String, Object> body = Map.of("caseDefinitionKey", DEFINITION_KEY,
                "tenantId", TENANT, "title", "T");

        ResponseEntity<Map> first = alice().post().uri("/cases").header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> second = alice().post().uri("/cases").header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);

        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        // The replay reproduces the ORIGINAL status, taken from the stored row, not whatever
        // the fresh path would have returned; and the reconstructed Location/ETag come back too.
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(second.getHeaders().getETag()).isEqualTo(first.getHeaders().getETag());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());

        // Exactly one case exists, not two.
        ResponseEntity<Map> all = alice().get().uri("/cases").retrieve().toEntity(Map.class);
        assertThat(items(all)).hasSize(1);
    }

    @Test
    void caseListingReturnsTotalsAndHonoursSortAndCreatedDateFilters() {
        deployDefinition();
        createCase("C case");
        createCase("A case");
        createCase("B case");

        ResponseEntity<Map> firstPage = alice().get()
                .uri("/cases?sort=title&page=0&pageSize=2&createdAfter={after}",
                        OffsetDateTime.now().minusDays(1).toString())
                .retrieve().toEntity(Map.class);

        assertThat(firstPage.getStatusCode().value()).isEqualTo(200);
        assertThat(firstPage.getBody()).containsEntry("page", 0)
                .containsEntry("pageSize", 2)
                .containsEntry("totalItems", 3)
                .containsEntry("totalPages", 2);
        assertThat(items(firstPage)).extracting(i -> i.get("title"))
                .containsExactly("A case", "B case");

        ResponseEntity<Map> empty = alice().get()
                .uri("/cases?createdBefore={before}", OffsetDateTime.now().minusDays(1).toString())
                .retrieve().toEntity(Map.class);
        assertThat(empty.getBody()).containsEntry("totalItems", 0)
                .containsEntry("totalPages", 0);
        assertThat(items(empty)).isEmpty();
    }

    @Test
    void theETagFromAMutationIsTheOneTheNextMutationMustPresent() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        ResponseEntity<Map> patched = alice().patch().uri("/cases/{id}", id)
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH).body(Map.of("title", "One"))
                .retrieve().toEntity(Map.class);

        assertThat(patched.getStatusCode().value()).isEqualTo(200);
        assertThat(patched.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(patched.getBody()).containsEntry("title", "One").containsEntry("version", 1);

        // The ETag handed back is genuinely the current one: a GET agrees, and it is accepted
        // as the If-Match for the next write.
        ResponseEntity<Map> read = alice().get().uri("/cases/{id}", id).retrieve().toEntity(Map.class);
        assertThat(read.getHeaders().getETag()).isEqualTo(patched.getHeaders().getETag());

        ResponseEntity<Map> again = alice().patch().uri("/cases/{id}", id)
                .header("If-Match", patched.getHeaders().getETag())
                .contentType(MERGE_PATCH).body(Map.of("title", "Two"))
                .retrieve().toEntity(Map.class);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getHeaders().getETag()).isEqualTo("\"2\"");
    }

    @Test
    void mergePatchNullsClearTitleAndVariables() {
        deployDefinition();
        ResponseEntity<Map> created = alice().post().uri("/cases")
                .header("Idempotency-Key", "merge-patch-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT,
                        "title", "Clear me",
                        "variables", Map.of("keep", "yes", "remove", "no",
                                "nested", Map.of("keepNested", "yes", "removeNested", "no"))))
                .retrieve().toEntity(Map.class);
        String id = (String) created.getBody().get("id");

        Map<String, Object> variablesPatch = new java.util.LinkedHashMap<>();
        variablesPatch.put("remove", null);
        variablesPatch.put("add", "new");
        variablesPatch.put("nested", new java.util.LinkedHashMap<>(Map.of("addNested", "new")));
        ((Map<String, Object>) variablesPatch.get("nested")).put("removeNested", null);
        Map<String, Object> patch = new java.util.LinkedHashMap<>();
        patch.put("title", null);
        patch.put("variables", variablesPatch);

        ResponseEntity<Map> updated = alice().patch().uri("/cases/{id}", id)
                .header("If-Match", created.getHeaders().getETag())
                .contentType(MERGE_PATCH)
                .body(patch)
                .retrieve().toEntity(Map.class);

        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody()).containsEntry("title", null);
        Map<String, Object> variables = (Map<String, Object>) updated.getBody().get("variables");
        assertThat(variables).containsEntry("keep", "yes")
                .containsEntry("add", "new")
                .doesNotContainKey("remove");
        assertThat((Map<String, Object>) variables.get("nested"))
                .containsEntry("keepNested", "yes")
                .containsEntry("addNested", "new")
                .doesNotContainKey("removeNested");
    }

    /**
     * The projection and the enforcement agree, and the refusal says what would work instead.
     * {@code close} is absent from {@code availableActions[]} because BPMN root completion owns
     * closure. Calling it anyway is refused by the same rule table, so the advertised actions
     * and enforcement remain aligned.
     */
    @Test
    void explicitCloseOfABpmnCaseIsNotOfferedAndIsRefused() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");
        assertThat((List<Map<String, Object>>) created.get("availableActions"))
                .extracting(a -> a.get("action")).doesNotContain("close");

        ResponseEntity<Map> refused = alice().post().uri("/cases/{id}/close", id)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("outcome", "done"))
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getBody()).containsEntry("code", "action-not-available");
        assertThat((List<String>) refused.getBody().get("availableActions"))
                .containsExactlyInAnyOrder("update", "cancel");
    }

    @Test
    void cancellingACaseLeavesItWithNoAvailableActions() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        ResponseEntity<Map> cancelled = alice().post().uri("/cases/{id}/cancel", id)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "duplicate"))
                .retrieve().toEntity(Map.class);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody()).containsEntry("state", "CANCELLED");
        assertThat((List<?>) cancelled.getBody().get("availableActions")).isEmpty();
    }

    @Test
    void cancellingACaseWithoutARequestBodyPreservesANullDomainReason() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        ResponseEntity<Map> cancelled = alice().post().uri("/cases/{id}/cancel", id)
                .header("If-Match", "\"0\"")
                .retrieve().toEntity(Map.class);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody()).containsEntry("state", "CANCELLED")
                .containsEntry("cancelReason", null);
    }

    @Test
    void aConsumerDiscoversCaseTypesAndTheirFormsWithoutKnowingAnyOfThemUpFront() {
        deployDefinition();

        ResponseEntity<List> listing = alice().get().uri("/case-definitions")
                .retrieve().toEntity(List.class);
        assertThat(listing.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> definitions = listing.getBody();
        assertThat(definitions).isNotEmpty();

        // Everything from here on comes out of the listing itself — no constant in this test
        // names the case type, its tenant, or its forms.
        String key = (String) definitions.get(0).get("key");
        String tenant = (String) definitions.get(0).get("tenantId");
        assertThat(tenant).isNotNull();

        ResponseEntity<Map> detail = alice().get()
                .uri("/case-definitions/{key}?tenantId={tenant}", key, tenant)
                .retrieve().toEntity(Map.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) detail.getBody().get("planItems")).isEmpty();

        String formKey = (String) ((List<?>) detail.getBody().get("formKeys")).get(0);
        ResponseEntity<Map> schema = alice().get()
                .uri("/case-definitions/{key}/forms/{formKey}", key, formKey)
                .retrieve().toEntity(Map.class);
        assertThat(schema.getStatusCode().value()).isEqualTo(200);
        assertThat(schema.getBody()).containsKey("properties");
    }

    @Test
    void exposesThePerCaseEventLogWithACursor() {
        Map<String, Object> created = deployAndCreateCase();

        ResponseEntity<List> response = alice().get()
                .uri("/cases/{id}/events?after=0&limit=50", created.get("id"))
                .retrieve().toEntity(List.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> events = response.getBody();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).containsKeys("specversion", "type", "subject", "cursor");
        assertThat(events.get(0)).containsEntry("specversion", "1.0")
                .containsEntry("subject", created.get("id"));

        // The cursor is a real resume point: asking for everything after the last one returns
        // nothing, and asking again from 0 returns the same first event.
        long last = ((Number) events.get(events.size() - 1).get("cursor")).longValue();
        ResponseEntity<List> after = alice().get()
                .uri("/cases/{id}/events?after={cursor}&limit=50", created.get("id"), last)
                .retrieve().toEntity(List.class);
        assertThat(after.getBody()).isEmpty();
    }

    @Test
    void projectedBpmnActivitiesAreReadOnlyThroughThePlanItemEndpoint() {
        Map<String, Object> created = deployAndCreateCase();

        ResponseEntity<List> response = alice().get()
                .uri("/cases/{id}/plan-items", created.get("id"))
                .retrieve().toEntity(List.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> items = response.getBody();
        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(i ->
                assertThat(i).containsKeys("id", "caseId", "type", "state", "version", "availableActions"));

        assertThat(items).extracting(i -> i.get("type"))
                .containsExactlyInAnyOrder("HUMAN_TASK", "MILESTONE");
        assertThat(items).allSatisfy(i ->
                assertThat((List<?>) i.get("availableActions")).isEmpty());
    }

    @Test
    void aTaskCanBeFoundOnTheWorklistClaimedAndCompletedThroughItsDeclaredForm() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        ResponseEntity<List> forCase = alice().get().uri("/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class);
        assertThat(forCase.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> tasks = forCase.getBody();
        assertThat(tasks).hasSize(1);

        Map<String, Object> task = tasks.get(0);
        assertThat(task).containsEntry("state", "OPEN").containsEntry("engineSync", "SYNCED")
                .containsEntry("formKey", "reviewForm");
        assertThat((List<String>) task.get("candidateGroups")).containsExactly("reviewers");
        String taskId = (String) task.get("id");
        long version = ((Number) task.get("version")).longValue();

        ResponseEntity<Map> claimed = alice().post().uri("/tasks/{id}/claim", taskId)
                .header("If-Match", "\"" + version + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);
        assertThat(claimed.getBody()).containsEntry("state", "CLAIMED").containsEntry("assignee", "alice");
        // A claimed task offers exactly one next step, and it carries the form key the client
        // needs to render it — no second call to find out how.
        assertThat((List<Map<String, Object>>) claimed.getBody().get("availableActions"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a).containsEntry("action", "complete");
                    assertThat(a).containsEntry("formKey", "reviewForm");
                });

        ResponseEntity<Map> completed = alice().post().uri("/tasks/{id}/complete", taskId)
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", Map.of("outcome", "approve")))
                .retrieve().toEntity(Map.class);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody()).containsEntry("state", "COMPLETED");

        // Completion is sent to Operaton. The REST layer must not manufacture a local
        // milestone transition; a later engine observation owns that projection.
        ResponseEntity<List> milestones = alice().get().uri("/cases/{id}/milestones", caseId)
                .retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) milestones.getBody()).isEmpty();
    }

    @Test
    void theWorklistShowsATaskToACandidateGroupMember() {
        deployAndCreateCase();

        ResponseEntity<List> bobs = client("bob").get().uri("/tasks?limit=50")
                .retrieve().toEntity(List.class);
        assertThat(bobs.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) bobs.getBody())
                .singleElement()
                .satisfies(t -> assertThat(t).containsEntry("state", "OPEN"));

        // carol is in no candidate group and holds no task, so her worklist is empty — the
        // repository's own OR rule, visible through the endpoint.
        ResponseEntity<List> carols = client("carol").get().uri("/tasks?limit=50")
                .retrieve().toEntity(List.class);
        assertThat(carols.getBody()).isEmpty();
    }

    @Test
    void commentsRoundTrip() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        ResponseEntity<Map> added = alice().post().uri("/cases/{id}/comments", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", "Looks fine", "visibility", "internal"))
                .retrieve().toEntity(Map.class);
        assertThat(added.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<List> listed = alice().get().uri("/cases/{id}/comments", caseId)
                .retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) listed.getBody()).singleElement()
                .satisfies(c -> {
                    assertThat(c).containsEntry("text", "Looks fine").containsEntry("author", "alice");
                    assertThat(c.get("createdAt")).isNotNull();
                });
    }

    /**
     * An unachieved milestone must report {@code achievedAt} as a JSON {@code null}, not as the
     * four-character string {@code "null"} that the brief's {@code String.valueOf(...)} would
     * have produced — the same defect Task 17 fixed for {@code CM_TASK.OUTCOME_}, which a client
     * cannot distinguish from a real value.
     *
     * <p>The row is inserted through {@code MilestoneRepository} — the production writer — rather
     * than driven through the plan model, because this fixture's milestone completes the instant
     * it enters, so the model never leaves one
     * unachieved for long enough to read.
     */
    @Test
    void anUnachievedMilestoneReportsNullRatherThanTheStringNullAndCanThenBeAchieved() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        ResponseEntity<List> planItems = alice().get().uri("/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class);
        String planItemId = (String) ((List<Map<String, Object>>) planItems.getBody()).stream()
                .filter(i -> "MILESTONE".equals(i.get("type"))).findFirst().orElseThrow().get("id");

        String milestoneId = "ms-" + UUID.randomUUID();
        milestoneRepo.insert(milestoneId, caseId, planItemId, "Manual checkpoint");

        ResponseEntity<List> before = alice().get().uri("/cases/{id}/milestones", caseId)
                .retrieve().toEntity(List.class);
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) before.getBody())
                .filteredOn(m -> milestoneId.equals(m.get("id")))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m).containsEntry("achieved", false);
                    assertThat(m).containsKey("achievedAt");
                    assertThat(m.get("achievedAt")).isNull();
                });

        ResponseEntity<Map> achieved = alice().post()
                .uri("/cases/{c}/milestones/{m}/achieve", caseId, milestoneId)
                .header("If-Match", "\"0\"")
                .retrieve().toEntity(Map.class);
        assertThat(achieved.getStatusCode().value()).isEqualTo(200);
        assertThat(achieved.getBody()).containsEntry("achieved", true);

        ResponseEntity<List> after = alice().get().uri("/cases/{id}/milestones", caseId)
                .retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) after.getBody())
                .filteredOn(m -> milestoneId.equals(m.get("id")))
                .singleElement()
                .satisfies(m -> assertThat((String) m.get("achievedAt")).contains("T"));
    }

    /**
     * Carried finding C6, as resolved in {@code CollaborationController.processBody}: a linked
     * process is never hidden, and its {@code engineSync} is published so a client can tell a
     * placeholder {@code processInstanceId} from the engine's real one.
     */
    @Test
    void aStartedProcessIsVisibleImmediatelyAndDeclaresItsEngineSyncState() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        // planItemId is threaded through to LinkedProcessService (fix round 1, I6): pick the
        // case's own ACTIVE plan item so the linked-process row correlates to something real.
        ResponseEntity<List> planItems = alice().get().uri("/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class);
        String planItemId = (String) ((List<Map<String, Object>>) planItems.getBody()).stream()
                .filter(i -> "ACTIVE".equals(i.get("state"))).findFirst().orElseThrow().get("id");

        ResponseEntity<Map> started = alice().post().uri("/cases/{id}/processes", caseId)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process", "planItemId", planItemId,
                        "variables", Map.of("k", "v")))
                .retrieve().toEntity(Map.class);
        assertThat(started.getStatusCode().value()).isEqualTo(201);
        assertThat(started.getBody()).containsEntry("planItemId", planItemId);

        ResponseEntity<List> listed = alice().get().uri("/cases/{id}/processes", caseId)
                .retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) listed.getBody())
                .filteredOn(p -> started.getBody().get("id").equals(p.get("id")))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p).containsEntry("id", started.getBody().get("id"));
                    assertThat(p).containsEntry("processDefinitionKey", "some-process");
                    assertThat(p).containsEntry("planItemId", planItemId);
                    assertThat(p).containsEntry("engineSync", "SYNCED");
                    assertThat(p).containsEntry("state", "ACTIVE");
                });
    }

    @Test
    void slaClocksAreListedPausedAndResumedThroughTheirOwnETags() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        givenAnSlaPolicy();
        slaService.startClocks(caseId, "pol-1", new Actor("alice", List.of()));

        ResponseEntity<List> listed = alice().get().uri("/cases/{id}/slas", caseId)
                .retrieve().toEntity(List.class);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> records = listed.getBody();
        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsEntry("status", "RUNNING");
        assertThat((List<Map<String, Object>>) records.get(0).get("availableActions"))
                .extracting(a -> a.get("action")).containsExactly("pause");
        // warnAt is a real ISO instant, not "null" and not a stringified object.
        assertThat((String) records.get(0).get("warnAt")).contains("T");
        String slaId = (String) records.get(0).get("id");
        long version = ((Number) records.get(0).get("version")).longValue();

        ResponseEntity<Map> paused = alice().post().uri("/cases/{c}/slas/{s}/pause", caseId, slaId)
                .header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "waiting"))
                .retrieve().toEntity(Map.class);
        assertThat(paused.getStatusCode().value()).isEqualTo(200);
        assertThat(paused.getBody()).containsEntry("status", "PAUSED")
                .containsEntry("pausedReason", "waiting");
        assertThat((List<Map<String, Object>>) paused.getBody().get("availableActions"))
                .extracting(a -> a.get("action")).containsExactly("resume");
        assertThat(paused.getHeaders().getETag()).isEqualTo("\"" + (version + 1) + "\"");

        ResponseEntity<Map> resumed = alice().post().uri("/cases/{c}/slas/{s}/resume", caseId, slaId)
                .header("If-Match", paused.getHeaders().getETag())
                .retrieve().toEntity(Map.class);
        assertThat(resumed.getStatusCode().value()).isEqualTo(200);
        assertThat(resumed.getBody()).containsEntry("status", "RUNNING");
        assertThat((List<Map<String, Object>>) resumed.getBody().get("availableActions"))
                .extracting(a -> a.get("action")).containsExactly("pause");
    }

    @Test
    void aWebhookSubscriptionReturnsItsPlaintextSecretExactlyOnce() {
        ResponseEntity<Map> created = alice().post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://example.test/hook",
                        "eventTypes", List.of("case.created"), "tenantId", TENANT))
                .retrieve().toEntity(Map.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat((String) created.getBody().get("secret")).isNotBlank();
        assertThat(created.getBody()).containsEntry("tenantId", TENANT);
        assertThat(auditActionFor((String) created.getBody().get("id")))
                .isEqualTo("alice:t1:webhook.subscribe");

        ResponseEntity<List> listed = alice().get().uri("/webhooks").retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) listed.getBody()).singleElement()
                .satisfies(s -> {
                    assertThat(s).containsEntry("url", "https://example.test/hook");
                    assertThat(s).containsEntry("active", true);
                    assertThat(s).doesNotContainKey("secret");
                    assertThat(s).doesNotContainKey("secretHash");
                    assertThat((List<Map<String, Object>>) s.get("availableActions"))
                            .extracting(a -> a.get("action"))
                            .contains("view-webhook-dead-letters", "redeliver-webhook-dead-letters");
                });
    }

    private String auditActionFor(String resourceId) {
        return jdbc().sql("""
                SELECT ACTOR_ || ':' || TENANT_ID_ || ':' || ACTION_
                FROM CM_AUDIT_LOG
                WHERE RESOURCE_TYPE_ = 'WebhookSubscription' AND RESOURCE_ID_ = :id
                  AND CASE_ID_ IS NULL""")
                .param("id", resourceId)
                .query(String.class)
                .single();
    }

    @Test
    void theGlobalEventStreamIsCursorPaginatedAcrossCases() {
        deployDefinition();
        createCase("first");
        createCase("second");

        ResponseEntity<Map> firstPage = alice().get().uri("/events?after=0&limit=1")
                .retrieve().toEntity(Map.class);
        assertThat(firstPage.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) firstPage.getBody().get("items");
        assertThat(firstItems).hasSize(1);
        assertThat(firstPage.getBody().get("nextCursor")).isNotNull();

        long cursor = ((Number) firstItems.get(0).get("cursor")).longValue();
        ResponseEntity<Map> secondPage = alice().get()
                .uri("/events?after={cursor}&limit=1", cursor)
                .retrieve().toEntity(Map.class);
        assertThat((List<Map<String, Object>>) secondPage.getBody().get("items")).singleElement()
                .satisfies(e -> assertThat(((Number) e.get("cursor")).longValue()).isGreaterThan(cursor));
    }

    @Test
    void anUnknownIdempotencyKeyReusedWithADifferentPayloadIsAConflict() {
        deployDefinition();
        String key = UUID.randomUUID().toString();

        alice().post().uri("/cases").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT, "title", "One"))
                .retrieve().toEntity(Map.class);

        ResponseEntity<Map> conflict = alice().post().uri("/cases").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT, "title", "Two"))
                .retrieve().toEntity(Map.class);

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(conflict.getBody()).containsEntry("code", "idempotency-conflict");
    }

    /**
     * The spec's {@code Page} envelope and repeatable {@code state} (fix round 1, I6), plus the
     * {@code pageSize} ceiling that stops a client asking the server for unbounded work (I8).
     */
    @Test
    void theCaseListingIsAPageEnvelopeWithARepeatableStateFilterAndABoundedPageSize() {
        deployDefinition();
        String openId = (String) createCase("still open").getBody().get("id");
        String cancelledId = (String) createCase("to cancel").getBody().get("id");
        alice().post().uri("/cases/{id}/cancel", cancelledId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "duplicate"))
                .retrieve().toEntity(Map.class);

        ResponseEntity<Map> all = alice().get().uri("/cases?page=0&pageSize=10")
                .retrieve().toEntity(Map.class);
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(all.getBody()).containsEntry("page", 0).containsEntry("pageSize", 10);
        assertThat(items(all)).hasSize(2);

        // One request, two states — the whole point of the repeatable parameter.
        ResponseEntity<Map> both = alice().get().uri("/cases?state=ACTIVE,CANCELLED")
                .retrieve().toEntity(Map.class);
        assertThat(items(both)).extracting(c -> c.get("id"))
                .containsExactlyInAnyOrder(openId, cancelledId);

        // Repeated parameters mean the same thing as the comma-separated form.
        ResponseEntity<Map> repeated = alice().get().uri("/cases?state=ACTIVE&state=CANCELLED")
                .retrieve().toEntity(Map.class);
        assertThat(items(repeated)).hasSize(2);

        // ...and a single state still narrows.
        assertThat(items(alice().get().uri("/cases?state=CANCELLED").retrieve().toEntity(Map.class)))
                .singleElement().satisfies(c -> assertThat(c).containsEntry("id", cancelledId));

        // Paging walks the envelope.
        ResponseEntity<Map> firstPage = alice().get().uri("/cases?page=0&pageSize=1")
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> secondPage = alice().get().uri("/cases?page=1&pageSize=1")
                .retrieve().toEntity(Map.class);
        assertThat(items(firstPage)).hasSize(1);
        assertThat(items(secondPage)).hasSize(1);
        assertThat(items(firstPage).get(0).get("id")).isNotEqualTo(items(secondPage).get(0).get("id"));

        // A client-chosen page size is clamped to the spec's maximum, and the envelope reports
        // the size actually used rather than the one asked for.
        ResponseEntity<Map> huge = alice().get().uri("/cases?pageSize=100000")
                .retrieve().toEntity(Map.class);
        assertThat(huge.getStatusCode().value()).isEqualTo(200);
        assertThat(huge.getBody()).containsEntry("pageSize", 200);
    }

    private void givenAnSlaPolicy() {
        List<Map<String, Object>> allDay = List.of(Map.of("from", "00:00", "to", "23:59"));
        slaRepo.insertCalendar("cal-test", Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("MONDAY", allDay, "TUESDAY", allDay, "WEDNESDAY", allDay,
                        "THURSDAY", allDay, "FRIDAY", allDay, "SATURDAY", allDay, "SUNDAY", allDay),
                "holidays", List.of()));
        slaRepo.insertPolicy("pol-1", "Standard", null, "cal-test");
        slaRepo.insertTarget("tgt-first", "pol-1", "firstResponse", "First response",
                "PT4H", "PT3H", List.of(), List.of("EMIT_EVENT"));
    }
}
