package org.casemgmt.poc;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.poc.support.PocAppEmbeddedTestBase;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.service.Actor;
import org.casemgmt.sla.SlaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code openapi-specs.md} the contract of record rather than decoration (spec §9): real
 * responses off the real running application, validated against the published document, at the
 * repository root, unmodified.
 *
 * <p>{@code openapi-specs.md} is a YAML document that happens to carry a {@code .md} extension —
 * it parses as YAML with nothing stripped.
 *
 * <p><b>Why every check here is paired with a negative control.</b> An OpenAPI 3.0 object schema
 * with no {@code required} list and no {@code additionalProperties: false} — which is what almost
 * every schema in this document is — accepts {@code {}} and accepts any number of undeclared
 * fields. A conformance test that only asserts "the report is empty" would therefore pass against
 * an empty body, against a body whose fields are all named differently, and against a validator
 * that silently failed to resolve the operation at all. That is the exact shape of the eight
 * vacuous mechanisms this project has already found. So each conformance assertion below is
 * followed by a mutation of the SAME body that must be REJECTED, pinning that the validator really
 * loaded this spec, really resolved this operation, and really applied its schema.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class OpenApiConformanceIT extends PocAppEmbeddedTestBase {

    /**
     * The seeded caller holding the {@code admin} identity group — the only one that can reach
     * {@code POST /webhooks} or {@code GET /webhooks/{id}/dead-letters}. Added to
     * {@code PocBootstrap} in Task 27's corrective round; before that no seeded user held it, so
     * three of this API's endpoints were unreachable in the application that demonstrates it.
     */
    private static final String ADMIN = "olivia";

    private static final OpenApiInteractionValidator VALIDATOR = validator();

    @Autowired private EventRepository events;
    @Autowired private WebhookRepository webhooks;
    @Autowired private WebhookDispatcher dispatcher;
    @Autowired private SlaService sla;
    @Autowired private JdbcClient jdbc;

    private static OpenApiInteractionValidator validator() {
        try {
            String specification = Files.readString(Path.of("..", "openapi-specs.md"));
            return OpenApiInteractionValidator.createForInlineApiSpecification(specification).build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load openapi-specs.md as an OpenAPI document", e);
        }
    }

    @Test
    void createdCaseResponsesConformToTheSpec() {
        ResponseEntity<String> created = client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "openapi-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "Spec"))
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        assertConforms("/cases", Request.Method.POST, 201, created.getBody());

        // Negative control: CaseState has a closed enum, so an out-of-enum state must be reported.
        // If this passes, the schema was never applied and the assertion above proved nothing.
        assertRejected("/cases", Request.Method.POST, 201,
                created.getBody().replace("\"state\":\"ACTIVE\"", "\"state\":\"NOT_A_CASE_STATE\""),
                "state");
    }

    @Test
    void readCaseAndTaskResponsesConformToTheSpec() {
        String caseId = (String) ((Map) client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "openapi-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "Spec read"))
                .retrieve().toEntity(Map.class).getBody()).get("id");

        ResponseEntity<String> single = client("alice").get()
                .uri("/case-api/v2/cases/{id}", caseId).retrieve().toEntity(String.class);
        assertThat(single.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}", Request.Method.GET, 200, single.getBody());
        assertRejected("/cases/{caseId}", Request.Method.GET, 200,
                single.getBody().replace("\"priority\":\"MEDIUM\"", "\"priority\":\"WHENEVER\""),
                "priority");

        ResponseEntity<String> tasks = client("alice").get()
                .uri("/case-api/v2/cases/{id}/tasks", caseId).retrieve().toEntity(String.class);
        assertThat(tasks.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}/tasks", Request.Method.GET, 200, tasks.getBody());
        // The tasks response is an ARRAY at the top level; a bare object is the shape mistake a
        // paging refactor would make, and the spec is the only thing that would catch it.
        assertRejected("/cases/{caseId}/tasks", Request.Method.GET, 200, "{}", "array");
    }

    /**
     * The {@code Page} envelope on {@code GET /cases}. Task 24 adopted it over a bare array
     * specifically to conform to this document; nothing in this module exercised it until now.
     */
    @Test
    void theCaseListingPageEnvelopeConformsToTheSpec() {
        ResponseEntity<String> listing = client("alice").get()
                .uri("/case-api/v2/cases?tenantId=t1&page=0&pageSize=5")
                .retrieve().toEntity(String.class);
        assertThat(listing.getStatusCode().value()).isEqualTo(200);

        assertConforms("/cases", Request.Method.GET, 200, listing.getBody());
        assertRejected("/cases", Request.Method.GET, 200,
                listing.getBody().replaceFirst("\"page\":\\d+", "\"page\":\"first\""), "page");
    }

    @Test
    void caseMutationsAndEventFeedsConformToTheSpec() {
        ResponseEntity<String> created = client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "openapi-mutate-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Spec mutate"))
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String caseId = stringField(created.getBody(), "id");

        ResponseEntity<String> patched = client("alice").patch()
                .uri("/case-api/v2/cases/{id}", caseId)
                .header("If-Match", "*")
                .contentType(MediaType.valueOf("application/merge-patch+json"))
                .body(Map.of("title", "Spec mutate patched"))
                .retrieve().toEntity(String.class);
        assertThat(patched.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}", Request.Method.PATCH, 200, patched.getBody());
        assertRejected("/cases/{caseId}", Request.Method.PATCH, 200,
                patched.getBody().replace("\"state\":\"ACTIVE\"", "\"state\":\"MAYBE\""),
                "state");

        ResponseEntity<String> caseEvents = client("alice").get()
                .uri("/case-api/v2/cases/{id}/events", caseId).retrieve().toEntity(String.class);
        assertThat(caseEvents.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}/events", Request.Method.GET, 200, caseEvents.getBody());
        assertRejected("/cases/{caseId}/events", Request.Method.GET, 200,
                caseEvents.getBody().replaceFirst("\"specversion\":\"1.0\"",
                        "\"specversion\":1"),
                "specversion");

        ResponseEntity<String> allEvents = client("alice").get()
                .uri("/case-api/v2/events?limit=5").retrieve().toEntity(String.class);
        assertThat(allEvents.getStatusCode().value()).isEqualTo(200);
        assertConforms("/events", Request.Method.GET, 200, allEvents.getBody());
        assertRejected("/events", Request.Method.GET, 200, "{\"items\":{}}", "items");

        ResponseEntity<String> cancelled = client("alice").post()
                .uri("/case-api/v2/cases/{id}/cancel", caseId)
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "openapi conformance cleanup"))
                .retrieve().toEntity(String.class);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}/cancel", Request.Method.POST, 200, cancelled.getBody());
    }

    @Test
    void caseDefinitionDiscoveryResponsesConformToTheSpec() {
        ResponseEntity<String> listing = client("alice").get()
                .uri("/case-api/v2/case-definitions").retrieve().toEntity(String.class);
        assertThat(listing.getStatusCode().value()).isEqualTo(200);

        assertConforms("/case-definitions", Request.Method.GET, 200, listing.getBody());
        assertRejected("/case-definitions", Request.Method.GET, 200, "{}", "array");

        List<Map<String, Object>> definitions = client("alice").get()
                .uri("/case-api/v2/case-definitions").retrieve().toEntity(List.class).getBody();
        assertThat(definitions).isNotEmpty();
        String key = (String) definitions.get(0).get("key");

        ResponseEntity<String> detail = client("alice").get()
                .uri("/case-api/v2/case-definitions/{key}", key).retrieve().toEntity(String.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);

        assertConforms("/case-definitions/{key}", Request.Method.GET, 200, detail.getBody());
        assertRejected("/case-definitions/{key}", Request.Method.GET, 200,
                detail.getBody().replaceFirst("\"version\":\\d+", "\"version\":\"one\""), "version");

        Map<String, Object> detailBody = client("alice").get()
                .uri("/case-api/v2/case-definitions/{key}", key).retrieve().toEntity(Map.class).getBody();
        String formKey = (String) ((List<?>) detailBody.get("formKeys")).get(0);

        ResponseEntity<String> schema = client("alice").get()
                .uri("/case-api/v2/case-definitions/{key}/forms/{formKey}", key, formKey)
                .retrieve().toEntity(String.class);
        assertThat(schema.getStatusCode().value()).isEqualTo(200);
        assertThat(schema.getHeaders().getContentType()).hasToString("application/schema+json");

        assertConforms("/case-definitions/{key}/forms/{formKey}", Request.Method.GET, 200,
                schema.getBody(), "application/schema+json");
        assertRejected("/case-definitions/{key}/forms/{formKey}", Request.Method.GET, 200,
                "[]", "application/schema+json", "object");
    }

    @Test
    void advertisedTaskFormCanBeLoadedFromThePinnedCaseVersionAndCompleted() {
        Map<String, Object> created = client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "versioned-form-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Versioned form flow"))
                .retrieve().toEntity(Map.class).getBody();
        String caseId = (String) created.get("id");
        int definitionVersion = ((Number) created.get("caseDefinitionVersion")).intValue();

        List<Map<String, Object>> tasks = client("alice").get()
                .uri("/case-api/v2/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class).getBody();
        Map<String, Object> task = tasks.getFirst();
        String taskId = (String) task.get("id");

        ResponseEntity<Map> claimed = client("alice").post()
                .uri("/case-api/v2/tasks/{id}/claim", taskId)
                .header("If-Match", "\"" + task.get("version") + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> completeAction = ((List<Map<String, Object>>) claimed.getBody()
                .get("availableActions")).getFirst();
        String formKey = (String) completeAction.get("formKey");

        ResponseEntity<String> form = client("alice").get()
                .uri("/case-api/v2/case-definitions/complaint/versions/{version}/forms/{formKey}",
                        definitionVersion, formKey)
                .retrieve().toEntity(String.class);
        assertThat(form.getStatusCode().value()).isEqualTo(200);
        assertThat(form.getHeaders().getContentType()).hasToString("application/schema+json");
        assertThat(form.getBody()).contains("\"channel\"").contains("\"summary\"");
        assertConforms("/case-definitions/{key}/versions/{version}/forms/{formKey}",
                Request.Method.GET, 200, form.getBody(), "application/schema+json");
        assertRejected("/case-definitions/{key}/versions/{version}/forms/{formKey}",
                Request.Method.GET, 200, "[]", "application/schema+json", "object");

        ResponseEntity<Map> completed = client("alice").post()
                .uri("/case-api/v2" + completeAction.get("href"))
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", Map.of("channel", "web", "summary", "Registered")))
                .retrieve().toEntity(Map.class);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody()).containsEntry("state", "COMPLETED");
    }

    @Test
    void planItemsTasksCollaborationAndSlaReadModelsConformToTheSpec() {
        String caseId = (String) ((Map) client("alice").post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "openapi-readmodels-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Spec read models"))
                .retrieve().toEntity(Map.class).getBody()).get("id");

        ResponseEntity<String> planItems = client("alice").get()
                .uri("/case-api/v2/cases/{id}/plan-items", caseId).retrieve().toEntity(String.class);
        assertThat(planItems.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}/plan-items", Request.Method.GET, 200, planItems.getBody());
        assertRejected("/cases/{caseId}/plan-items", Request.Method.GET, 200,
                planItems.getBody().replaceFirst("\"state\":\"[A-Z_]+\"", "\"state\":\"BROKEN\""),
                "state");

        ResponseEntity<String> worklist = client("alice").get()
                .uri("/case-api/v2/tasks?limit=5").retrieve().toEntity(String.class);
        assertThat(worklist.getStatusCode().value()).isEqualTo(200);
        assertThat(worklist.getBody()).contains("\"engineSync\":\"SYNCED\"");
        assertConforms("/tasks", Request.Method.GET, 200, worklist.getBody());
        assertRejected("/tasks", Request.Method.GET, 200,
                worklist.getBody().replaceFirst("\"engineSync\":\"SYNCED\"", "\"engineSync\":\"LOST\""),
                "engineSync");

        ResponseEntity<String> comments = client("alice").get()
                .uri("/case-api/v2/cases/{id}/comments", caseId).retrieve().toEntity(String.class);
        assertThat(comments.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}/comments", Request.Method.GET, 200, comments.getBody());
        assertRejected("/cases/{caseId}/comments", Request.Method.GET, 200, "{}", "array");

        ResponseEntity<String> createdComment = client("alice").post()
                .uri("/case-api/v2/cases/{id}/comments", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", "OpenAPI comment", "visibility", "internal"))
                .retrieve().toEntity(String.class);
        assertThat(createdComment.getStatusCode().value()).isEqualTo(201);
        assertConforms("/cases/{caseId}/comments", Request.Method.POST, 201, createdComment.getBody());
        assertRejected("/cases/{caseId}/comments", Request.Method.POST, 201,
                createdComment.getBody().replace("\"visibility\":\"internal\"", "\"visibility\":\"private\""),
                "visibility");

        ResponseEntity<String> milestones = client("alice").get()
                .uri("/case-api/v2/cases/{id}/milestones", caseId).retrieve().toEntity(String.class);
        assertThat(milestones.getStatusCode().value()).isEqualTo(200);
        assertConforms("/cases/{caseId}/milestones", Request.Method.GET, 200, milestones.getBody());
        assertRejected("/cases/{caseId}/milestones", Request.Method.GET, 200,
                "[{\"id\":\"milestone-openapi-negative\",\"name\":\"Broken\",\"achieved\":\"no\"}]",
                "achieved");

        sla.startClocks(caseId, "sla-complaint", new Actor("alice", List.of("handlers")));
        ResponseEntity<String> slas = client("alice").get()
                .uri("/case-api/v2/cases/{id}/slas", caseId).retrieve().toEntity(String.class);
        assertThat(slas.getStatusCode().value()).isEqualTo(200);
        assertThat(slas.getBody()).contains("\"status\":\"RUNNING\"");
        assertConforms("/cases/{caseId}/slas", Request.Method.GET, 200, slas.getBody());
        assertRejected("/cases/{caseId}/slas", Request.Method.GET, 200,
                slas.getBody().replaceFirst("\"status\":\"RUNNING\"", "\"status\":\"UNKNOWN\""),
                "status");
    }

    @Test
    void webhookSubscriptionAndRedeliveryResponsesConformToTheSpec() {
        ResponseEntity<String> created = client(ADMIN).post().uri("/case-api/v2/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "http://127.0.0.1:1/hook", "eventTypes", java.util.List.of("*")))
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertConforms("/webhooks", Request.Method.POST, 201, created.getBody());
        assertRejected("/webhooks", Request.Method.POST, 201,
                created.getBody().replace("\"eventTypes\":[\"*\"]", "\"eventTypes\":\"*\""),
                "eventTypes");
        String webhookId = stringField(created.getBody(), "id");

        ResponseEntity<String> listing = client(ADMIN).get()
                .uri("/case-api/v2/webhooks").retrieve().toEntity(String.class);
        assertThat(listing.getStatusCode().value()).isEqualTo(200);
        assertConforms("/webhooks", Request.Method.GET, 200, listing.getBody());
        assertRejected("/webhooks", Request.Method.GET, 200, "{}", "array");

        ResponseEntity<String> redelivery = client(ADMIN).post()
                .uri("/case-api/v2/webhooks/{id}/dead-letters/redeliver", webhookId)
                .retrieve().toEntity(String.class);
        assertThat(redelivery.getStatusCode().value()).isEqualTo(202);
        assertConformsNoBody("/webhooks/{webhookId}/dead-letters/redeliver",
                Request.Method.POST, 202);
    }

    /**
     * {@code GET /webhooks/{webhookId}/dead-letters} — added in Task 27's corrective round for a
     * specific reason: the endpoint was implemented in the fix wave and its response was shaped
     * from {@code CM_WEBHOOK_DELIVERY}'s columns rather than from this document, so it shipped
     * with two documented fields missing ({@code event}, {@code failedAt}) and four undocumented
     * ones present. Nothing caught it, because no conformance case exercised this path — the
     * validator only checks the operations it is handed. That is the same shape as the report's
     * own "a green build is evidence only about the tests that ran".
     *
     * <p>Exercises the EMPTY-queue case deliberately: a `[]` is a valid instance of the array
     * schema, so on its own it would prove nothing at all — which is exactly why the negative
     * control below is not optional here. It sends a populated body of the shape the controller
     * actually builds and then breaks one documented field's TYPE, so the assertion is pinned to
     * this operation's own schema having really been resolved and applied.
     */
    @Test
    void theDeadLetterQueueResponseConformsToTheSpec() {
        String webhookId = (String) ((Map) client(ADMIN).post().uri("/case-api/v2/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "http://127.0.0.1:1/hook", "eventTypes", java.util.List.of("*")))
                .retrieve().toEntity(Map.class).getBody()).get("id");
        assertThat(webhookId).as("the subscription must actually be created").isNotNull();

        // A REAL dead letter, produced by the application's own dispatcher against its own
        // schema — not a hand-written body. The empty queue is a valid instance of the array
        // schema and would prove nothing on its own, so the row has to exist before the GET.
        // The subscriber URL above resolves to nothing, so drainOnce fails the delivery for
        // real; maxRetries is 1, so the second pass dead-letters it.
        long seq = events.append(new CaseEvent(CaseIds.newId(), "/engines/eng-a/cases",
                "org.example.cm.case.created", "eng-a:spec-dlq", "t1",
                OffsetDateTime.now(), Map.of("probe", true)));
        webhooks.enqueueDelivery(CaseIds.newId(), webhookId, seq);
        deadLetterEverythingFor(webhookId);

        ResponseEntity<String> deadLetters = client(ADMIN).get()
                .uri("/case-api/v2/webhooks/{id}/dead-letters", webhookId)
                .retrieve().toEntity(String.class);
        assertThat(deadLetters.getStatusCode().value()).isEqualTo(200);
        assertThat(deadLetters.getBody())
                .as("the conformance assertion below is vacuous against an empty array")
                .contains("\"attempts\"").contains("\"event\"").contains("\"failedAt\"");

        assertConforms("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200,
                deadLetters.getBody());

        // Negative control: `attempts` is declared an integer, so a string must be rejected. If
        // this passes, the operation was never resolved and the assertion above proves nothing.
        assertRejected("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200,
                deadLetters.getBody().replaceFirst("\"attempts\":\\d+", "\"attempts\":\"lots\""),
                "attempts");

        // Second negative control, on the EMBEDDED CloudEvent specifically: it declares
        // required [id, source, type, specversion], so dropping one must be caught. Without this,
        // `event` could be any object at all and the conforming assertion would not notice —
        // which is exactly how the original implementation shipped without `event` at all.
        assertRejected("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200,
                deadLetters.getBody().replaceFirst("\"specversion\":\"1.0\",", ""), "specversion");
    }

    /**
     * The NULL branches of the same response (corrective round 2). The case above exercises only
     * the fully-resolvable row, which is why it did not catch that the schema this same change
     * published <b>forbade the nulls the code emits</b>: `event` was declared as a bare
     * {@code $ref}, and in OpenAPI 3.0 a {@code $ref} ignores every sibling key — so the comment
     * beside it saying "nullable on purpose" was a statement of intent with nothing enforcing it,
     * and `CloudEvent` being {@code type: object} rejects a JSON null outright. `failedAt` had no
     * {@code nullable} either. Both are reachable: `event` when a delivery is dead-lettered
     * because its event could not be resolved — the very reason {@code WebhookDispatcher} records
     * as "event N not found" — and `failedAt` for any row dead-lettered before the
     * {@code cm-poc-webhook-delivery-failed-at} changeset existed.
     *
     * <p><b>`failedAt: null` is a REAL response.</b> The column is nulled first, which is exactly
     * the state an upgraded deployment's pre-existing DEAD rows are in, and the body then comes
     * off the running application like every other assertion in this class.
     *
     * <p><b>`event: null` is derived from that real body</b>, and deliberately so:
     * {@code FK_CM_WHD_EVENT} makes a delivery pointing at a non-existent event impossible to
     * create, so the branch cannot be reached from a test without disabling a constraint the
     * schema is entitled to rely on. Deriving the shape is the same technique the negative
     * controls above already use, applied positively — and what is under test is the SCHEMA's
     * treatment of a null, not the controller's ability to produce one.
     */
    @Test
    void theDeadLetterQueuesNullBranchesConformToTheSpec() {
        String webhookId = (String) ((Map) client(ADMIN).post().uri("/case-api/v2/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", "http://127.0.0.1:1/hook", "eventTypes", java.util.List.of("*")))
                .retrieve().toEntity(Map.class).getBody()).get("id");

        long seq = events.append(new CaseEvent(CaseIds.newId(), "/engines/eng-a/cases",
                "org.example.cm.case.created", "eng-a:spec-dlq-null", "t1",
                OffsetDateTime.now(), Map.of("probe", true)));
        webhooks.enqueueDelivery(CaseIds.newId(), webhookId, seq);
        deadLetterEverythingFor(webhookId);

        // Exactly the state a row dead-lettered before the FAILED_AT_ changeset is left in.
        jdbc.sql("UPDATE CM_WEBHOOK_DELIVERY SET FAILED_AT_ = NULL WHERE WEBHOOK_ID_ = :w")
                .param("w", webhookId).update();

        ResponseEntity<String> withNullFailedAt = client(ADMIN).get()
                .uri("/case-api/v2/webhooks/{id}/dead-letters", webhookId)
                .retrieve().toEntity(String.class);
        assertThat(withNullFailedAt.getStatusCode().value()).isEqualTo(200);
        assertThat(withNullFailedAt.getBody())
                .as("the null branch must actually be present, or this asserts nothing")
                .contains("\"failedAt\":null");
        assertConforms("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200,
                withNullFailedAt.getBody());

        // The unresolvable-event shape: the key is OMITTED, never sent as null. Derived from
        // the real body above by deleting it, which is the exact JSON deadLetterBody() produces
        // when bySeqs resolves nothing for the row.
        String withoutEvent = withNullFailedAt.getBody()
                .replaceFirst("\"event\":\\{.*?\\}\\},", "");
        assertThat(withoutEvent).doesNotContain("\"event\"");
        assertConforms("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200, withoutEvent);

        // ...and an explicit null is NOT the shape the contract accepts, which is precisely why
        // the controller omits the key. Pins the reason the omission exists, so nobody
        // "simplifies" it back to a null that this document cannot express for a $ref.
        assertRejected("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200,
                withNullFailedAt.getBody().replaceFirst("\"event\":\\{.*?\\}\\}", "\"event\":null"),
                "event");

        // Negative control: nulls are permitted only where declared. `attempts` is not nullable,
        // so this must still be rejected — otherwise "the schema accepts my nulls" would be
        // satisfied by a schema that accepts anything.
        assertRejected("/webhooks/{webhookId}/dead-letters", Request.Method.GET, 200,
                withoutEvent.replaceFirst("\"attempts\":\\d+", "\"attempts\":null"), "attempts");
    }

    /** Drains until this subscription's deliveries are all DEAD, so the GET has real rows. */
    private void deadLetterEverythingFor(String webhookId) {
        for (int pass = 0; pass < 10 && webhooks.deadLetters(webhookId).isEmpty(); pass++) {
            dispatcher.drainOnce();
            jdbc.sql("UPDATE CM_WEBHOOK_DELIVERY SET NEXT_ATTEMPT_AT_ = SYSTIMESTAMP - "
                    + "INTERVAL '1' HOUR WHERE WEBHOOK_ID_ = :w").param("w", webhookId).update();
        }
        assertThat(webhooks.deadLetters(webhookId))
                .as("the dispatcher must actually have dead-lettered the probe delivery")
                .isNotEmpty();
    }

    // ---- plumbing ----

    private void assertConforms(String path, Request.Method method, int status, String body) {
        assertConforms(path, method, status, body, "application/json");
    }

    private void assertConforms(String path, Request.Method method, int status, String body,
                                String contentType) {
        ValidationReport report = validate(path, method, status, body, contentType);
        assertThat(report.getMessages())
                .as("%s %s -> %d does not match openapi-specs.md:%n%s%nbody was:%n%s",
                        method, path, status, report, body)
                .isEmpty();
    }

    private void assertConformsNoBody(String path, Request.Method method, int status) {
        ValidationReport report = VALIDATOR.validateResponse(path, method,
                SimpleResponse.Builder.status(status).build());
        assertThat(report.getMessages())
                .as("%s %s -> %d does not match openapi-specs.md:%n%s",
                        method, path, status, report)
                .isEmpty();
    }

    private void assertRejected(String path, Request.Method method, int status, String body,
                                String expectedInMessage) {
        assertRejected(path, method, status, body, "application/json", expectedInMessage);
    }

    private void assertRejected(String path, Request.Method method, int status, String body,
                                String contentType, String expectedInMessage) {
        ValidationReport report = validate(path, method, status, body, contentType);
        assertThat(report.getMessages())
                .as("negative control for %s %s: a deliberately invalid body was accepted, so the "
                        + "conforming assertion beside it proves nothing", method, path)
                .isNotEmpty();
        assertThat(report.toString())
                .as("negative control for %s %s must fail on '%s', not on some unrelated mismatch",
                        method, path, expectedInMessage)
                .contains(expectedInMessage);
    }

    private ValidationReport validate(String path, Request.Method method, int status, String body) {
        return validate(path, method, status, body, "application/json");
    }

    private ValidationReport validate(String path, Request.Method method, int status, String body,
                                      String contentType) {
        return VALIDATOR.validateResponse(path, method,
                SimpleResponse.Builder.status(status)
                        .withContentType(contentType)
                        .withBody(body)
                        .build());
    }

    private static String stringField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("No string field '" + field + "' in " + json);
        }
        int valueStart = start + needle.length();
        int valueEnd = json.indexOf('"', valueStart);
        if (valueEnd < 0) {
            throw new AssertionError("Unterminated string field '" + field + "' in " + json);
        }
        return json.substring(valueStart, valueEnd);
    }
}
