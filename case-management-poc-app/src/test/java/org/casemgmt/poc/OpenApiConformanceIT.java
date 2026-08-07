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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
        ValidationReport report = validate(path, method, status, body);
        assertThat(report.getMessages())
                .as("%s %s -> %d does not match openapi-specs.md:%n%s%nbody was:%n%s",
                        method, path, status, report, body)
                .isEmpty();
    }

    private void assertRejected(String path, Request.Method method, int status, String body,
                                String expectedInMessage) {
        ValidationReport report = validate(path, method, status, body);
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
        return VALIDATOR.validateResponse(path, method,
                SimpleResponse.Builder.status(status)
                        .withContentType("application/json")
                        .withBody(body)
                        .build());
    }
}
