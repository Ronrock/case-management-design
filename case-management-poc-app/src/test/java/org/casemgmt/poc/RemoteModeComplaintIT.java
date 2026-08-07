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
 * <p><b>Correction (Fix round 1, review):</b> the "remote engine" is a second, real instance of
 * {@link PocApplication} (embedded mode, on its own random port) — but it runs its embedded
 * Operaton engine against the SAME Oracle datasource as the third, remote-mode instance ({@link
 * PocOracleSupport}), which is what lets a single {@code PocBootstrap} run (whichever instance
 * starts first) seed users, groups and the complaint definition once for both. That means this is
 * accurately described as <b>two Operaton engine nodes sharing one database</b>, not two fully
 * independent engines — a real, standard Operaton deployment shape (clustered engine nodes
 * against one schema), but not the stronger claim an earlier draft of this Javadoc made. The claim
 * that matters survives that correction intact: the remote-mode instance's ONLY gateway to engine
 * work is {@code OutboxEngineGateway} (an {@code @Primary} bean — see {@code
 * RemoteEngineAutoConfiguration}), which never calls a {@link TaskService} at all, only enqueues a
 * {@code CM_ENGINE_COMMAND} row; the only thing that can ever drain it is {@code
 * EngineCommandDispatcher}, registered ONLY by {@code RemoteEngineAutoConfiguration} (never by
 * {@code EmbeddedEngineAutoConfiguration}) and invoked only via {@code
 * CaseManagementSchedulers.dispatchEngineCommands}'s {@code ObjectProvider.ifAvailable} — which is
 * empty, and therefore a no-op, in an embedded-mode context. So even sharing a datasource, an
 * embedded-mode context structurally cannot drain the remote-mode instance's own outbox; the HTTP
 * round trip this test observes is the only path there is.
 *
 * <p>Three proofs that this is genuinely asynchronous and genuinely remote, not a same-JVM
 * shortcut wearing a costume:
 * <ol>
 *   <li>{@link #theComplaintCasePathRunsEndToEndInRemoteModeAgainstARealSeparateEngine()} polls
 *   {@code engineSync} on the freshly-created task and asserts it starts {@code PENDING} (never
 *   {@code SYNCED} on creation, unlike embedded mode — see {@code OutboxEngineGateway}) and that
 *   {@code claim} is genuinely absent from {@code availableActions[]} until the starter's own
 *   {@code EngineCommandDispatcher} (Task 25's real scheduler, not a fake) drains the command and
 *   flips it — then, independently of any of THIS application's own tables, queries the engine
 *   node's own {@link TaskService} directly and asserts a real Operaton task exists with the
 *   expected name and candidate groups, correlated by the {@code caseId} task variable {@link
 *   org.casemgmt.engine.embedded.EmbeddedEngineGateway} always sets. As of Fix round 1 (Important
 *   4), {@code /engine-rest} requires authentication, so this also exercises — and depends on —
 *   {@code RemoteEngineAutoConfiguration.engineRestClient}'s basic-auth credentials actually being
 *   correct;</li>
 *   <li>{@link #anUnreachableRemoteEngineLeavesTheTaskPendingAfterAFailedAttempt()} points a
 *   remote-mode instance at a dead port instead and proves the SAME task never leaves {@code
 *   PENDING} and {@code claim} never appears after one failed attempt — the mechanism-stripping
 *   half: if {@code engineSync} flipped to {@code SYNCED} regardless of whether anything is
 *   listening, the first test's assertion on it would be proving nothing;</li>
 *   <li>{@link #wrongRemoteEngineCredentialsLeaveTheTaskPendingRatherThanSynced()} points a
 *   remote-mode instance at the SAME real, reachable engine node as the first test, but with the
 *   wrong password — proving the credentials the first test sends are actually load-bearing (the
 *   401 {@code RestClientResponseException} {@link org.casemgmt.engine.remote.RemoteEngineGateway}
 *   wraps as {@link org.casemgmt.engine.EngineException} sends the command back through the same
 *   retry path a dead port does), not merely that the port answers.</li>
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
     * task never leaves {@code PENDING} after one failed attempt — {@code
     * EngineCommandDispatcher.drainOnce} catches the connection failure and calls {@code
     * markRetry}, so nothing ever reports success back and {@code claim} never appears. If {@code
     * engineSync} flipped to {@code SYNCED} regardless of connectivity, the first test's
     * assertion on it would be worthless.
     *
     * <p><b>Corrected name (Fix round 1, review Minor):</b> this does NOT prove permanence. {@code
     * EngineCommand.BACKOFF} starts at one minute and escalates to ten hours over five attempts
     * before {@code exhausted()} lets {@link org.casemgmt.engine.EngineCommandDispatcher} mark the
     * command {@code DEAD} and report {@code FAILED} — reaching that state needs over twelve
     * cumulative hours, far outside any sane test budget. What this test actually establishes,
     * and no more, is that the first attempt's failure does not get silently reported as success.
     */
    @Test
    void anUnreachableRemoteEngineLeavesTheTaskPendingAfterAFailedAttempt() throws Exception {
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
        // it would have shown up by now. NOT long enough to reach BACKOFF's 1-minute retry, which
        // is exactly the point: this proves the FIRST attempt's failure, not eventual dead-lettering.
        Thread.sleep(2000);

        Map<String, Object> task = findTask(http, caseId, "Register complaint");
        assertThat(task.get("engineSync")).isEqualTo("PENDING");
        assertThat((List<Map<String, Object>>) task.get("availableActions")).isEmpty();
    }

    /**
     * Fix round 1, Important 4: {@code /engine-rest} now requires authentication (it used to
     * {@code permitAll()}). Points a remote-mode instance at the SAME real, reachable engine node
     * the first test uses, but with a wrong password, and proves the task never leaves {@code
     * PENDING} — i.e. that the credentials {@code RemoteEngineAutoConfiguration.engineRestClient}
     * sends are actually checked and actually required, not merely present and ignored. Without
     * this test, the first test's basic-auth path was exercised but never PROVEN load-bearing —
     * exactly the Task 25 lesson about a harness that looks like it covers a condition but never
     * actually produces it.
     */
    @Test
    void wrongRemoteEngineCredentialsLeaveTheTaskPendingRatherThanSynced() throws Exception {
        ConfigurableApplicationContext engine = bootPocApplication(Map.of());
        int enginePort = portOf(engine);

        ConfigurableApplicationContext remote = bootPocApplication(Map.of(
                "casemgmt.engine.mode", "remote",
                "casemgmt.engine.remote.base-url", "http://localhost:" + enginePort + "/engine-rest",
                "casemgmt.engine.remote.username", "admin",
                "casemgmt.engine.remote.password", "definitely-the-wrong-password",
                "casemgmt.schedulers.engine-command-interval-ms", "200"));
        int remotePort = portOf(remote);
        RestClient http = httpClient(remotePort, "alice");

        Map<String, Object> created = (Map<String, Object>) http.post().uri("/case-api/v2/cases")
                .header("Idempotency-Key", "remote-mode-badauth-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Wrong credentials probe"))
                .retrieve().toEntity(Map.class).getBody();
        String caseId = (String) created.get("id");

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
