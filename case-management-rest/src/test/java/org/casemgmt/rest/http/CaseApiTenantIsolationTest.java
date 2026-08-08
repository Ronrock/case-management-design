package org.casemgmt.rest.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation (fix round 1, Critical 2).
 *
 * <p>The review found that nothing in this API was scoped to a tenant: {@code POST /webhooks}
 * took {@code tenantId} straight from the request body — so any authenticated user could point
 * an endpoint they control at another tenant's event stream and keep receiving it —
 * {@code GET /events} streamed every CloudEvent in the deployment, {@code GET /webhooks} listed
 * every tenant's subscriptions, and every per-case read served any case to anyone.
 *
 * <p><b>The prober is {@code dave}, and he is deliberately over-privileged.</b> He is
 * {@code admin} (so an administration gate cannot be what refuses him) and he is in
 * {@code reviewers}, the fixture task's candidate group (so a task rule cannot be what refuses
 * him). The only thing he does not have is tenant {@code t1}. Every refusal below is therefore
 * attributable to the tenant boundary and to nothing else — and each test pairs it with the same
 * request from a {@code t1} user succeeding, so it cannot be passing on a broken route.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiTenantIsolationTest extends CaseApiHttpTestBase {

    private static final String OTHER_TENANT_USER = "dave";

    @Test
    void anotherTenantsCaseDoesNotExistAsFarAsThisCallerIsConcerned() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        // The owner reads it fine — the route works and the case exists.
        assertThat(alice().get().uri("/cases/{id}", id).retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> read = client(OTHER_TENANT_USER).get().uri("/cases/{id}", id)
                .retrieve().toEntity(Map.class);
        // 404, not 403: a 403 on a caller-supplied id is an existence oracle across the tenant
        // boundary. See CallerResolver.requireVisible.
        assertThat(read.getStatusCode().value()).isEqualTo(404);
        assertThat(read.getBody()).containsEntry("code", "not-found");

        ResponseEntity<Map> patch = client(OTHER_TENANT_USER).patch().uri("/cases/{id}", id)
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH).body(Map.of("title", "Leaked"))
                .retrieve().toEntity(Map.class);
        assertThat(patch.getStatusCode().value()).isEqualTo(404);

        // Untouched.
        ResponseEntity<Map> after = alice().get().uri("/cases/{id}", id).retrieve().toEntity(Map.class);
        assertThat(after.getBody()).containsEntry("title", created.get("title"))
                .containsEntry("version", 0);
    }

    @Test
    void everyPerCaseSubResourceIsScopedToTheCasesTenant() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        List<String> subResources = List.of("/cases/{id}/comments", "/cases/{id}/milestones",
                "/cases/{id}/processes", "/cases/{id}/slas", "/cases/{id}/tasks",
                "/cases/{id}/plan-items", "/cases/{id}/events");

        for (String uri : subResources) {
            assertThat(alice().get().uri(uri, id).retrieve().toEntity(String.class)
                    .getStatusCode().value())
                    .as("owner reads %s", uri).isEqualTo(200);

            ResponseEntity<Map> denied = client(OTHER_TENANT_USER).get().uri(uri, id)
                    .retrieve().toEntity(Map.class);
            assertThat(denied.getStatusCode().value()).as("cross-tenant read of %s", uri).isEqualTo(404);
            assertThat(denied.getBody()).containsEntry("code", "not-found");
        }
    }

    @Test
    void theCaseListingOnlyEverShowsTheCallersOwnTenantAndRefusesToBeAskedForAnother() {
        deployAndCreateCase();

        ResponseEntity<Map> mine = alice().get().uri("/cases").retrieve().toEntity(Map.class);
        assertThat(items(mine)).hasSize(1);

        ResponseEntity<Map> theirs = client(OTHER_TENANT_USER).get().uri("/cases")
                .retrieve().toEntity(Map.class);
        assertThat(theirs.getStatusCode().value()).as("the endpoint works for dave").isEqualTo(200);
        assertThat(items(theirs)).as("but shows him nothing of tenant t1").isEmpty();

        // Asking for someone else's tenant explicitly is refused rather than quietly rewritten
        // to his own — a client must never believe it saw a tenant it did not.
        ResponseEntity<Map> crossTenant = client(OTHER_TENANT_USER).get()
                .uri("/cases?tenantId={t}", TENANT).retrieve().toEntity(Map.class);
        assertThat(crossTenant.getStatusCode().value()).isEqualTo(403);
        assertThat(crossTenant.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(crossTenant.getBody()).containsEntry("code", "forbidden");

        // ...and naming his own tenant is fine, so the parameter still means something.
        assertThat(client(OTHER_TENANT_USER).get().uri("/cases?tenantId=t2")
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theGlobalEventStreamIsScopedToTheCallersTenant() {
        deployAndCreateCase();

        ResponseEntity<List> mine = alice().get().uri("/events?after=0&limit=100")
                .retrieve().toEntity(List.class);
        assertThat((List<?>) mine.getBody()).isNotEmpty();

        ResponseEntity<List> theirs = client(OTHER_TENANT_USER).get().uri("/events?after=0&limit=100")
                .retrieve().toEntity(List.class);
        assertThat(theirs.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) theirs.getBody())
                .as("tenant t1's case events must not reach a t2 caller").isEmpty();
    }

    /**
     * The sharpest part of Critical 2. {@code POST /webhooks} used the body's {@code tenantId}
     * verbatim, so this exact request registered an attacker-controlled endpoint against another
     * tenant and received that tenant's events continuously.
     */
    @Test
    void aWebhookCannotBeSubscribedAgainstAnotherTenantAndListingsDoNotCross() {
        deployDefinition();

        ResponseEntity<Map> exfiltration = client(OTHER_TENANT_USER).post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://attacker.test/hook",
                        "eventTypes", List.of("case.created"), "tenantId", TENANT))
                .retrieve().toEntity(Map.class);

        assertThat(exfiltration.getStatusCode().value()).isEqualTo(403);
        assertThat(exfiltration.getBody()).containsEntry("code", "forbidden");

        // dave is admin, so the administration gate is not what refused him: the same request
        // against his OWN tenant succeeds, and binds to t2 no matter what he asked for.
        ResponseEntity<Map> ownTenant = client(OTHER_TENANT_USER).post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://dave.test/hook", "eventTypes", List.of("case.created")))
                .retrieve().toEntity(Map.class);
        assertThat(ownTenant.getStatusCode().value()).isEqualTo(201);
        assertThat(ownTenant.getBody()).containsEntry("tenantId", "t2");

        ResponseEntity<Map> alices = alice().post().uri("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "https://alice.test/hook", "eventTypes", List.of("case.created")))
                .retrieve().toEntity(Map.class);
        assertThat(alices.getStatusCode().value()).isEqualTo(201);

        // Neither administrator sees the other's endpoints.
        ResponseEntity<List> davesList = client(OTHER_TENANT_USER).get().uri("/webhooks")
                .retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) davesList.getBody()).singleElement()
                .satisfies(w -> assertThat(w).containsEntry("url", "https://dave.test/hook"));

        ResponseEntity<List> alicesList = alice().get().uri("/webhooks").retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) alicesList.getBody()).singleElement()
                .satisfies(w -> assertThat(w).containsEntry("url", "https://alice.test/hook"));
    }

    /**
     * Identity groups are global; cases are not. dave is in {@code reviewers}, which is exactly
     * the fixture task's candidate group, so without a tenant predicate on the worklist query he
     * would see — and be able to claim — another tenant's work purely by group name.
     */
    @Test
    void theWorklistDoesNotOfferAnotherTenantsTasksToASimilarlyNamedGroup() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        ResponseEntity<List> bobs = client("bob").get().uri("/tasks?limit=50")
                .retrieve().toEntity(List.class);
        assertThat((List<?>) bobs.getBody())
                .as("bob is in reviewers and in tenant t1: he sees it").hasSize(1);

        ResponseEntity<List> daves = client(OTHER_TENANT_USER).get().uri("/tasks?limit=50")
                .retrieve().toEntity(List.class);
        assertThat((List<?>) daves.getBody())
                .as("dave is in reviewers too, but in tenant t2").isEmpty();

        // And he cannot reach it by id either.
        Map<String, Object> task = (Map<String, Object>) ((List<?>) bobs.getBody()).get(0);
        ResponseEntity<Map> claim = client(OTHER_TENANT_USER).post()
                .uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "\"" + ((Number) task.get("version")).longValue() + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(claim.getStatusCode().value()).isEqualTo(404);

        // bob's identical claim succeeds, so the 404 is the tenant boundary and not a dead route.
        ResponseEntity<Map> bobsClaim = client("bob").post().uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "\"" + ((Number) task.get("version")).longValue() + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(bobsClaim.getStatusCode().value()).isEqualTo(200);
        assertThat(alice().get().uri("/cases/{id}/tasks", caseId).retrieve().toEntity(List.class)
                .getStatusCode().value()).isEqualTo(200);
    }

    /**
     * Final whole-branch review, Minor: {@code TaskController} resolved {@code If-Match} BEFORE
     * the tenant check, against an unfiltered lookup — so {@code If-Match: *} distinguished an
     * existing foreign-tenant task from a nonexistent id. {@code ETagSupport.expectedVersion}
     * answers 412 {@code precondition-failed} for a wildcard with no current representation and
     * proceeds otherwise, so a foreign task got past that point and only then hit the tenant
     * check, while a made-up id was refused earlier and differently.
     * {@code CaseController.expectedVersion} was given a tenant filter for exactly this reason;
     * the task path was not, and the asymmetry with its own sibling is what made it a defect.
     *
     * <p>The assertion is that the two ids are INDISTINGUISHABLE to the foreign caller — same
     * status and same {@code code} — not merely that each is refused. "Both are refused" is
     * satisfied by the defective version too (412 and 404 are both refusals); the oracle is the
     * difference between them, so the difference is what has to disappear.
     */
    @Test
    void ifMatchStarCannotDistinguishAnotherTenantsTaskFromANonexistentOne() {
        deployAndCreateCase();

        Map<String, Object> task = (Map<String, Object>) ((List<?>) client("bob").get()
                .uri("/tasks?limit=50").retrieve().toEntity(List.class).getBody()).get(0);

        ResponseEntity<Map> foreignTask = client(OTHER_TENANT_USER).post()
                .uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "*")
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> nonexistent = client(OTHER_TENANT_USER).post()
                .uri("/tasks/{id}/claim", "task-that-does-not-exist")
                .header("If-Match", "*")
                .retrieve().toEntity(Map.class);

        assertThat(foreignTask.getStatusCode().value())
                .as("an existing foreign-tenant task and a made-up id must be indistinguishable")
                .isEqualTo(nonexistent.getStatusCode().value());
        assertThat(foreignTask.getBody().get("code")).isEqualTo(nonexistent.getBody().get("code"));
        assertThat(foreignTask.getStatusCode().value()).isEqualTo(404);

        // Negative control: for the task's OWN tenant, If-Match: * is still a working wildcard
        // and not simply broken — otherwise "indistinguishable" is satisfied by refusing
        // everyone.
        ResponseEntity<Map> owner = client("bob").post().uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "*")
                .retrieve().toEntity(Map.class);
        assertThat(owner.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * Fix round 1, review finding I4. {@code CM_IDEMPOTENCY_KEY} is keyed on
     * {@code (KEY_, SCOPE_)}; with a constant scope, two callers picking the same key collide —
     * and with identical bodies the second one is handed the FIRST one's case id and replayed
     * body, across tenants. The scope now carries the caller.
     */
    @Test
    void twoCallersMayUseTheSameIdempotencyKeyWithoutCollidingOrSeeingEachOthersCase() {
        deployDefinition();
        Map<String, Object> body = Map.of("caseDefinitionKey", DEFINITION_KEY,
                "tenantId", TENANT, "title", "Same title");

        ResponseEntity<Map> alices = alice().post().uri("/cases").header("Idempotency-Key", "retry-1")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> carols = client("carol").post().uri("/cases")
                .header("Idempotency-Key", "retry-1")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);

        assertThat(alices.getStatusCode().value()).isEqualTo(201);
        assertThat(carols.getStatusCode().value())
                .as("carol's key is her own; she must not get a 409 for a key she never used")
                .isEqualTo(201);
        assertThat(carols.getBody().get("id")).isNotEqualTo(alices.getBody().get("id"));
        assertThat(carols.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");

        // Each caller's own retry still replays their own case, so scoping did not break the
        // mechanism it protects.
        ResponseEntity<Map> alicesRetry = alice().post().uri("/cases")
                .header("Idempotency-Key", "retry-1")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(alicesRetry.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(alicesRetry.getBody().get("id")).isEqualTo(alices.getBody().get("id"));
    }

    /**
     * Fix round 2, review finding Important 2. {@code CaseDefinitionService} read {@code tenantId}
     * out of the submitted document, so the deploy endpoint was the one place left where a caller
     * chose the tenant they wrote into. dave is a legitimate administrator — the {@code admin}
     * gate passes for him — of tenant t2, and the fixture document declares {@code "tenantId":
     * "t1"}. Publishing it would put HIS plan model behind t1's case type: every future t1 case of
     * that key instantiates it, because {@code CaseService.create} resolves through
     * {@code findLatest(key, tenant)}.
     */
    @Test
    void anAdministratorCannotDeployACaseDefinitionIntoAnotherTenant() {
        deployDefinition();

        ResponseEntity<Map> refused = client(OTHER_TENANT_USER).post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON).body(definitionJson())
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getBody()).containsEntry("code", "forbidden");

        // t1's definition is untouched: still version 1, still alice's.
        ResponseEntity<Map> t1Definition = alice().get()
                .uri("/case-definitions/{k}?tenantId={t}", DEFINITION_KEY, TENANT)
                .retrieve().toEntity(Map.class);
        assertThat(t1Definition.getBody()).containsEntry("version", 1);

        // dave IS an administrator, so the admin gate is not what refused him: the SAME key,
        // deployed under his own tenant, succeeds — and lands at version 1, independently of
        // t1's version 1 of that key.
        //
        // Fix round 3 is what makes this expressible. CM_CASE_DEF.ID_ used to be
        // "{key}:{version}", which contradicted UQ_CM_CASE_DEF's tenant-scoped
        // UNIQUE (KEY_, VERSION_NO_, TENANT_ID_): both tenants' first deploy of one key minted
        // "widget-review:1" and the second died on the primary key with ORA-00001. The id is now
        // "{tenant}:{key}:{version}". Until that change this test had to dodge the collision by
        // using a different key, which meant it never exercised the case that actually matters.
        ResponseEntity<Map> ownTenant = client(OTHER_TENANT_USER).post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(definitionJson().replace("\"tenantId\": \"t1\"", "\"tenantId\": \"t2\""))
                .retrieve().toEntity(Map.class);
        assertThat(ownTenant.getStatusCode().value())
                .as("the same key must be deployable in a second tenant")
                .isEqualTo(201);
        assertThat(ownTenant.getBody())
                .containsEntry("key", DEFINITION_KEY)
                .containsEntry("tenantId", "t2")
                .containsEntry("version", 1)
                .containsEntry("id", "t2:" + DEFINITION_KEY + ":1");

        // t1's own definition still exists, at its own version 1, under its own id.
        assertThat(alice().get().uri("/case-definitions/{k}?tenantId={t}", DEFINITION_KEY, TENANT)
                .retrieve().toEntity(Map.class).getBody())
                .containsEntry("version", 1)
                .containsEntry("id", TENANT + ":" + DEFINITION_KEY + ":1");

        // A document that names no tenant binds to the caller's, rather than landing untenanted
        // where no tenant-scoped listing would ever find it again — and versions continue within
        // t2, not across tenants.
        ResponseEntity<Map> untenanted = client(OTHER_TENANT_USER).post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(definitionJson().replace("\"tenantId\": \"t1\",", ""))
                .retrieve().toEntity(Map.class);
        assertThat(untenanted.getStatusCode().value()).isEqualTo(201);
        assertThat(untenanted.getBody()).containsEntry("tenantId", "t2")
                .containsEntry("version", 2);

        // Each administrator sees only their own tenant's definition of the shared key, at their
        // own version.
        assertThat((List<Map<String, Object>>) client(OTHER_TENANT_USER).get()
                .uri("/case-definitions").retrieve().toEntity(List.class).getBody())
                .singleElement()
                .satisfies(d -> assertThat(d).containsEntry("key", DEFINITION_KEY)
                        .containsEntry("version", 2).containsEntry("tenantId", "t2"));

        assertThat((List<Map<String, Object>>) alice().get().uri("/case-definitions")
                .retrieve().toEntity(List.class).getBody())
                .singleElement()
                .satisfies(d -> assertThat(d).containsEntry("key", DEFINITION_KEY)
                        .containsEntry("version", 1).containsEntry("tenantId", TENANT));
    }

    /**
     * The consumer that actually matters after the id change (fix round 3): {@code
     * CaseService.create} resolves a definition through {@code findLatest(key, tenant)}, so two
     * tenants holding the same key must each instantiate THEIR OWN plan model, not each other's.
     */
    @Test
    void twoTenantsSharingACaseDefinitionKeyEachInstantiateTheirOwnDefinition() {
        deployDefinition();

        // t2 deploys the SAME key with a distinguishable plan item. The distinguisher is the
        // defKey, not the display name: PlanModelInstantiator sets PlanItem.name from defKey, so
        // renaming only the "name" field would produce identical plan items and the assertion
        // below would be unfalsifiable. Renaming the milestone is safe — nothing declares it as
        // a parentStageKey, and its entry criterion refers to "review", which is unchanged.
        client(OTHER_TENANT_USER).post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(definitionJson()
                        .replace("\"tenantId\": \"t1\"", "\"tenantId\": \"t2\"")
                        .replace("\"defKey\": \"reviewed\"", "\"defKey\": \"t2reviewed\""))
                .retrieve().toEntity(Map.class);

        ResponseEntity<Map> t1Case = createCase("t1 case");
        assertThat(t1Case.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map> t2Case = client(OTHER_TENANT_USER).post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "title", "t2 case"))
                .retrieve().toEntity(Map.class);
        assertThat(t2Case.getStatusCode().value()).isEqualTo(201);

        // Each case carries its own tenant's definition, both at version 1.
        assertThat(t1Case.getBody()).containsEntry("tenantId", TENANT)
                .containsEntry("caseDefinitionVersion", 1);
        assertThat(t2Case.getBody()).containsEntry("tenantId", "t2")
                .containsEntry("caseDefinitionVersion", 1);

        // ...and the plan model each one instantiated is its own, not the other tenant's.
        assertThat(planItemNames(alice(), (String) t1Case.getBody().get("id")))
                .contains("reviewed").doesNotContain("t2reviewed");
        assertThat(planItemNames(client(OTHER_TENANT_USER), (String) t2Case.getBody().get("id")))
                .contains("t2reviewed").doesNotContain("reviewed");
    }

    private List<String> planItemNames(org.springframework.web.client.RestClient client, String caseId) {
        return ((List<Map<String, Object>>) client.get().uri("/cases/{id}/plan-items", caseId)
                .retrieve().toEntity(List.class).getBody()).stream()
                .map(i -> (String) i.get("name"))
                .toList();
    }

    @Test
    void aCaseCannotBeCreatedIntoAnotherTenant() {
        deployDefinition();

        ResponseEntity<Map> refused = client(OTHER_TENANT_USER).post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT, "title", "T"))
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody()).containsEntry("code", "forbidden");
        assertThat(items(alice().get().uri("/cases").retrieve().toEntity(Map.class))).isEmpty();
    }
}
