package org.casemgmt.rest.http;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.domain.TaskState;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level regression for a task backed by a published, active BPMN release. A remote request
 * is accepted as an operation; only later engine evidence may change confirmed task state.
 */
@SpringBootTest(classes = CaseApiTestConfig.class,
        properties = "casemgmt.test.remote=true",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteTaskOperationHttpTest extends CaseApiHttpTestBase {

    private static final String KEY = "remote-task-review";
    private static final String CASE_ID = "remote-operation-case";
    private static final String TASK_ID = "remote-operation-task";

    @Autowired CaseDefinitionReleaseService releases;
    @Autowired CaseDefinitionVersionService versions;
    @Autowired CaseRepository cases;
    @Autowired PlanItemRepository planItems;
    @Autowired CaseTaskRepository tasks;

    @BeforeEach
    void seedPublishedBpmnTask() {
        var orchestration = releases.publish(KEY, TENANT, ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", bpmn().getBytes(StandardCharsets.UTF_8), "alice");
        var contract = releases.publish(KEY, TENANT, ReleaseKind.CONTRACT, "application/json",
                contract().getBytes(StandardCharsets.UTF_8), "alice");
        var presentation = releases.publish(KEY, TENANT, ReleaseKind.PRESENTATION, "application/json",
                "{\"version\":\"1.0\",\"sections\":[]}".getBytes(StandardCharsets.UTF_8), "alice");
        var binding = versions.bind(KEY, TENANT, orchestration.id(), contract.id(), presentation.id(), "alice");

        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        cases.insert(new CaseInstance(CASE_ID, CaseApiTestConfig.ENGINE_ID, TENANT,
                binding.caseDefinitionId(), KEY, 1, null, "Remote task case", CaseState.ACTIVE,
                CasePriority.MEDIUM, null, null, "alice", "NONE", null, null, Map.of(), 0L, now, now, null));
        planItems.insert(new PlanItem("remote-operation-item", CASE_ID, "review", PlanItemType.HUMAN_TASK,
                "Review", PlanItemState.ACTIVE, null, false, 1, "engine-task-remote", null,
                null, 0L, now, now, null));
        tasks.insert(new CaseTask(TASK_ID, CASE_ID, "remote-operation-item", "engine-task-remote",
                "Review", null, TaskState.OPEN, null, null, List.of("reviewers"), "reviewForm", 50,
                null, null, CaseTask.EngineSync.SYNCED, 0L, now, now, null));
    }

    @Test
    void remoteClaimReplayReturnsTheOriginalAcceptedOperationWithoutASecondCommand() {
        ResponseEntity<Map> first = claim("0", "claim-replay");
        ResponseEntity<Map> replay = claim("0", "claim-replay");

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
        assertThat(first.getBody()).containsEntry("status", "PENDING");
        assertThat(replay.getBody()).containsEntry("id", first.getBody().get("id"));
        assertThat(tasks.require(TASK_ID).state()).isEqualTo(TaskState.OPEN);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='claim-replay'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void remoteCompleteReplayReturnsTheOriginalAcceptedOperationWithoutCompletingTheTask() {
        jdbc().sql("UPDATE CM_TASK SET STATE_='CLAIMED', ASSIGNEE_='bob', VERSION_=1 WHERE ID_=:id")
                .param("id", TASK_ID).update();

        ResponseEntity<Map> first = complete("1", "complete-replay");
        ResponseEntity<Map> replay = complete("1", "complete-replay");

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
        assertThat(first.getBody()).containsEntry("status", "PENDING");
        assertThat(replay.getBody()).containsEntry("id", first.getBody().get("id"));
        assertThat(tasks.require(TASK_ID).state()).isEqualTo(TaskState.CLAIMED);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='complete-replay'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> claim(String version, String key) {
        return client("bob").post().uri("/tasks/{id}/claim", TASK_ID)
                .header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> complete(String version, String key) {
        return client("bob").post().uri("/tasks/{id}/complete", TASK_ID)
                .header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("variables", Map.of("outcome", "approve")))
                .retrieve().toEntity(Map.class);
    }

    private static String contract() {
        return """
                {"key":"remote-task-review","orchestrationMode":"BPMN","fields":{},
                 "candidateGroups":["reviewers"],"forms":{"reviewForm":{"schema":
                 {"type":"object","required":["outcome"],"properties":{"outcome":{"type":"string"}}}}}}
                """;
    }

    private static String bpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                  <bpmn:process id="remote-task-review" isExecutable="true">
                    <bpmn:userTask id="review" operaton:formKey="reviewForm"
                        operaton:candidateGroups="reviewers"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
