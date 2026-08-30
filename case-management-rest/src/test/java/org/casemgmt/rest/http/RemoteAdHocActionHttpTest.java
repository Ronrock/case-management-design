package org.casemgmt.rest.http;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP + Oracle proof that a lost response replays the one durable ad-hoc operation. */
@SpringBootTest(classes = CaseApiTestConfig.class,
        properties = "casemgmt.test.remote=true",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteAdHocActionHttpTest extends CaseApiHttpTestBase {

    private static final String KEY = "remote-ad-hoc";
    private static final String CASE_ID = "remote-ad-hoc-case";

    @Autowired CaseDefinitionReleaseService releases;
    @Autowired CaseDefinitionVersionService versions;
    @Autowired CaseRepository cases;
    @Autowired ParticipantRepository participants;

    @BeforeEach
    void seedPublishedAction() {
        var orchestration = releases.publish(KEY, TENANT, ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", bpmn().getBytes(StandardCharsets.UTF_8), "alice");
        var contract = releases.publish(KEY, TENANT, ReleaseKind.CONTRACT, "application/json",
                contract().getBytes(StandardCharsets.UTF_8), "alice");
        var presentation = releases.publish(KEY, TENANT, ReleaseKind.PRESENTATION, "application/json",
                "{\"version\":\"1.0\",\"sections\":[]}".getBytes(StandardCharsets.UTF_8), "alice");
        var binding = versions.bind(KEY, TENANT, orchestration.id(), contract.id(), presentation.id(), "alice");
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T12:00:00Z");
        cases.insert(new CaseInstance(CASE_ID, CaseApiTestConfig.ENGINE_ID, TENANT,
                binding.caseDefinitionId(), KEY, 1, null, "Ad hoc case", CaseState.ACTIVE,
                CasePriority.MEDIUM, null, null, "alice", "NONE", null, null, Map.of(), 0L, now, now, null));
        participants.insert("action-handler", CASE_ID, "alice", null, "handler");
    }

    @Test
    void lostResponseReplayReturnsTheSameAcceptedOperationAndDoesNotCreateAnotherCommand() {
        ResponseEntity<Map> first = execute("alice", "0", "lost-response-key");
        ResponseEntity<Map> replay = execute("alice", "0", "lost-response-key");

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
        assertThat(first.getHeaders().getLocation()).hasToString("/case-api/v2/operations/" + first.getBody().get("operationId"));
        assertThat(replay.getBody()).containsEntry("operationId", first.getBody().get("operationId"));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='lost-response-key'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_TASK WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(Long.class).single()).isZero();
    }

    @Test
    void crossTenantAndUnauthorisedCallersCannotCreateAnActionCommand() {
        ResponseEntity<Map> crossTenant = execute("dave", "0", "foreign-key");
        ResponseEntity<Map> noPermission = execute("carol", "0", "denied-key");

        assertThat(crossTenant.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(noPermission.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(Long.class).single()).isZero();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> execute(String user, String version, String key) {
        return client(user).post().uri("/cases/{caseId}/ad-hoc-actions/investigate", CASE_ID)
                .header("If-Match", "\"" + version + "\"").header("Idempotency-Key", key)
                .retrieve().toEntity(Map.class);
    }

    private static String contract() {
        return """
                {"key":"remote-ad-hoc","orchestrationMode":"BPMN","roles":["handler"],"fields":{},"forms":{},
                 "adHocActions":[{"id":"investigate","type":"TASK","roles":["handler"]}]}
                """;
    }

    private static String bpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                  <bpmn:process id="remote-ad-hoc" isExecutable="true"/>
                </bpmn:definitions>
                """;
    }
}
