package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.OutboxEngineGateway;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.springframework.boot.autoconfigure.AutoConfigurations;
// Spring Boot 4 relocated DataSourceAutoConfiguration out of spring-boot-autoconfigure's
// org.springframework.boot.autoconfigure.jdbc package (where the brief's own import pointed,
// and where it still lives in Boot 3) into a new dedicated spring-boot-jdbc artifact under
// org.springframework.boot.jdbc.autoconfigure — confirmed by inspecting spring-boot-jdbc-4.0.7.jar
// directly, since "package does not exist" at the old location gave no other clue.
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationTest {

    /**
     * Deviation from the brief: {@code EmbeddedEngineAutoConfiguration} is added to the
     * registered auto-configurations. The brief's own runner omitted it, which left
     * {@link #embeddedModeWithoutAnEngineOnTheClasspathFailsWithAClearMessage} unable to pass —
     * with only {@code CaseManagementAutoConfiguration} and {@code RemoteEngineAutoConfiguration}
     * registered, embedded mode has no {@code EngineGateway} bean-producing configuration at all,
     * so startup would fail with a generic {@code NoSuchBeanDefinitionException} for
     * {@code EngineGateway} instead of the friendly "operaton-bpm-spring-boot-starter" message
     * {@code EmbeddedEngineAutoConfiguration.embeddedEngineRequirementCheck} produces — confirmed
     * by actually running the test with the omission restored (see the Task 25 report's
     * mechanism-stripping evidence).
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    EmbeddedEngineAutoConfiguration.class, CaseManagementAutoConfiguration.class,
                    RemoteEngineAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:autoconfig;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void disabledByDefaultPropertyLeavesTheContextClean() {
        runner.withPropertyValues("casemgmt.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CaseService.class));
    }

    @Test
    void remoteModeRegistersTheOutboxGateway() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm")
                .run(context -> {
                    assertThat(context).hasSingleBean(CaseService.class);
                    assertThat(context.getBean(EngineGateway.class))
                            .isInstanceOf(OutboxEngineGateway.class);
                });
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
        runner.withClassLoader(new FilteredClassLoader(ProcessEngine.class))
                .withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=embedded",
                        "casemgmt.events.type-prefix=org.example.cm")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("operaton-bpm-spring-boot-starter"));
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
     * Not one of the brief's four test cases. {@code CaseManagementAutoConfiguration.webhookService}
     * overrides {@code WebhookService.subscribe} in an anonymous subclass to capture the plaintext
     * secret (K3), which looked like a K3-shaped risk on inspection: Java annotations are not
     * normally inherited across an override, so re-declaring {@code @Transactional} there (which
     * the production code does) looked load-bearing. Verified with the negative control below that
     * it is actually NOT — {@code AnnotationTransactionAttributeSource} resolves
     * {@code @Transactional} by walking up the class hierarchy for a same-signature method, so
     * {@code WebhookService.subscribe}'s own annotation is found either way (confirmed by
     * stripping the override's explicit annotation and re-running: still passed — see the Task 25
     * report). The explicit re-declaration stays in the production code as self-documenting
     * defense in depth; this test's real job now is to guard the invariant itself — the bean
     * Spring actually constructs must resolve {@code subscribe} as transactional — regardless of
     * which mechanism supplies that.
     */
    @Test
    void webhookServiceSubscribeOverrideStaysTransactional() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm")
                .run(context -> {
                    WebhookService bean = context.getBean(WebhookService.class);
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
