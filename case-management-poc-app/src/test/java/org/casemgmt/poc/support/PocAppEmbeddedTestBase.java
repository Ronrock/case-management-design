package org.casemgmt.poc.support;

import org.casemgmt.poc.PocApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Boots the real, unmodified {@link PocApplication} — {@code casemgmt.engine.mode=embedded},
 * the default from {@code application.yaml} — against the shared Testcontainers Oracle instance
 * ({@link PocOracleSupport}), with a real embedded Operaton engine and {@link
 * org.casemgmt.poc.PocBootstrap} genuinely running as an {@code ApplicationRunner} on startup:
 * real users/groups, the real {@code complaint} definition, the real {@code decision-letter}
 * process, the real Dutch business calendar and SLA policy.
 *
 * <p>Every subclass shares this exact {@code @SpringBootTest} configuration (same context class,
 * same dynamic properties) so Spring's test context cache boots the embedded application exactly
 * once for the whole module, not once per test class — the Operaton engine schema-update and the
 * {@code PocBootstrap} seeding are not cheap, and there is no reason to pay for them twice to
 * exercise two different sets of assertions against the same running app.
 */
@SpringBootTest(classes = PocApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PocAppEmbeddedTestBase {

    @DynamicPropertySource
    static void oracleAndSchedulers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PocOracleSupport.ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", PocOracleSupport.ORACLE::getUsername);
        registry.add("spring.datasource.password", PocOracleSupport.ORACLE::getPassword);
        // Embedded mode never reads the engine-command scheduler at all (createHumanTask etc.
        // are synchronous local calls — see EmbeddedEngineGateway), and nothing here depends on
        // the webhook or SLA sweepers firing. Slowed down (not disabled outright — the point is
        // to prove the starter's real schedulers are safe to run, not to hide them) to keep
        // background scheduled work from generating log noise against the shared connection
        // pool while these tests run.
        registry.add("casemgmt.schedulers.webhook-interval-ms", () -> "2000");
        registry.add("casemgmt.schedulers.engine-command-interval-ms", () -> "2000");
        registry.add("casemgmt.schedulers.sla-sweep-interval-ms", () -> "3600000");
        registry.add("casemgmt.webhooks.secret-encryption-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @LocalServerPort
    protected int port;

    /** A client that never throws on a non-2xx response — every status assertion in these
     * tests is made against the real status line, never against an exception message. */
    protected RestClient client(String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(h -> h.setBasicAuth(user, user))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }
}
