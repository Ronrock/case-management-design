package org.casemgmt.rest.http;

import org.casemgmt.OracleTestBase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Shared plumbing for the HTTP-level controller tests: a real servlet container on a random
 * port, a real Oracle schema (inherited from {@link OracleTestBase}, including its automatic
 * per-test wipe) and a {@link RestClient} per user.
 *
 * <p>These tests go over the wire on purpose. Task 22's error mapping was fully covered by
 * direct calls into {@code ProblemDetailHandler} but never once by an HTTP response, leaving
 * the {@code application/problem+json} content type and the serialisation of its extension
 * properties ({@code code}, {@code violations}, {@code availableActions}) unverified — carried
 * finding C3. Nothing below asserts on a handler's return value; every assertion is on a status
 * line, a header or a parsed response body.
 */
@SpringBootTest(classes = CaseApiTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class CaseApiHttpTestBase extends OracleTestBase {

    /**
     * The definition every test deploys. Deliberately {@code widget-review} from
     * case-management-core's own fixtures: it is case-type agnostic (no module below
     * case-management-poc-app may name a real case type), it declares a HUMAN_TASK with a
     * {@code formKey} and a {@code candidateGroups} of {@code reviewers}, and its
     * {@code reviewForm} schema makes {@code outcome} required — which is what produces a
     * root-pointer form violation to assert over the wire.
     */
    static final String DEFINITION_KEY = "widget-review";
    static final String TENANT = "t1";

    /**
     * {@code PATCH /cases/{id}} declares {@code consumes = application/merge-patch+json} per
     * {@code openapi-specs.md} (fix round 1, review finding I6), so every patch in these tests
     * sends that media type. Sending {@code application/json} now gets a 415 from Spring's
     * content negotiation — which is itself asserted once, in CaseApiErrorContractTest, so this
     * constant cannot quietly stop matching the controller.
     */
    static final MediaType MERGE_PATCH = MediaType.valueOf("application/merge-patch+json");

    @LocalServerPort
    int port;

    /**
     * Bridges {@link OracleTestBase}'s protected, shared connection pool to
     * {@link CaseApiTestConfig}, which is not a subclass and therefore cannot reach it.
     */
    static DataSource sharedDataSource() {
        return dataSource();
    }

    /**
     * A client that never throws on a non-2xx response. Every status assertion in these tests
     * has to be made against the actual status line — a client that turns a 409 into an
     * exception whose message merely contains "409" would let a test pass on a status produced
     * by something other than the code under test.
     */
    RestClient client(String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultHeaders(h -> h.setBasicAuth(user, user))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    RestClient alice() {
        return client("alice");
    }

    /** Publishes the BPMN-backed {@code widget-review} bundle through the production API. */
    @SuppressWarnings("unchecked")
    Map<String, Object> deployDefinition() {
        ResponseEntity<Map> response = alice().post().uri("/case-definitions")
                .contentType(MediaType.valueOf("application/zip"))
                .body(definitionArchive())
                .retrieve().toEntity(Map.class);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("Fixture deploy failed: " + response);
        }
        return response.getBody();
    }

    /** {@code GET /cases} returns the spec's Page envelope; this pulls out {@code items}. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(ResponseEntity<Map> pageResponse) {
        return (List<Map<String, Object>>) pageResponse.getBody().get("items");
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> createCase(String title) {
        ResponseEntity<Map> response = alice().post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT,
                        "title", title, "priority", "HIGH",
                        "variables", Map.of("channel", "web")))
                .retrieve().toEntity(Map.class);
        if (response.getStatusCode().value() == 201) {
            projectObservedFixtureActivities((String) response.getBody().get("id"));
        }
        return response;
    }

    /** Deploys the definition and creates one case, returning the created case body. */
    @SuppressWarnings("unchecked")
    Map<String, Object> deployAndCreateCase() {
        deployDefinition();
        ResponseEntity<Map> created = createCase("A case");
        if (created.getStatusCode().value() != 201) {
            throw new IllegalStateException("Fixture case creation failed: " + created);
        }
        return created.getBody();
    }

    static String definitionJson() {
        try (InputStream in = CaseApiHttpTestBase.class
                .getResourceAsStream("/definitions/test-definition.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing /definitions/test-definition.json fixture");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the case definition fixture", e);
        }
    }

    static byte[] definitionArchive() {
        return archive(definitionJson());
    }

    static byte[] archive(String contractJson) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                write(zip, "processes/widget-review.bpmn", """
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                          <bpmn:process id="widget-review" isExecutable="true">
                            <bpmn:startEvent id="start"/>
                            <bpmn:sequenceFlow id="to-review" sourceRef="start" targetRef="review"/>
                            <bpmn:userTask id="review" name="Review"
                                operaton:formKey="reviewForm"
                                operaton:candidateGroups="reviewers"/>
                            <bpmn:sequenceFlow id="to-end" sourceRef="review" targetRef="end"/>
                            <bpmn:endEvent id="end"/>
                          </bpmn:process>
                        </bpmn:definitions>
                        """);
                write(zip, "contract.json", contractJson);
                write(zip, "presentation.json", "{\"version\":\"1.0\",\"sections\":[]}");
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the BPMN test bundle", e);
        }
    }

    private static void write(ZipOutputStream zip, String path, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Simulates the engine-observation bridge that is outside this REST slice. These rows are
     * projections of the BPMN user task and milestone, never inputs to a local state machine.
     */
    void projectObservedFixtureActivities(String caseId) {
        String processInstanceId = jdbc().sql("""
                SELECT PROC_INST_ID_ FROM CM_LINKED_PROCESS
                WHERE CASE_ID_=:caseId AND IS_CASE_ROOT_=1
                """).param("caseId", caseId).query(String.class).single();
        OffsetDateTime now = OffsetDateTime.now();
        String taskPlanItemId = "pi-" + UUID.randomUUID();
        String engineTaskId = "engine-task-" + UUID.randomUUID();
        String taskActivityInstanceId = "activity-" + UUID.randomUUID();
        jdbc().sql("""
                INSERT INTO CM_PLAN_ITEM
                  (ID_,CASE_ID_,PI_DEF_ID_,TYPE_,NAME_,STATE_,AD_HOC_,REPETITION_NO_,
                   CAMUNDA_TASK_ID_,PROC_INST_ID_,VERSION_,CREATED_AT_,UPDATED_AT_,
                   ENGINE_ACTIVITY_ID_,PROJECTION_STATUS_,LAST_ENGINE_UPDATE_AT_,LAST_PROJECTED_AT_)
                VALUES (:id,:caseId,'review','HUMAN_TASK','Review','ACTIVE',0,1,
                        :taskId,:processId,0,:now,:now,:activityInstanceId,'CURRENT',:now,:now)
                """).param("id", taskPlanItemId).param("caseId", caseId)
                .param("taskId", engineTaskId).param("processId", processInstanceId)
                .param("activityInstanceId", taskActivityInstanceId)
                .param("now", now).update();
        jdbc().sql("""
                INSERT INTO CM_TASK
                  (ID_,CASE_ID_,PLAN_ITEM_ID_,CAMUNDA_TASK_ID_,NAME_,STATE_,CAND_GROUPS_JSON_,
                   FORM_KEY_,PRIORITY_,ENGINE_SYNC_,VERSION_,CREATED_AT_,UPDATED_AT_,
                   PROJECTION_STATUS_,LAST_ENGINE_UPDATE_AT_,LAST_PROJECTED_AT_,PROC_INST_ID_)
                VALUES (:id,:caseId,:planItemId,:engineTaskId,'Review','OPEN','[\"reviewers\"]',
                        'reviewForm',50,'SYNCED',0,:now,:now,'CURRENT',:now,:now,:processId)
                """).param("id", "task-" + UUID.randomUUID()).param("caseId", caseId)
                .param("planItemId", taskPlanItemId).param("engineTaskId", engineTaskId)
                .param("processId", processInstanceId).param("now", now).update();
        jdbc().sql("""
                INSERT INTO CM_PLAN_ITEM
                  (ID_,CASE_ID_,PI_DEF_ID_,TYPE_,NAME_,STATE_,AD_HOC_,REPETITION_NO_,
                   PROC_INST_ID_,VERSION_,CREATED_AT_,UPDATED_AT_,ENGINE_ACTIVITY_ID_,
                   PROJECTION_STATUS_,LAST_ENGINE_UPDATE_AT_,LAST_PROJECTED_AT_)
                VALUES (:id,:caseId,'reviewed','MILESTONE','Reviewed','AVAILABLE',0,1,
                        :processId,0,:now,:now,:activityInstanceId,'CURRENT',:now,:now)
                """).param("id", "pi-" + UUID.randomUUID()).param("caseId", caseId)
                .param("processId", processInstanceId)
                .param("activityInstanceId", "activity-" + UUID.randomUUID())
                .param("now", now).update();
    }
}
