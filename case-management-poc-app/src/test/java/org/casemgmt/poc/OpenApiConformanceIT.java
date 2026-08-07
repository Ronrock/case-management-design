package org.casemgmt.poc;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.casemgmt.poc.support.PocAppEmbeddedTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final OpenApiInteractionValidator VALIDATOR = validator();

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
