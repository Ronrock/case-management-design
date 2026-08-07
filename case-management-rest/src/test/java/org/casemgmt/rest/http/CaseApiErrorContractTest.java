package org.casemgmt.rest.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error contract as a client actually receives it (carried findings C2, C3, C4).
 *
 * <p>Task 22 tested its {@code ProblemDetailHandler} exclusively by calling its methods and
 * inspecting the returned {@code ProblemDetail}. That leaves two things unproven, and both are
 * things a client depends on: that the response really carries
 * {@code Content-Type: application/problem+json}, and that Jackson 3 — which is what Spring Boot
 * 4 uses for HTTP, not the Jackson 2 the handler's own module compiles against — actually
 * serialises the RFC 9457 extension properties ({@code code}, {@code violations},
 * {@code availableActions}) rather than dropping them. Every assertion below is on a real
 * response.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiErrorContractTest extends CaseApiHttpTestBase {

    @Test
    void aMissingIfMatchOnAMutationIsRefusedWith428AsProblemJson() {
        Map<String, Object> created = deployAndCreateCase();

        ResponseEntity<Map> response = alice().patch().uri("/cases/{id}", created.get("id"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "New title"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(428);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("code", "if-match-required")
                .containsEntry("status", 428)
                .containsEntry("type", "https://casemgmt.org/problems/if-match-required");
    }

    @Test
    void aStaleIfMatchIsRefusedWith412VersionConflict() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        ResponseEntity<Map> first = alice().patch().uri("/cases/{id}", id).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "One"))
                .retrieve().toEntity(Map.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> stale = alice().patch().uri("/cases/{id}", id).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "Two"))
                .retrieve().toEntity(Map.class);

        assertThat(stale.getStatusCode().value()).isEqualTo(412);
        assertThat(stale.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(stale.getBody()).containsEntry("code", "version-conflict");
    }

    @Test
    void anUnparseableIfMatchIsARequestError() {
        Map<String, Object> created = deployAndCreateCase();

        ResponseEntity<Map> response = alice().patch().uri("/cases/{id}", created.get("id"))
                .header("If-Match", "\"not-a-version\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "X"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "invalid-request");
    }

    /**
     * Carried finding C4, first half: {@code If-Match: *} means "any current representation"
     * (RFC 7232 §3.1), so the update proceeds against whatever version is current — with no
     * version in hand at all, and without the caller having read the resource first.
     */
    @Test
    void ifMatchStarUpdatesTheCurrentRepresentationWhateverItsVersion() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        // Move the version away from 0 so "*" cannot be passing by coincidence.
        alice().patch().uri("/cases/{id}", id).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "One"))
                .retrieve().toEntity(Map.class);

        ResponseEntity<Map> wildcard = alice().patch().uri("/cases/{id}", id).header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "Two"))
                .retrieve().toEntity(Map.class);

        assertThat(wildcard.getStatusCode().value()).isEqualTo(200);
        assertThat(wildcard.getBody()).containsEntry("title", "Two");
        assertThat(wildcard.getHeaders().getETag()).isEqualTo("\"2\"");
    }

    /**
     * Carried finding C4, second half — the one {@code ETagSupport.parseIfMatch} deliberately
     * left to its caller. {@code If-Match: *} evaluates to FALSE when the origin server has no
     * current representation, and a false precondition is 412. Not 404: the client asked
     * "update it if it still exists", and the answer must be distinguishable from "you are
     * looking at the wrong URL" and from "you have the wrong version".
     */
    @Test
    void ifMatchStarAgainstAMissingCaseIsAFailedPreconditionNotANotFound() {
        deployDefinition();

        ResponseEntity<Map> response = alice().patch().uri("/cases/{id}", "eng-test:does-not-exist")
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "X"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "precondition-failed");

        // Contrast: a concrete tag against the same missing case is an ordinary 404, so the 412
        // above is genuinely the wildcard rule and not "missing case always answers 412".
        ResponseEntity<Map> concrete = alice().patch().uri("/cases/{id}", "eng-test:does-not-exist")
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "X"))
                .retrieve().toEntity(Map.class);
        assertThat(concrete.getStatusCode().value()).isEqualTo(404);
        assertThat(concrete.getBody()).containsEntry("code", "not-found");
    }

    @Test
    void anUnknownCaseIsA404ProblemDocument() {
        deployDefinition();

        ResponseEntity<Map> response = alice().get().uri("/cases/{id}", "eng-test:nope")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "not-found");
    }

    /**
     * Carried finding C3, the sharpest part: RFC 6901 pointers must survive Jackson 3
     * serialisation verbatim, <b>including the empty-string root pointer</b>. Task 17 changed
     * these from networknt's JSONPath flavour specifically so a renderer can bind each message
     * to an input with a standard pointer resolver, and {@code ""} — the pointer for a violation
     * against the whole document, which is what a missing required property produces — is the
     * shape most easily corrupted into {@code "/"} or dropped entirely on the way out.
     */
    @Test
    void aFormViolationIs422AndItsRfc6901PointersSurviveSerialisationVerbatim() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        Map<String, Object> task = openTask(caseId);
        String taskId = (String) task.get("id");
        ResponseEntity<Map> claimed = alice().post().uri("/tasks/{id}/claim", taskId)
                .header("If-Match", "\"" + ((Number) task.get("version")).longValue() + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);

        // reviewForm requires "outcome"; submitting nothing violates the schema at the document
        // root, and submitting a value outside the enum violates it at "/outcome".
        ResponseEntity<Map> missingRequired = alice().post().uri("/tasks/{id}/complete", taskId)
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("variables", Map.of()))
                .retrieve().toEntity(Map.class);

        assertThat(missingRequired.getStatusCode().value()).isEqualTo(422);
        assertThat(missingRequired.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(missingRequired.getBody()).containsEntry("code", "form-invalid");

        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) missingRequired.getBody().get("violations");
        assertThat(violations).isNotEmpty();
        assertThat(violations).allSatisfy(v -> assertThat(v).containsKeys("pointer", "message"));
        assertThat(violations).extracting(v -> v.get("pointer"))
                .as("the root pointer is the empty string, never \"/\" and never absent")
                .contains("");

        ResponseEntity<Map> badEnum = alice().post().uri("/tasks/{id}/complete", taskId)
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", Map.of("outcome", "maybe")))
                .retrieve().toEntity(Map.class);

        assertThat(badEnum.getStatusCode().value()).isEqualTo(422);
        assertThat((List<Map<String, Object>>) badEnum.getBody().get("violations"))
                .extracting(v -> v.get("pointer"))
                .as("a field-level pointer is a real RFC 6901 pointer, not JSONPath")
                .contains("/outcome");
    }

    /**
     * Carried finding C2: a plan item declaring a {@code formKey} the definition has no schema
     * for is a definition-authoring typo, and used to escape {@code CaseTaskService} as a bare
     * {@code IllegalStateException} — an opaque 500 with no {@code code} to switch on.
     */
    @Test
    void aTaskDeclaringAFormKeyTheDefinitionDoesNotDefineIsAClientErrorNotAServerFault() {
        deployDefinitionWithADanglingFormKey();

        ResponseEntity<Map> created = alice().post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "dangling-form", "tenantId", TENANT, "title", "T"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String caseId = (String) created.getBody().get("id");

        Map<String, Object> task = openTask(caseId);
        ResponseEntity<Map> claimed = alice().post().uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "\"" + ((Number) task.get("version")).longValue() + "\"")
                .retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> completed = alice().post().uri("/tasks/{id}/complete", task.get("id"))
                .header("If-Match", claimed.getHeaders().getETag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", Map.of("outcome", "approve")))
                .retrieve().toEntity(Map.class);

        assertThat(completed.getStatusCode().value())
                .as("a definition-authoring typo must not surface as a 500")
                .isEqualTo(400);
        assertThat(completed.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(completed.getBody())
                .containsEntry("code", "case-definition-invalid")
                .containsEntry("caseDefinitionKey", "dangling-form");
        assertThat((String) completed.getBody().get("detail")).contains("noSuchForm");
    }

    private Map<String, Object> openTask(String caseId) {
        ResponseEntity<List> tasks = alice().get().uri("/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class);
        return (Map<String, Object>) ((List<?>) tasks.getBody()).get(0);
    }

    /**
     * A definition whose human task points at a form key its {@code forms} map does not contain.
     * Written out here rather than derived from the shared fixture so the defect being tested is
     * visible in one place — and, like every other definition in this module, it names no real
     * case type.
     */
    private void deployDefinitionWithADanglingFormKey() {
        String json = """
                {
                  "key": "dangling-form",
                  "name": "Dangling Form",
                  "tenantId": "t1",
                  "roles": ["owner", "handler"],
                  "forms": { "presentForm": { "type": "object" } },
                  "planItems": [
                    { "defKey": "step", "type": "HUMAN_TASK", "name": "Step",
                      "manualActivation": false, "required": true, "formKey": "noSuchForm",
                      "candidateGroups": ["reviewers"], "sortOrder": 10 }
                  ]
                }""";
        ResponseEntity<Map> deployed = alice().post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON).body(json)
                .retrieve().toEntity(Map.class);
        assertThat(deployed.getStatusCode().value()).isEqualTo(201);
    }
}
