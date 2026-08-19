package org.casemgmt.rest.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class DocumentApiHttpTest extends CaseApiHttpTestBase {

    @Test
    void documentsCanBeLinkedListedSearchedAndRemoved() {
        deployDefinition();
        ResponseEntity<Map> created = createCase("Document evidence case");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String caseId = (String) created.getBody().get("id");

        ResponseEntity<Map> added = alice().post().uri("/cases/{id}/documents", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "passport-evidence.pdf",
                        "category", "evidence",
                        "mimeType", "application/pdf",
                        "sizeBytes", 1234,
                        "contentUrl", "https://dms.example/documents/passport-evidence"))
                .retrieve().toEntity(Map.class);

        assertThat(added.getStatusCode().value()).isEqualTo(201);
        assertThat(added.getBody())
                .containsEntry("caseId", caseId)
                .containsEntry("name", "passport-evidence.pdf")
                .containsEntry("contentUrl", "https://dms.example/documents/passport-evidence");
        String documentId = (String) added.getBody().get("id");

        ResponseEntity<List> listed = alice().get().uri("/cases/{id}/documents", caseId)
                .retrieve().toEntity(List.class);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) listed.getBody())
                .extracting(document -> document.get("id"))
                .containsExactly(documentId);

        ResponseEntity<Map> search = alice().post().uri("/search/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("q", "passport",
                        "scopes", List.of("documents"),
                        "includeProviderStatus", true))
                .retrieve().toEntity(Map.class);
        assertThat(search.getStatusCode().value()).isEqualTo(200);
        assertThat(searchItems(search)).extracting(item -> item.get("id"))
                .containsExactly(documentId);
        assertThat(searchItems(search).get(0))
                .containsEntry("resultType", "document")
                .containsEntry("sourceProvider", "document-metadata");

        ResponseEntity<Map> otherTenantSearch = client("dave").post().uri("/search/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("q", "passport", "scopes", List.of("documents")))
                .retrieve().toEntity(Map.class);
        assertThat(searchItems(otherTenantSearch)).isEmpty();

        ResponseEntity<Void> deleted = alice().delete()
                .uri("/cases/{id}/documents/{documentId}", caseId, documentId)
                .retrieve().toBodilessEntity();
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        assertThat(alice().get().uri("/cases/{id}/documents", caseId)
                .retrieve().toEntity(List.class).getBody()).isEmpty();
    }

    @Test
    void documentMutationsUseTheCollaborationPolicy() {
        deployDefinition();
        ResponseEntity<Map> created = createCase("Document authorization case");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String caseId = (String) created.getBody().get("id");

        Map<String, Object> body = Map.of("name", "internal-memo.pdf",
                "contentUrl", "https://dms.example/documents/internal-memo");
        ResponseEntity<Map> refusedAdd = client("carol").post()
                .uri("/cases/{id}/documents", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(Map.class);
        assertThat(refusedAdd.getStatusCode().value()).isEqualTo(409);
        assertThat(refusedAdd.getBody()).containsEntry("code", "action-not-available");

        ResponseEntity<Map> added = alice().post().uri("/cases/{id}/documents", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(Map.class);
        assertThat(added.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> refusedDelete = client("carol").delete()
                .uri("/cases/{id}/documents/{documentId}", caseId, added.getBody().get("id"))
                .retrieve().toEntity(Map.class);
        assertThat(refusedDelete.getStatusCode().value()).isEqualTo(409);
        assertThat(refusedDelete.getBody()).containsEntry("code", "action-not-available");
    }

    private static List<Map<String, Object>> searchItems(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }
}
