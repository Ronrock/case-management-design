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
import java.util.Map;
import java.util.UUID;

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

    /** Deploys {@code widget-review} through the API and returns the deploy response body. */
    @SuppressWarnings("unchecked")
    Map<String, Object> deployDefinition() {
        ResponseEntity<Map> response = alice().post().uri("/case-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(definitionJson())
                .retrieve().toEntity(Map.class);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("Fixture deploy failed: " + response);
        }
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> createCase(String title) {
        return alice().post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", DEFINITION_KEY, "tenantId", TENANT,
                        "title", title, "priority", "HIGH",
                        "variables", Map.of("channel", "web")))
                .retrieve().toEntity(Map.class);
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
}
