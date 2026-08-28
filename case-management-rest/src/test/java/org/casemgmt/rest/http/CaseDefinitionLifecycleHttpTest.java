package org.casemgmt.rest.http;

import org.casemgmt.service.CaseDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDefinitionLifecycleHttpTest extends CaseApiHttpTestBase {

    @Autowired
    CaseDefinitionService definitions;

    @Test
    void creatingACaseWithoutAnActiveBpmnBindingReturnsAStableConflict() {
        definitions.deployBpmn("unbound-bpmn", """
                {"key":"unbound-bpmn","name":"Unbound BPMN","fields":[],"forms":{}}
                """, "alice", TENANT);

        ResponseEntity<Map> response = create("unbound-bpmn");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("code", "case-definition-not-active")
                .containsEntry("availableActions", java.util.List.of());
    }

    @Test
    void aTrulyUnknownDefinitionRemainsNotFoundRatherThanBecomingAConflict() {
        ResponseEntity<Map> response = create("does-not-exist");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("code", "not-found");
    }

    @Test
    void planModelCaseCreationRemainsAvailableWithoutABinding() {
        deployDefinition();

        ResponseEntity<Map> response = create(DEFINITION_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("caseDefinitionKey", DEFINITION_KEY);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> create(String definitionKey) {
        return alice().post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", definitionKey, "tenantId", TENANT,
                        "title", "Lifecycle", "variables", Map.of()))
                .retrieve().toEntity(Map.class);
    }
}
