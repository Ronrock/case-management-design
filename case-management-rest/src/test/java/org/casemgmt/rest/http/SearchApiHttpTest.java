package org.casemgmt.rest.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class SearchApiHttpTest extends CaseApiHttpTestBase {

    @Test
    void searchesCasesThroughTheProjectionProviderWithProviderStatus() {
        deployDefinition();
        Map<String, Object> created = createCase("BK-SEARCH-1", "Needle payment case");

        ResponseEntity<Map> response = alice().get()
                .uri("/search/cases?q=BK-SEARCH-1")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(searchItems(response)).extracting(item -> item.get("id"))
                .containsExactly(created.get("id"));
        assertThat(searchItems(response).get(0))
                .containsEntry("resultType", "case")
                .containsEntry("sourceProvider", "case-projection");
        assertThat((List<Map<String, Object>>) response.getBody().get("providerStatuses"))
                .extracting(status -> status.get("id"))
                .containsExactly("case-projection");
    }

    @Test
    void searchNeverLetsAnotherTenantDiscoverCaseExistence() {
        deployDefinition();
        createCase("BK-TENANT-LEAK", "Tenant scoped case");

        ResponseEntity<Map> response = client("dave").get()
                .uri("/search/cases?q=BK-TENANT-LEAK")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(searchItems(response)).isEmpty();
    }

    @Test
    void casesEndpointTreatsFreeTextWildcardsAsLiteralOracleText() {
        deployDefinition();
        Map<String, Object> literal = createCase("BK-REST-100%_", "Literal 100%_ case");
        createCase("BK-REST-100XA", "Literal 100XA case");

        ResponseEntity<Map> response = alice().get()
                .uri(builder -> builder.path("/search/cases")
                        .queryParam("q", "100%_")
                        .build())
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(searchItems(response)).extracting(item -> item.get("id"))
                .containsExactly(literal.get("id"));
        assertThat((List<String>) searchItems(response).get(0).get("matchedFields"))
                .contains("businessKey", "title");
    }

    @Test
    void queryEndpointSupportsStatusAliasAndProviderStatusSuppression() {
        deployDefinition();
        Map<String, Object> created = createCase("BK-STATUS-FILTER", "Filtered status case");

        ResponseEntity<Map> response = alice().post().uri("/search/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "q", "status",
                        "scopes", List.of("cases"),
                        "filters", Map.of("status", created.get("state")),
                        "includeProviderStatus", false))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(searchItems(response)).extracting(item -> item.get("id"))
                .containsExactly(created.get("id"));
        assertThat((List<Map<String, Object>>) response.getBody().get("providerStatuses")).isEmpty();
    }

    @Test
    void exposesOrchestratedQueryProvidersSuggestionsAndFacetWarnings() {
        deployDefinition();
        createCase("BK-SUGGEST-1", "Suggestion target");

        ResponseEntity<Map> query = alice().post().uri("/search/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("q", "Suggestion", "scopes", List.of("cases")))
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> providers = alice().get().uri("/search/providers")
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> suggestions = alice().get().uri("/search/suggestions?q=Suggestion")
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> facets = alice().get().uri("/search/facets?scope=cases")
                .retrieve().toEntity(Map.class);

        assertThat(searchItems(query)).hasSize(1);
        assertThat((List<Map<String, Object>>) providers.getBody().get("providers"))
                .extracting(status -> status.get("id"))
                .contains("case-projection", "document-metadata");
        assertThat((List<Map<String, Object>>) suggestions.getBody().get("items"))
                .extracting(item -> item.get("suggestionType"))
                .containsExactly("case");
        assertThat((List<Map<String, Object>>) facets.getBody().get("warnings"))
                .extracting(warning -> warning.get("code"))
                .containsExactly("facet-unavailable");
    }

    @Test
    void invalidSearchScopeIsAProblemJsonBadRequest() {
        ResponseEntity<Map> response = alice().post().uri("/search/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("scopes", List.of("everything")))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "invalid-request");
    }

    @Test
    void invalidSearchStateIsAProblemJsonBadRequest() {
        ResponseEntity<Map> response = alice().get()
                .uri("/search/cases?state=UNKNOWN")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "invalid-request");
    }

    @Test
    void facetWarningIsAppendedWithoutDiscardingProviderWarnings() {
        ResponseEntity<Map> response = alice().get()
                .uri("/search/facets?scope=tasks")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) response.getBody().get("warnings"))
                .extracting(warning -> warning.get("code"))
                .containsExactly("no-provider", "facet-unavailable");
    }

    @Test
    void excessiveSearchPageIsAProblemJsonBadRequestInsteadOfOverflowing() {
        ResponseEntity<Map> response = alice().get()
                .uri("/search/cases?page=2147483647&pageSize=200")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("code", "invalid-request")
                .containsEntry("detail", "Search result window exceeds 10,000 items");
    }

    private Map<String, Object> createCase(String businessKey, String title) {
        ResponseEntity<Map> created = alice().post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT,
                        "businessKey", businessKey, "title", title, "priority", "HIGH"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return created.getBody();
    }

    private static List<Map<String, Object>> searchItems(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }
}
