package org.casemgmt.rest.http;

import org.casemgmt.repo.MilestoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class WorkerPermissionsAuthorizationHttpTest extends CaseApiHttpTestBase {

    @Autowired MilestoneRepository milestoneRepo;

    @Test
    void workerPermissionsDenialBlocksCaseReadAndSearchExecution() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        ResponseEntity<Map> read = client("erin").get().uri("/cases/{id}", caseId)
                .retrieve().toEntity(Map.class);
        assertThat(read.getStatusCode().value()).isEqualTo(403);
        assertThat(read.getBody()).containsEntry("code", "forbidden");

        ResponseEntity<Map> search = client("erin").post().uri("/search/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("q", "A case", "scopes", List.of("cases")))
                .retrieve().toEntity(Map.class);
        assertThat(search.getStatusCode().value()).isEqualTo(403);
        assertThat(search.getBody()).containsEntry("code", "forbidden");
    }

    @Test
    void caseListingDoesNotExposeDeniedItemsOrTotals() {
        deployAndCreateCase();

        ResponseEntity<Map> response = client("erin").get().uri("/cases")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) response.getBody().get("items")).isEmpty();
        assertThat(response.getBody())
                .containsEntry("totalItems", 0)
                .containsEntry("totalPages", 0);
    }

    @Test
    void workerPermissionsDenialBlocksDocumentLinking() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        ResponseEntity<Map> refused = client("erin").post().uri("/cases/{id}/documents", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "blocked.pdf",
                        "contentUrl", "https://dms.example/documents/blocked"))
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody()).containsEntry("code", "forbidden");
    }

    @Test
    void workerPermissionsDenialBlocksCollaborationReads() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");

        assertForbidden(client("erin").get().uri("/cases/{id}/comments", caseId)
                .retrieve().toEntity(Map.class));
        assertForbidden(client("erin").get().uri("/cases/{id}/milestones", caseId)
                .retrieve().toEntity(Map.class));
        assertForbidden(client("erin").get().uri("/cases/{id}/processes", caseId)
                .retrieve().toEntity(Map.class));
    }

    @Test
    void workerPermissionsDenialBlocksCollaborationWrites() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        String version = "\"" + created.get("version") + "\"";
        assertForbidden(client("erin").post().uri("/cases/{id}/comments", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", "blocked", "visibility", "internal"))
                .retrieve().toEntity(Map.class));
        assertForbidden(client("erin").post().uri("/cases/{id}/processes", caseId)
                .header("If-Match", version)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionKey", "blocked-process",
                        "variables", Map.of()))
                .retrieve().toEntity(Map.class));
    }

    @Test
    void workerPermissionsDenialBlocksTaskClaimEvenWhenLocalCandidateGroupWouldAllowIt() {
        Map<String, Object> created = deployAndCreateCase();
        String caseId = (String) created.get("id");
        ResponseEntity<List> tasks = alice().get().uri("/cases/{id}/tasks", caseId)
                .retrieve().toEntity(List.class);
        Map<String, Object> task = (Map<String, Object>) tasks.getBody().get(0);

        ResponseEntity<Map> refused = client("erin").post()
                .uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "\"" + task.get("version") + "\"")
                .retrieve().toEntity(Map.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody()).containsEntry("code", "forbidden");
    }

    private static void assertForbidden(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("code", "forbidden");
    }
}
