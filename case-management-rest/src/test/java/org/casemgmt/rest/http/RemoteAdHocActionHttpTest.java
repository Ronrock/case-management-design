package org.casemgmt.rest.http;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.CommandDispatchOutcome;
import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.observation.CommandConfirmationLifecycleReporter;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
    @Autowired LinkedProcessRepository linkedProcesses;
    @Autowired EventPublisher events;

    @BeforeEach
    void seedPublishedAction() {
        var orchestration = releases.publish(KEY, TENANT, ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", bpmn().getBytes(StandardCharsets.UTF_8), "alice");
        var contract = releases.publish(KEY, TENANT, ReleaseKind.CONTRACT, "application/json",
                contract(orchestration.id()).getBytes(StandardCharsets.UTF_8), "alice");
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

    @Test
    void processActionReturnsAcceptedLocationThenConfirmsTheExactPendingLinkAndEmitsLifecycleEvents() {
        ResponseEntity<Map> response = execute("alice", "0", "process-confirm-key", "launch");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/case-api/v2/operations/" + response.getBody().get("operationId"));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId AND ENGINE_SYNC_='PENDING'")
                .param("caseId", CASE_ID).query(Long.class).single()).isEqualTo(1L);

        drain(command -> confirmed(command, "remote-process-1", CommandDispatchOutcome.RemoteState.PROCESS_STARTED));

        assertThat(jdbc().sql("SELECT ENGINE_SYNC_ FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(String.class).single()).isEqualTo(CaseTask.EngineSync.SYNCED.name());
        assertThat(jdbc().sql("SELECT PROC_INST_ID_ FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(String.class).single()).isEqualTo("remote-process-1");
        assertThat(eventTypes()).contains(event("case.adhoc.requested"), event("case.adhoc.confirmed"));
    }

    @Test
    void definitiveProcessRejectionFailsTheOperationAndPendingLinkWithoutAnEngineIdentity() {
        ResponseEntity<Map> response = execute("alice", "0", "process-reject-key", "launch");

        drain(command -> CommandDispatchOutcome.http(400,
                CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED, null, null));

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_ENGINE_COMMAND WHERE OPERATION_ID_=:operation")
                .param("operation", response.getBody().get("operationId")).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc().sql("SELECT ENGINE_SYNC_ FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(String.class).single()).isEqualTo(CaseTask.EngineSync.FAILED.name());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId AND PROC_INST_ID_ IS NOT NULL")
                .param("caseId", CASE_ID).query(Long.class).single()).isZero();
        assertThat(eventTypes()).contains(event("case.adhoc.requested"), event("case.adhoc.failed"));
    }

    @Test
    void sameKeyProcessReplayCreatesOneCommandAndOnePendingLink() {
        ResponseEntity<Map> first = execute("alice", "0", "process-replay-key", "launch");
        ResponseEntity<Map> replay = execute("alice", "0", "process-replay-key", "launch");

        assertThat(replay.getBody()).containsEntry("operationId", first.getBody().get("operationId"));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='process-replay-key'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void messageActionConfirmsWithoutInventingTaskOrLinkedProcessProjections() {
        ResponseEntity<Map> response = execute("alice", "0", "message-confirm-key", "notify");
        drain(command -> confirmed(command, "message-correlation-1", CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED));

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_ENGINE_COMMAND WHERE OPERATION_ID_=:operation")
                .param("operation", response.getBody().get("operationId")).query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_TASK WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(Long.class).single()).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_LINKED_PROCESS WHERE CASE_ID_=:caseId")
                .param("caseId", CASE_ID).query(Long.class).single()).isZero();
        assertThat(eventTypes()).contains(event("case.adhoc.requested"), event("case.adhoc.confirmed"));
    }

    @Test
    void messageRejectionEmitsFailureAndReplayAndTenantChecksRemainSafe() {
        ResponseEntity<Map> first = execute("alice", "0", "message-replay-key", "notify");
        ResponseEntity<Map> replay = execute("alice", "0", "message-replay-key", "notify");
        ResponseEntity<Map> foreign = execute("dave", "0", "message-foreign-key", "notify");
        assertThat(replay.getBody()).containsEntry("operationId", first.getBody().get("operationId"));
        assertThat(foreign.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE IDEMPOTENCY_KEY_='message-replay-key'")
                .query(Long.class).single()).isEqualTo(1L);

        drain(command -> CommandDispatchOutcome.http(400,
                CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED, null, null));

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_ENGINE_COMMAND WHERE OPERATION_ID_=:operation")
                .param("operation", first.getBody().get("operationId")).query(String.class).single()).isEqualTo("FAILED");
        assertThat(eventTypes()).contains(event("case.adhoc.requested"), event("case.adhoc.failed"));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> execute(String user, String version, String key) {
        return execute(user, version, key, "launch");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> execute(String user, String version, String key, String action) {
        return client(user).post().uri("/cases/{caseId}/ad-hoc-actions/{action}", CASE_ID, action)
                .header("If-Match", "\"" + version + "\"").header("Idempotency-Key", key)
                .retrieve().toEntity(Map.class);
    }

    private void drain(org.casemgmt.engine.EngineCommandTransport transport) {
        CommandConfirmationLifecycleReporter lifecycle = new CommandConfirmationLifecycleReporter(
                cases, linkedProcesses, observation -> new org.casemgmt.observation.ApplyResult(
                        observation.observationId(), org.casemgmt.observation.ApplyStatus.APPLIED, 0L,
                        java.util.List.of()));
        // The HTTP fixture deliberately wires its request-side repository through JdbcClient;
        // draining production commands needs the DataSource constructor to own its lease
        // transaction. Both repositories point at this Testcontainers Oracle schema.
        new EngineCommandDispatcher(new EngineCommandRepository(dataSource()), transport, "ad-hoc-http-test", Clock.systemUTC(),
                Duration.ofSeconds(30), events, lifecycle::confirmed).drainOnce();
    }

    private static CommandDispatchOutcome confirmed(
            org.casemgmt.engine.ProductionEngineCommandStore.StoredCommand command,
            String remoteIdentity, CommandDispatchOutcome.RemoteState state) {
        var context = command.state().command();
        return CommandDispatchOutcome.http(202, CommandDispatchOutcome.Acceptance.ACCEPTED, null,
                new CommandDispatchOutcome.ConfirmationEvidence(context.tenantId(), context.operationId(),
                        context.commandId(), context.commandType(), context.expectedTargetIdentity(),
                        remoteIdentity, state, CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE,
                        "http:ad-hoc"));
    }

    private java.util.List<String> eventTypes() {
        return jdbc().sql("SELECT TYPE_ FROM CM_EVENT WHERE SUBJECT_=:caseId ORDER BY SEQ_")
                .param("caseId", CASE_ID).query(String.class).list();
    }

    private static String event(String suffix) {
        return CaseApiTestConfig.EVENT_TYPE_PREFIX + "." + suffix;
    }

    private static String contract(String releaseId) {
        return """
                {"key":"remote-ad-hoc","orchestrationMode":"BPMN","roles":["handler"],"fields":{},"forms":{},
                 "adHocActions":[
                   {"id":"launch","type":"PROCESS","roles":["handler"],"processDefinitionKey":"remote-ad-hoc","orchestrationReleaseId":"%s"},
                   {"id":"notify","type":"MESSAGE","roles":["handler"],"messageName":"notify"}
                 ]}
                """.formatted(releaseId);
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
