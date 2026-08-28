package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.OutboxEngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineEventBridge;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.event.WebhookSecretStore;
import org.casemgmt.observation.EngineObservationHandler;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rules.CriterionEvaluator;
import org.casemgmt.rules.JuelCriterionEvaluator;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.FormValidator;
import org.casemgmt.service.WebhookService;
import org.casemgmt.sla.SlaSweeper;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
// Spring Boot 4 relocated DataSourceAutoConfiguration out of spring-boot-autoconfigure's
// org.springframework.boot.autoconfigure.jdbc package (where the brief's own import pointed,
// and where it still lives in Boot 3) into a new dedicated spring-boot-jdbc artifact under
// org.springframework.boot.jdbc.autoconfigure — confirmed by inspecting spring-boot-jdbc-4.0.7.jar
// directly, since "package does not exist" at the old location gave no other clue.
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AutoConfigurationTest {

    /**
     * Deviation from the brief: {@code EmbeddedEngineAutoConfiguration} and (fix round 1,
     * Important 1) {@code CaseManagementSchedulers} are added to the registered
     * auto-configurations. The brief's own runner omitted both. Omitting
     * {@code EmbeddedEngineAutoConfiguration} left
     * {@link #embeddedModeWithoutAnEngineOnTheClasspathFailsWithAClearMessage} unable to pass —
     * with only {@code CaseManagementAutoConfiguration} and {@code RemoteEngineAutoConfiguration}
     * registered, embedded mode has no {@code EngineGateway} bean-producing configuration at all,
     * so startup would fail with a generic {@code NoSuchBeanDefinitionException} for
     * {@code EngineGateway} instead of the friendly "operaton-bpm-spring-boot-starter" message
     * {@code EmbeddedEngineAutoConfiguration.embeddedEngineRequirementCheck} produces — confirmed
     * by actually running the test with the omission restored (see the Task 25 report's
     * mechanism-stripping evidence). Omitting {@code CaseManagementSchedulers} let
     * {@link #disabledPropertyLeavesTheContextClean} pass without ever exercising it,
     * hiding a real Important-1 bug (fix round 1): {@code CaseManagementSchedulers} had no
     * {@code casemgmt.enabled} guard of its own, so {@code casemgmt.enabled=false} left it active
     * and demanding {@code WebhookDispatcher}/{@code SlaSweeper} beans that no longer existed —
     * the exact opposite of {@code CaseManagementProperties}' documented "leaves a plain Operaton
     * app completely untouched" contract for that property.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    EmbeddedEngineAutoConfiguration.class, CaseManagementAutoConfiguration.class,
                    RemoteEngineAutoConfiguration.class, CaseManagementSchedulers.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:autoconfig;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "casemgmt.webhooks.secret-encryption-key="
                            + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    /**
     * Fix round 1, Important 1: strengthened beyond a bean-absence check to
     * {@code hasNotFailed()} as well. A bean-absence assertion alone cannot distinguish "the
     * context came up clean, minus this one bean" from "the context blew up entirely" — both
     * leave {@code CaseService} absent. The whole point of {@code casemgmt.enabled=false} is the
     * FIRST of those, per {@code CaseManagementProperties}' own Javadoc.
     */
    @Test
    void disabledPropertyLeavesTheContextClean() {
        runner.withPropertyValues("casemgmt.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CaseService.class);
                });
    }

    /**
     * Fix round 1, Important 1: the other half of the same gap, on {@code RemoteEngineAutoConfiguration}
     * specifically. {@code casemgmt.engine.mode=remote} is a plausible leftover in a config file
     * that also sets {@code casemgmt.enabled=false} (e.g. temporarily disabling the module without
     * touching its engine-mode setting) — before the fix, that combination activated
     * {@code RemoteEngineAutoConfiguration} anyway (it only checked {@code casemgmt.engine.mode},
     * never {@code casemgmt.enabled}), and its {@code engineRestClient} bean demanded the
     * {@code CaseManagementProperties} bean that only the (disabled) {@code CaseManagementAutoConfiguration}
     * registers.
     */
    @Test
    void disabledPropertyLeavesTheContextCleanEvenWithRemoteModeConfigured() {
        runner.withPropertyValues("casemgmt.enabled=false", "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CaseService.class);
                });
    }

    /**
     * Fix round 1, Important 2, part 2: {@code EmbeddedEngineAutoConfiguration} is gated only on
     * {@code casemgmt.enabled}, not {@code casemgmt.engine.mode} — so it still activates in remote
     * mode, and a correctly-configured remote-mode consumer must end up with a context that has
     * not failed.
     *
     * <p><b>What this test does NOT prove (Task 25 review, corrected in Task 27):</b> it does not
     * protect the introspection property that motivated the nested-{@code @ConditionalOnClass}
     * restructuring in {@link EmbeddedEngineAutoConfiguration}. It passed against the UNNESTED
     * shape too. {@code FilteredClassLoader} owns zero URLs (it calls
     * {@code super(new URL[0], parent)}), so it never DEFINES a class; every filtered name is
     * merely hidden from {@code loadClass}, while {@code getDeclaredMethods()} resolves parameter
     * types through the class's DEFINING loader — here the application loader, which sees
     * {@code TaskService} on this module's own test classpath. No in-process harness can produce
     * the absence this test appears to simulate. Kept because "remote mode with this
     * auto-configuration active still yields a working context" is worth pinning on its own terms;
     * the restructuring's guard is the code shape itself, documented there.
     */
    @Test
    void embeddedAutoConfigurationSurvivesEngineAbsentFromClasspathInRemoteMode() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(ProcessEngine.class, TaskService.class,
                        RuntimeService.class, EmbeddedEngineGateway.class))
                .withConfiguration(AutoConfigurations.of(EmbeddedEngineAutoConfiguration.class))
                .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine.mode=remote")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * {@code casemgmt.schedulers.enabled=false} here (and on
     * {@link #webhookServiceSubscribeOverrideStaysTransactional}, the other test where the whole
     * bean set constructs successfully): this test asserts on bean presence, not scheduling
     * behaviour, and a fully-constructed {@code CaseManagementSchedulers} means real
     * {@code @Scheduled} tasks start running against this context's short-lived, auto-closed H2
     * datasource — {@code fixedDelayString} with no explicit initial-delay fires its first
     * execution immediately, which can race the context's close (triggered as soon as this
     * method's lambda returns) and log a spurious "HikariDataSource has been closed" error from a
     * background thread. Turning scheduling off keeps this test's own output clean without
     * weakening what it actually checks.
     */
    @Test
    void remoteModeRegistersTheOutboxGateway() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(CaseService.class);
                    assertThat(context.getBean(EngineGateway.class))
                            .isInstanceOf(OutboxEngineGateway.class);
                });
    }

    @Test
    void embeddedModeRegistersTheSpringEventProjectionBridgeAfterItsPort() {
        runner.withBean(TaskService.class, () -> mock(TaskService.class))
                .withBean(RuntimeService.class, () -> mock(RuntimeService.class))
                .withBean(RepositoryService.class, () -> mock(RepositoryService.class))
                .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=embedded",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddedEngineEventBridge.class);
                    assertThat(context).hasSingleBean(EngineObservationHandler.class);
                });
    }

    @Test
    void schedulerAutoConfigurationConstructsTheSchedulerBeanWhenEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CaseManagementSchedulers.class))
                .withBean(CaseManagementProperties.class, CaseManagementProperties::new)
                .withBean(WebhookDispatcher.class, () -> mock(WebhookDispatcher.class))
                .withBean(SlaSweeper.class, () -> mock(SlaSweeper.class))
                .withBean(IdempotencyRepository.class, () -> mock(IdempotencyRepository.class))
                .withPropertyValues(
                        "casemgmt.enabled=true",
                        "casemgmt.schedulers.enabled=true",
                        "casemgmt.schedulers.webhook-interval-ms=3600000",
                        "casemgmt.schedulers.engine-command-interval-ms=3600000",
                        "casemgmt.schedulers.sla-sweep-interval-ms=3600000",
                        "casemgmt.schedulers.idempotency-purge-interval-ms=3600000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CaseManagementSchedulers.class);
                });
    }

    @Test
    void remoteEngineRestClientSupportsBearerTokenProvider() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            runner.withUserConfiguration(RemoteBearerConfiguration.class)
                    .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                            "casemgmt.engine.mode=remote",
                            "casemgmt.engine.remote.base-url=http://localhost:"
                                    + server.getAddress().getPort() + "/engine-rest",
                            "casemgmt.engine.remote.auth-mode=bearer",
                            "casemgmt.events.type-prefix=org.example.cm",
                            "casemgmt.schedulers.enabled=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.getBean("engineRestClient", RestClient.class)
                                .get()
                                .uri("/task")
                                .retrieve()
                                .toBodilessEntity();
                        assertThat(authorization).hasValue("Bearer engine-token");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bearerRemoteEngineAuthRequiresATokenSource() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.engine.remote.auth-mode=bearer",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("RemoteEngineBearerTokenProvider"));
    }

    @Test
    void remoteEngineTimeoutsMustFitTheClientFactoryIntegerBoundary() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.engine.remote.connect-timeout-ms=2147483648",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("connect-timeout-ms")
                        .hasMessageContaining("32-bit"));
    }

    /**
     * Deviation from the brief: adds {@code .withClassLoader(new FilteredClassLoader(...))}.
     * Without it this test cannot exercise what its name promises. This module's own
     * {@code EmbeddedEngineAutoConfiguration.java} imports {@code org.operaton.bpm.engine.*} and
     * {@code org.casemgmt.engine.embedded.EmbeddedEngineGateway} directly, so
     * {@code case-management-engine-embedded} (and, transitively,
     * {@code operaton-bpm-spring-boot-starter}) is on THIS module's own compile/test classpath
     * unconditionally — {@code <optional>true</optional>} in the starter's pom only suppresses
     * transitivity to downstream consumers, it does not remove the jar from this module's own
     * build. {@code ProcessEngine} is therefore always resolvable here unless explicitly hidden,
     * which is exactly what {@link FilteredClassLoader} is for: it simulates the classpath of a
     * downstream consumer who added this starter but not the Operaton engine.
     */
    @Test
    void embeddedModeWithoutAnEngineOnTheClasspathFailsWithAClearMessage() {
        runner.withClassLoader(new FilteredClassLoader(ProcessEngine.class, TaskService.class,
                        RuntimeService.class, EmbeddedEngineGateway.class))
                .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=embedded",
                        "casemgmt.events.type-prefix=org.example.cm")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("operaton-bpm-spring-boot-starter"));
    }

    /**
     * Final whole-branch review, Important 3: {@code CaseManagementAutoConfiguration} declared
     * ~40 beans and not one carried {@code @ConditionalOnMissingBean}, while
     * {@code CallerResolver}'s own Javadoc promised "a consumer can substitute its own identity
     * mapping without excluding a component scan". As wired they could not — bean-definition
     * overriding is off by default in Boot 4, so a consumer declaring their own bean got
     * {@code BeanDefinitionOverrideException} at startup, and the same held for
     * {@code ActionPolicy}, {@code CriterionEvaluator}, {@code FormValidator} and
     * {@code EventPublisher}.
     *
     * <p>Asserts both halves for each: the context comes up at all (the override exception is a
     * startup failure, so {@code hasNotFailed} is the load-bearing half), and the bean the
     * context actually hands out is the consumer's INSTANCE, not the starter's. Identity, not
     * type: every substitute below is a subclass, so a type assertion alone would pass against
     * the starter's own bean and prove nothing.
     */
    @Test
    void aConsumerCanSubstituteEveryDocumentedExtensionPoint() {
        runner.withUserConfiguration(SubstitutingConfiguration.class)
                .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> {
                    assertThat(context).as("a consumer's own beans must not collide with the "
                            + "starter's — bean-definition overriding is off in Boot 4")
                            .hasNotFailed();

                    assertThat(context.getBean(CallerResolver.class))
                            .isSameAs(SubstitutingConfiguration.CALLER_RESOLVER);
                    assertThat(context.getBean(ActionPolicy.class))
                            .isSameAs(SubstitutingConfiguration.ACTION_POLICY);
                    assertThat(context.getBean(CriterionEvaluator.class))
                            .isSameAs(SubstitutingConfiguration.CRITERION_EVALUATOR);
                    assertThat(context.getBean(FormValidator.class))
                            .isSameAs(SubstitutingConfiguration.FORM_VALIDATOR);
                    assertThat(context.getBean(EventPublisher.class))
                            .isSameAs(SubstitutingConfiguration.EVENT_PUBLISHER);

                    // The isSameAs assertions above are what carry this test; this last one adds
                    // only that the rest of the bean graph still constructs around the
                    // substitutes rather than failing to resolve a dependency. It does NOT show
                    // the substitutes are wired THROUGH to their consumers, which an earlier
                    // version of this comment claimed — it shows CaseService exists. (That
                    // conclusion does happen to hold, because every injection point here is
                    // by type with exactly one candidate, but this assertion is not the evidence
                    // for it, and a comment asserting more than its assertion checks is the
                    // vacuous-mechanism shape this project keeps finding.)
                    assertThat(context).hasSingleBean(CaseService.class);
                });
    }

    /**
     * Negative control for the test above. Without a substitute in the context, the starter's
     * own defaults must still be registered — otherwise "the consumer's bean won" is satisfiable
     * by the starter simply not declaring these beans at all, which every assertion above would
     * happily accept while the context silently lost its defaults.
     */
    @Test
    void withoutASubstituteTheStarterStillProvidesEveryExtensionPoint() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(CallerResolver.class);
                    assertThat(context).hasSingleBean(ActionPolicy.class);
                    assertThat(context).hasSingleBean(FormValidator.class);
                    assertThat(context).hasSingleBean(EventPublisher.class);
                    assertThat(context.getBean(CriterionEvaluator.class))
                            .isInstanceOf(JuelCriterionEvaluator.class);
                });
    }

    @Test
    void caseManagementAutoConfigurationDelegatesBeanWiringToFocusedConfigurations() {
        assertThat(Arrays.stream(CaseManagementAutoConfiguration.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .toList())
                .as("the top-level auto-configuration should only compose focused wiring classes")
                .isEmpty();

        assertThat(CaseManagementAutoConfiguration.class.getAnnotation(Import.class).value())
                .contains(CaseManagementRepositoryConfiguration.class,
                        CaseManagementPolicyConfiguration.class,
                        CaseManagementServiceConfiguration.class,
                        CaseManagementWorkerPermissionsConfiguration.class,
                        CaseManagementSearchConfiguration.class,
                        CaseManagementWebhookConfiguration.class,
                        CaseManagementSlaConfiguration.class);
    }

    /**
     * A consumer's own configuration. Each bean is a distinct instance held in a static field so
     * the assertions above can check identity rather than type — the substitutes are subclasses,
     * and a type check would not distinguish them from the starter's own beans.
     */
    @Configuration(proxyBeanMethods = false)
    static class SubstitutingConfiguration {

        static final CallerResolver CALLER_RESOLVER = new CallerResolver(null) {};
        static final ActionPolicy ACTION_POLICY = new ActionPolicy() {};
        static final CriterionEvaluator CRITERION_EVALUATOR = (expression, context) -> true;
        static final FormValidator FORM_VALIDATOR = new FormValidator() {};
        static final EventPublisher EVENT_PUBLISHER =
                new EventPublisher(null, null, null, "org.example.consumer", "eng-consumer") {};

        @Bean CallerResolver callerResolver() { return CALLER_RESOLVER; }
        @Bean ActionPolicy actionPolicy() { return ACTION_POLICY; }
        @Bean CriterionEvaluator criterionEvaluator() { return CRITERION_EVALUATOR; }
        @Bean FormValidator formValidator() { return FORM_VALIDATOR; }
        @Bean EventPublisher eventPublisher() { return EVENT_PUBLISHER; }
    }

    @Configuration(proxyBeanMethods = false)
    static class RemoteBearerConfiguration {
        @Bean
        RemoteEngineBearerTokenProvider remoteEngineBearerTokenProvider() {
            return () -> "engine-token";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomWebhookSecretStoreConfiguration {
        static final WebhookSecretStore STORE = mock(WebhookSecretStore.class);

        @Bean
        WebhookSecretStore webhookSecretStore() {
            return STORE;
        }
    }

    @Test
    void customWebhookSecretStoreDoesNotRequireTheDatabaseEncryptionKey() {
        runner.withUserConfiguration(CustomWebhookSecretStoreConfiguration.class)
                .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.webhooks.secret-encryption-key=",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WebhookSecretStore.class))
                            .isSameAs(CustomWebhookSecretStoreConfiguration.STORE);
                });
    }

    @Test
    void defaultWebhookSecretStoreFailsClearlyWithoutAnEncryptionKey() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.webhooks.secret-encryption-key=",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("secret-encryption-key"));
    }

    @Test
    void missingEventTypePrefixFailsStartup() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("type-prefix"));
    }

    /**
     * Not one of the brief's four test cases. {@code CaseManagementWebhookConfiguration.webhookService}
     * overrides {@code WebhookService.subscribe} in an anonymous subclass to capture the plaintext
     * secret (K3), which looked like a K3-shaped risk on inspection: Java annotations are not
     * normally inherited across an override, so re-declaring {@code @Transactional} there (which
     * the production code does) looked load-bearing. Verified with the negative control below that
     * it is actually NOT, in the sense that the ATTRIBUTE still resolves without it —
     * {@code AnnotationTransactionAttributeSource} resolves {@code @Transactional} by walking up
     * the class hierarchy for a same-signature method, so {@code WebhookService.subscribe}'s own
     * annotation is found either way (confirmed by stripping the override's explicit annotation
     * and re-running: still passed — see the Task 25 report). The explicit re-declaration stays in
     * the production code as self-documenting defense in depth.
     *
     * <p>Fix round 1, Important 3: the resolvable-attribute check above is necessary but not
     * sufficient — K3's actual wording is "a silent no-op <b>unless the bean is behind a Spring
     * proxy</b>", and a resolvable attribute says nothing about whether one exists. Removing
     * {@code @Import(TransactionManagerConfig.class)} from {@code CaseManagementAutoConfiguration}
     * left every assertion in this method green, because
     * {@code AnnotationTransactionAttributeSource} answers a pure class-hierarchy/reflection
     * question that needs no {@code PlatformTransactionManager} or AOP infrastructure at all —
     * confirmed by actually removing the import and re-running (see the Task 25 report's Fix
     * round 1 section). Added: an assertion that a {@code PlatformTransactionManager} bean
     * actually exists, and that the bean {@code WebhookService.subscribe} is called through is
     * actually an AOP proxy — the two preconditions K3 says must both hold before
     * {@code @Transactional} does anything at all.
     */
    @Test
    void webhookServiceSubscribeStaysTransactionalAndUsesASecretStore() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm",
                        "casemgmt.schedulers.enabled=false")
                .run(context -> {
                    assertThat(context).as("TransactionManagerConfig must be wired in so a real "
                                    + "PlatformTransactionManager exists")
                            .hasSingleBean(PlatformTransactionManager.class);
                    assertThat(context).hasSingleBean(WebhookSecretStore.class);

                    WebhookService bean = context.getBean(WebhookService.class);
                    assertThat(AopUtils.isAopProxy(bean))
                            .as("the bean subscribe() is actually invoked through must be a Spring "
                                    + "AOP proxy, or @Transactional on it is a documented no-op (K3)")
                            .isTrue();

                    Method subscribe = ReflectionUtils.findMethod(bean.getClass(), "subscribe",
                            String.class, String.class, List.class, Actor.class);
                    // Negative control: list() carries no @Transactional anywhere in its
                    // hierarchy, so this must resolve to null — proving getTransactionAttribute
                    // does not just always return non-null for any method on this bean (i.e. that
                    // the positive assertion below is actually discriminating on something).
                    Method list = ReflectionUtils.findMethod(bean.getClass(), "list");
                    TransactionAttribute listAttr = new AnnotationTransactionAttributeSource()
                            .getTransactionAttribute(list, bean.getClass());
                    assertThat(listAttr).as("negative control: list() must NOT be transactional")
                            .isNull();
                    TransactionAttribute attribute = new AnnotationTransactionAttributeSource()
                            .getTransactionAttribute(subscribe, bean.getClass());
                    assertThat(attribute)
                            .as("subscribe() must stay @Transactional on the bean's actual "
                                    + "runtime class (the override), not just the WebhookService "
                                    + "superclass method it shadows")
                            .isNotNull();
                });
    }
}
