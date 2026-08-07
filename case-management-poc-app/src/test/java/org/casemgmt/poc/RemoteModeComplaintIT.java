package org.casemgmt.poc;

import org.casemgmt.poc.support.PocOracleSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O2, remote-mode half: the complaint case runs end to end with THIS application's own case
 * processing routed over real HTTP to a SEPARATE, independent Operaton engine — not a fake, not
 * a mock, not the same JVM's local engine wearing a different hat.
 *
 * <p><b>The "remote engine" is a second, real instance of {@link PocApplication} itself</b>
 * (embedded mode, on its own random port), which is exactly the deployment this PoC is meant to
 * demonstrate (spec: "every DevOps team runs its own engine + case service" — {@code
 * openapi-specs.md}'s own description). A THIRD instance, {@code casemgmt.engine.mode=remote},
 * points its {@code casemgmt.engine.remote.base-url} at the second instance's {@code
 * /engine-rest} and drives the whole complaint flow purely over its own HTTP API — it never
 * touches the engine instance directly. Both share the one Oracle schema
 * ({@link PocOracleSupport}), which is what lets a single {@code PocBootstrap} run (whichever
 * instance starts first) seed users, groups and the complaint definition once for both — Operaton
 * and this schema both support more than one engine node against one database, which is the
 * production shape this mirrors, not a test-only trick.
 *
 * <p>Two independent proofs that this is genuinely asynchronous and genuinely remote, not a
 * same-JVM shortcut wearing a costume:
 * <ol>
 *   <li>{@link #theComplaintCasePathRunsEndToEndInRemoteModeAgainstARealSeparateEngine()} polls
 *   {@code engineSync} on the freshly-created task and asserts it starts {@code PENDING} (never
 *   {@code SYNCED} on creation, unlike embedded mode — see {@code OutboxEngineGateway}) and that
 *   {@code claim} is genuinely absent from {@code availableActions[]} until the starter's own
 *   {@code EngineCommandDispatcher} (Task 25's real scheduler, not a fake) drains the command and
 *   flips it — then, independently of any of THIS application's own tables, queries the engine
 *   instance's own {@link TaskService} directly and asserts a real Operaton task exists with the
 *   expected name and candidate groups, correlated by the {@code caseId} task variable {@link
 *   org.casemgmt.engine.embedded.EmbeddedEngineGateway} always sets;</li>
 *   <li>{@link #anUnreachableRemoteEngineLeavesTheTaskPermanentlyPendingRatherThanSilentlySynced()}
 *   points a remote-mode instance at a dead port instead and proves the SAME task never leaves
 *   {@code PENDING} and {@code claim} never appears — the mechanism-stripping half: if {@code
 *   engineSync} flipped to {@code SYNCED} regardless of whether anything is listening, the first
 *   assertion above would be proving nothing.</li>
 * </ol>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class RemoteModeComplaintIT {

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        for (int i = contexts.size() - 1; i >= 0; i--) {
            contexts.get(i).close();
        }
        contexts.clear();
    }

    @Test
    void theComplaintCasePathRunsEndToEndInRemoteModeAgainstARealSeparateEngine() throws Exception {
        ConfigurableApplicationContext engine = bootPocApplication(Map.of());
        int enginePort = portOf(engine);

        ConfigurableApplicationContext remote = bootPocApplication(Map.of(
                "casemgmt.engine.mode", "remote",
                "casemgmt.engine.remote.base-url", "http://localhost:" + enginePort + "/engine-rest",
                "casemgmt.engine.remote.username", "admin",
                "casemgmt.engine.remote.password", "admin",
                // Fast drain: the default 5s would make this test slow without proving anything
                // more than a longer wait would.
                "casemgmt.schedulers.engine-command-interval-ms", "200"));
        int remotePort = portOf(remote);

        RestClient http = httpClient(remotePort, "alice");

        Map<String, Object> created = (Map<String, Object>) http.post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "remote-mode-it-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Broken widget (remote mode)", "priority", "HIGH"))
                .retrieve().toEntity(Map.class).getBody();
        String caseId = (String) created.get("id");

        // The task exists immediately (CaseTaskService writes CM_TASK synchronously in every
        // mode — only the ENGINE side is deferred), but starts PENDING: OutboxEngineGateway
        // never talks to an engine at all, it only enqueues a command (see its own Javadoc).
        Map<String, Object> registerTask = findTask(http, caseId, "Register complaint");
        assertThat(registerTask.get("engineSync")).isEqualTo("PENDING");
        assertThat((List<Map<String, Object>>) registerTask.get("availableActions")).isEmpty();

        // Wait for the real scheduler (CaseManagementSchedulers.dispatchEngineCommands, Task 25 —
        // not a test double) to drain the command over real HTTP against the engine instance.
        Map<String, Object> synced = awaitEngineSync(http, caseId, "Register complaint");
        assertThat(synced.get("engineSync")).isEqualTo("SYNCED");
        assertThat((List<Map<String, Object>>) synced.get("availableActions"))
                .extracting(a -> a.get("action")).contains("claim");

        // Independent proof, off THIS application's own tables entirely: the engine instance's
        // own TaskService has a real task for this case.
        TaskService engineTasks = engine.getBean(TaskService.class);
        List<Task> onTheEngine = engineTasks.createTaskQuery()
                .taskVariableValueEquals("caseId", caseId).list();
        assertThat(onTheEngine).hasSize(1);
        assertThat(onTheEngine.get(0).getName()).isEqualTo("Register complaint");
        assertThat(engineTasks.getIdentityLinksForTask(onTheEngine.get(0).getId()))
                .extracting(l -> l.getGroupId())
                .contains("intake");

        // ---- drive the rest of the flow over remote-mode HTTP, same shape as CaseApiIT ----
        claimAndComplete(http, (String) synced.get("id"), Map.of("channel", "web", "summary", "broken"));
        assertThat(findMilestone(http, caseId, "acknowledged").get("achieved")).isEqualTo(true);

        Map<String, Object> assessTask = awaitEngineSync(http, caseId, "Assess complaint");
        claimAndComplete(http, (String) assessTask.get("id"), Map.of("outcome", "upheld"));

        Map<String, Object> decisionLetterItem = findPlanItem(http, caseId, "sendDecisionLetter");
        assertThat(decisionLetterItem.get("state")).isEqualTo("ACTIVE");
        completePlanItem(http, caseId, (String) decisionLetterItem.get("id"));
        assertThat(findMilestone(http, caseId, "decided").get("achieved")).isEqualTo(true);

        Map<String, Object> closeTask = awaitEngineSync(http, caseId, "Close complaint");
        claimAndComplete(http, (String) closeTask.get("id"), Map.of("outcome", "resolved"));

        Map<String, Object> beforeClose = (Map<String, Object>) http.get().uri("/case-api/v2/cases/{id}", caseId)
                .retrieve().toEntity(Map.class).getBody();
        assertThat((List<Map<String, Object>>) beforeClose.get("availableActions"))
                .extracting(a -> a.get("action")).contains("close");
        ResponseEntity<Map> closeResponse = http.post().uri("/case-api/v2/cases/{id}/close", caseId)
                .header("If-Match", "*").retrieve().toEntity(Map.class);
        assertThat(closeResponse.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> finalCase = (Map<String, Object>) http.get().uri("/case-api/v2/cases/{id}", caseId)
                .retrieve().toEntity(Map.class).getBody();
        assertThat(finalCase.get("state")).isEqualTo("CLOSED");
    }

    /**
     * Mechanism-stripping counterpart, kept as a permanent assertion rather than a one-off
     * manual check: points remote mode at a port nothing is listening on and proves the SAME
     * task never leaves {@code PENDING} — the {@code EngineCommandDispatcher} retries and
     * dead-letters, but nothing ever reports success back, so {@code claim} never appears. If
     * {@code engineSync} flipped to {@code SYNCED} regardless of connectivity, the previous
     * test's assertion on it would be worthless.
     */
    @Test
    void anUnreachableRemoteEngineLeavesTheTaskPermanentlyPendingRatherThanSilentlySynced() throws Exception {
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }

        ConfigurableApplicationContext remote = bootPocApplication(Map.of(
                "casemgmt.engine.mode", "remote",
                "casemgmt.engine.remote.base-url", "http://localhost:" + deadPort + "/engine-rest",
                "casemgmt.engine.remote.connect-timeout-ms", "500",
                "casemgmt.engine.remote.read-timeout-ms", "500",
                "casemgmt.schedulers.engine-command-interval-ms", "200"));
        int remotePort = portOf(remote);
        RestClient http = httpClient(remotePort, "alice");

        Map<String, Object> created = (Map<String, Object>) http.post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "remote-mode-dead-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Unreachable engine probe"))
                .retrieve().toEntity(Map.class).getBody();
        String caseId = (String) created.get("id");

        // Several dispatcher cycles' worth of waiting — long enough that, if the mechanism were
        // broken (e.g. the dispatcher silently marking commands SYNCED regardless of outcome),
        // it would have shown up by now.
        Thread.sleep(2000);

        Map<String, Object> task = findTask(http, caseId, "Register complaint");
        assertThat(task.get("engineSync")).isEqualTo("PENDING");
        assertThat((List<Map<String, Object>>) task.get("availableActions")).isEmpty();
    }

    // ---- shared plumbing ----

    /**
     * Overrides are passed as {@code --key=value} command-line arguments, not via {@code
     * SpringApplicationBuilder.properties(Map)} — that method registers them as Spring Boot's
     * lowest-precedence "default properties" source, which {@code application.yaml}'s own
     * {@code spring.datasource.url} (a real value, not a placeholder) always wins over. Found by
     * actually running this: every boot attempted a real TNS connection to {@code localhost:1521}
     * (the yaml's literal value) instead of the Testcontainers port, failing with ORA-12541.
     * Command-line arguments sit near the top of Spring's property-source precedence, which is
     * what actually lets these overrides win.
     */
    private ConfigurableApplicationContext bootPocApplication(Map<String, Object> overrides) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", PocOracleSupport.ORACLE.getJdbcUrl());
        properties.put("spring.datasource.username", PocOracleSupport.ORACLE.getUsername());
        properties.put("spring.datasource.password", PocOracleSupport.ORACLE.getPassword());
        properties.putAll(overrides);

        String[] args = properties.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PocApplication.class)
                .run(args);
        contexts.add(ctx);
        return ctx;
    }

    private int portOf(ConfigurableApplicationContext ctx) {
        return ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    }

    private RestClient httpClient(int port, String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(h -> h.setBasicAuth(user, user))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findTask(RestClient http, String caseId, String name) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) http.get()
                .uri("/case-api/v2/cases/{id}/tasks", caseId).retrieve().toEntity(List.class).getBody();
        return tasks.stream().filter(t -> name.equals(t.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No task named '" + name + "' on case " + caseId));
    }

    /** Polls until the named task's {@code engineSync} is {@code SYNCED}, bounded. */
    private Map<String, Object> awaitEngineSync(RestClient http, String caseId, String name) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Map<String, Object> task = findTask(http, caseId, name);
            if ("SYNCED".equals(task.get("engineSync"))) {
                return task;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Task '" + name + "' on case " + caseId
                + " never reached engineSync=SYNCED within the wait budget");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findMilestone(RestClient http, String caseId, String name) {
        List<Map<String, Object>> milestones = (List<Map<String, Object>>) http.get()
                .uri("/case-api/v2/cases/{id}/milestones", caseId).retrieve().toEntity(List.class).getBody();
        return milestones.stream().filter(m -> name.equals(m.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No milestone named '" + name + "' on case " + caseId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPlanItem(RestClient http, String caseId, String name) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) http.get()
                .uri("/case-api/v2/cases/{id}/plan-items", caseId).retrieve().toEntity(List.class).getBody();
        return items.stream().filter(i -> name.equals(i.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No plan item named '" + name + "' on case " + caseId));
    }

    private void claimAndComplete(RestClient http, String taskId, Map<String, Object> variables) {
        ResponseEntity<Map> claimed = http.post().uri("/case-api/v2/tasks/{id}/claim", taskId)
                .header("If-Match", "*").retrieve().toEntity(Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> completed = http.post().uri("/case-api/v2/tasks/{id}/complete", taskId)
                .header("If-Match", "*").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", variables))
                .retrieve().toEntity(Map.class);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
    }

    private void completePlanItem(RestClient http, String caseId, String itemId) {
        ResponseEntity<Map> r = http.post()
                .uri("/case-api/v2/cases/{caseId}/plan-items/{itemId}/complete", caseId, itemId)
                .header("If-Match", "*").retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }
}
