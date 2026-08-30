package org.casemgmt.rest.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level regression for the remote contract: a task request is accepted as an operation;
 * its confirmed CM_TASK state is changed only by later engine evidence.
 */
@SpringBootTest(classes = CaseApiTestConfig.class,
        properties = "casemgmt.test.remote=true",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteTaskOperationHttpTest extends CaseApiHttpTestBase {

    @Test
    void remoteClaimReplayReturnsTheOriginalAcceptedOperationWithoutASecondCommand() {
        String taskId = taskId(deployAndCreateCase());

        ResponseEntity<Map> first = claim(taskId, "0", "claim-replay");
        ResponseEntity<Map> replay = claim(taskId, "0", "claim-replay");

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
        assertThat(first.getBody()).containsEntry("status", "PENDING");
        assertThat(replay.getBody()).containsEntry("id", first.getBody().get("id"));
        assertThat(confirmedTask(taskId).get("state")).isEqualTo("OPEN");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='claim-replay'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void remoteCompleteReplayReturnsTheOriginalAcceptedOperationWithoutCompletingTheTask() {
        String taskId = taskId(deployAndCreateCase());
        jdbc().sql("UPDATE CM_TASK SET STATE_='CLAIMED', ASSIGNEE_='bob', VERSION_=1 WHERE ID_=:id")
                .param("id", taskId).update();

        ResponseEntity<Map> first = complete(taskId, "1", "complete-replay");
        ResponseEntity<Map> replay = complete(taskId, "1", "complete-replay");

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
        assertThat(first.getBody()).containsEntry("status", "PENDING");
        assertThat(replay.getBody()).containsEntry("id", first.getBody().get("id"));
        assertThat(confirmedTask(taskId).get("state")).isEqualTo("CLAIMED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='complete-replay'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    private String taskId(Map<String, Object> created) {
        ResponseEntity<List> response = alice().get().uri("/cases/{id}/tasks", created.get("id"))
                .retrieve().toEntity(List.class);
        return ((Map<String, Object>) response.getBody().getFirst()).get("id").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> confirmedTask(String taskId) {
        List<?> rows = alice().get().uri("/tasks?limit=50")
                .retrieve().toEntity(List.class).getBody();
        return (Map<String, Object>) rows.stream()
                .filter(Map.class::isInstance).map(row -> (Map<String, Object>) row)
                .filter(task -> taskId.equals(task.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> claim(String taskId, String version, String key) {
        return client("bob").post().uri("/tasks/{id}/claim", taskId)
                .header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> complete(String taskId, String version, String key) {
        return client("bob").post().uri("/tasks/{id}/complete", taskId)
                .header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("variables", Map.of("outcome", "approve")))
                .retrieve().toEntity(Map.class);
    }
}
