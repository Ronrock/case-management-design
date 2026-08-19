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
                .contentType(MERGE_PATCH).body(Map.of("title", "New title"))
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
                .contentType(MERGE_PATCH).body(Map.of("title", "One"))
                .retrieve().toEntity(Map.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> stale = alice().patch().uri("/cases/{id}", id).header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH).body(Map.of("title", "Two"))
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
                .contentType(MERGE_PATCH).body(Map.of("title", "X"))
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
                .contentType(MERGE_PATCH).body(Map.of("title", "One"))
                .retrieve().toEntity(Map.class);

        ResponseEntity<Map> wildcard = alice().patch().uri("/cases/{id}", id).header("If-Match", "*")
                .contentType(MERGE_PATCH).body(Map.of("title", "Two"))
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
                .contentType(MERGE_PATCH).body(Map.of("title", "X"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "precondition-failed");

        // Contrast: a concrete tag against the same missing case is an ordinary 404, so the 412
        // above is genuinely the wildcard rule and not "missing case always answers 412".
        ResponseEntity<Map> concrete = alice().patch().uri("/cases/{id}", "eng-test:does-not-exist")
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH).body(Map.of("title", "X"))
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

    /**
     * Fix round 1, review finding I5. {@code CasePriority.valueOf(...)} was called directly, so
     * a bad enum raised a bare {@code IllegalArgumentException} — which the handler deliberately
     * does not map — and shipped as a 500. That is the same defect shape as carried finding C2,
     * reintroduced one layer up in the controller.
     */
    @Test
    void anUnknownEnumValueIsAClientErrorNamingTheLegalOnes() {
        deployDefinition();

        ResponseEntity<Map> badPriority = alice().post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT,
                        "title", "T", "priority", "URGENT"))
                .retrieve().toEntity(Map.class);

        assertThat(badPriority.getStatusCode().value())
                .as("a bad enum must not surface as a 500").isEqualTo(400);
        assertThat(badPriority.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(badPriority.getBody()).containsEntry("code", "invalid-request");
        assertThat((String) badPriority.getBody().get("detail"))
                .contains("URGENT").contains("priority").contains("MEDIUM");

        // The same shape on the query side, which the controller now parses itself rather than
        // leaving to Spring's binder (whose 400 would prove nothing about this controller).
        ResponseEntity<Map> badState = alice().get().uri("/cases?state=BOGUS")
                .retrieve().toEntity(Map.class);
        assertThat(badState.getStatusCode().value()).isEqualTo(400);
        assertThat(badState.getBody()).containsEntry("code", "invalid-request");
        assertThat((String) badState.getBody().get("detail")).contains("BOGUS").contains("ACTIVE");

        // A legal value on the same parameter still works, so the 400 is the value and not the
        // parameter being rejected outright.
        assertThat(alice().get().uri("/cases?state=ACTIVE").retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(200);
    }

    /**
     * {@code PATCH /cases/{id}} declares {@code consumes = application/merge-patch+json} per the
     * spec (fix round 1, I6). Asserted so the media type in the controller and the one every
     * other test sends cannot silently drift apart.
     */
    @Test
    void patchDeclaresTheMergePatchMediaTypeAndRejectsPlainJson() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        ResponseEntity<String> wrongType = alice().patch().uri("/cases/{id}", id)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "One"))
                .retrieve().toEntity(String.class);
        assertThat(wrongType.getStatusCode().value()).isEqualTo(415);

        ResponseEntity<Map> rightType = alice().patch().uri("/cases/{id}", id)
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH).body(Map.of("title", "One"))
                .retrieve().toEntity(Map.class);
        assertThat(rightType.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * The spec declares {@code If-Match} on milestone achieve and process start (fix round 1,
     * I6). Neither sub-resource carries a version of its own, so it is enforced against the
     * case's — a real precondition, not a header read and discarded.
     */
    @Test
    void milestoneAchieveAndProcessStartRequireAndEnforceIfMatch() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        ResponseEntity<Map> noHeader = alice().post().uri("/cases/{id}/processes", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process"))
                .retrieve().toEntity(Map.class);
        assertThat(noHeader.getStatusCode().value()).isEqualTo(428);
        assertThat(noHeader.getBody()).containsEntry("code", "if-match-required");

        ResponseEntity<Map> staleHeader = alice().post().uri("/cases/{id}/processes", caseId)
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process"))
                .retrieve().toEntity(Map.class);
        assertThat(staleHeader.getStatusCode().value()).isEqualTo(412);
        assertThat(staleHeader.getBody()).containsEntry("code", "version-conflict");

        assertThat(alice().post().uri("/cases/{id}/processes", caseId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "some-process"))
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(201);
    }

    /**
     * <b>Corrects a finding, rather than fixing one.</b> The final whole-branch review recorded
     * as a Minor that "{@code ProblemDetail.title} is never set, so it is omitted from every
     * problem body", making every response incomplete against RFC 9457 §3.1. On Spring Framework
     * 7 (Boot 4) that is false: {@code ProblemDetail.getTitle()} falls back to the status reason
     * phrase when the field is null, so the wire body has always carried a conformant title. The
     * proposed fix — {@code setTitle(status.getReasonPhrase())} in {@code ProblemDetailHandler}
     * — was written, this test passed, the setter was stripped, and this test passed again with
     * identical values. It wrote exactly what the getter derives and could never change an
     * outcome, so it was dropped instead of kept (see {@code ProblemDetailHandler.decorate}).
     *
     * <p>This test stays regardless, because the ASSERTION is real even though the fix was not:
     * it pins "the body a client actually receives carries a title", whoever supplies the value,
     * and it is what would catch a future Spring upgrade that removed the fallback. Asserted
     * across two distinct statuses, since §3.1 requires {@code title} to summarise the TYPE and
     * therefore to track the status rather than be one constant string, and alongside
     * {@code code}, so a change that filled in {@code title} by clobbering the field clients
     * switch on would still fail here.
     */
    @Test
    void everyProblemBodyCarriesAnRfc9457Title() {
        Map<String, Object> created = deployAndCreateCase();
        String id = (String) created.get("id");

        ResponseEntity<Map> missingIfMatch = alice().patch().uri("/cases/{id}", id)
                .contentType(MERGE_PATCH).body(Map.of("title", "New title"))
                .retrieve().toEntity(Map.class);
        assertThat(missingIfMatch.getStatusCode().value()).isEqualTo(428);
        assertThat(missingIfMatch.getBody())
                .containsEntry("code", "if-match-required")
                .containsEntry("title", "Precondition Required");

        ResponseEntity<Map> notFound = alice().get().uri("/cases/{id}", "eng-a:nope")
                .retrieve().toEntity(Map.class);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody())
                .containsEntry("code", "not-found")
                .containsEntry("title", "Not Found");
    }

    /**
     * Final whole-branch review, Minor: {@code ProblemDetailHandler} did not extend
     * {@code ResponseEntityExceptionHandler}, so every exception Spring MVC raises BEFORE a
     * handler runs escaped the advice entirely. The sharpest of those is a malformed request
     * body ({@code HttpMessageNotReadableException}) — the one error a generic consumer is most
     * likely to trigger while learning the API — which shipped as Spring's default error page
     * instead of problem+json.
     *
     * <p>Sends bytes that are not JSON at all, so the failure happens in the message converter
     * with no handler method ever invoked. Asserts the full contract shape, not just the status:
     * the media type, the stable {@code code}, and the {@code title} — a 400 that arrived as
     * {@code text/html} would satisfy a status-only assertion.
     */
    @Test
    void aMalformedRequestBodyIsProblemJsonLikeEveryOtherError() {
        ResponseEntity<Map> response = alice().post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{ this is not json")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(response.getBody())
                .containsEntry("code", "invalid-request")
                .containsEntry("title", "Bad Request")
                .containsKey("detail");
    }

    @Test
    void aMalformedCaseDefinitionIsRejectedAtDeployTimeAsAClientError() {
        ResponseEntity<Map> response = alice().post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"key":"bad-criteria","name":"Bad Criteria","tenantId":"%s",
                         "planItems":[
                           {"defKey":"done","type":"MILESTONE","name":"Done",
                            "entryCriteria":["${items.missing.state == 'COMPLETED'}"],
                            "sortOrder":10}]}""".formatted(TENANT))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .containsEntry("code", "case-definition-invalid")
                .containsEntry("caseDefinitionKey", "bad-criteria");
    }

    /**
     * Final whole-branch review, Minor: the 500 {@code model-error} path copied
     * {@code e.getMessage()} straight into the client-visible {@code detail}. Exception messages
     * in this codebase quote plan-item ids, case-definition keys, state names and raw criterion
     * expressions, so that is information disclosure on a fault the client did not cause and can
     * do nothing about.
     *
     * <p>Triggers a real {@code CriterionEvaluationException} by deploying a definition whose
     * entry criterion is not a valid expression, then creating a case of it — the evaluator runs
     * inside {@code CaseService.create}. Asserts BOTH halves: the contract shape survives (still
     * problem+json, still a stable {@code code}), and the leaked identifier is absent from the
     * entire serialised body, not merely from {@code detail} — a message copied into some other
     * property would leak just as much.
     */
    @Test
    void aServerFaultDoesNotLeakTheExceptionMessageToTheClient() {
        String key = "leaky-model";
        alice().post().uri("/case-definitions").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"key":"%s","name":"Leaky","tenantId":"%s",
                         "planItems":[
                           {"defKey":"gate","type":"HUMAN_TASK","name":"gate",
                            "entryCriteria":["${ this is not a valid juel expression }"],
                            "sortOrder":10}]}""".formatted(key, TENANT))
                .retrieve().toEntity(Map.class);

        ResponseEntity<String> raw = alice().post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", key, "tenantId", TENANT, "title", "T"))
                .retrieve().toEntity(String.class);

        assertThat(raw.getStatusCode().value()).isEqualTo(500);
        assertThat(raw.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(raw.getBody())
                .contains("\"code\":\"model-error\"")
                .as("the plan-item defKey the exception message quotes must not reach the client")
                .doesNotContain("gate")
                .doesNotContain("juel");
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
