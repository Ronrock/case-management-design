package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineGateway;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

@AutoConfiguration(before = CaseManagementAutoConfiguration.class)
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddedEngineAutoConfiguration {

    /**
     * Fix round 1, Important 2: {@code embeddedEngineGateway} moved into this nested,
     * class-level-{@code @ConditionalOnClass}-gated static configuration, out of the outer
     * class's own body. The outer {@code EmbeddedEngineAutoConfiguration} previously carried
     * {@code TaskService}/{@code RuntimeService}/{@code EmbeddedEngineGateway} directly in a
     * {@code @Bean} method SIGNATURE, guarded only by a method-level
     * {@code @ConditionalOnClass(ProcessEngine.class)} — review round 1 flagged that
     * {@code AutowiredAnnotationBeanPostProcessor}/factory-method type resolution can reflectively
     * introspect a configuration class's declared methods regardless of a method-level condition,
     * which for a consumer without the Operaton engine on the classpath could throw
     * {@code NoClassDefFoundError} several frames before this class's own fail-fast check
     * ({@link #embeddedEngineRequirementCheck}) ever gets a chance to produce its friendly
     * message — precisely the failure that check exists to pre-empt.
     *
     * <p>Actually reproducing that with a widened {@code FilteredClassLoader} (hiding
     * {@code ProcessEngine}, {@code TaskService}, {@code RuntimeService} AND
     * {@code EmbeddedEngineGateway} together, both through the full auto-configuration stack and
     * in isolation with only this class registered) did NOT trigger it on this Spring Boot 4.0.7 /
     * JDK combination — {@code AutoConfigurationTest.embeddedModeWithoutAnEngineOnTheClasspathFailsWithAClearMessage}
     * and a temporary isolated diagnostic both still produced the friendly message / a clean
     * context, not a {@code NoClassDefFoundError}, even with the WIDER filter and the ORIGINAL
     * (unnested) shape. Most likely Spring's own {@code ReflectionUtils} tolerates a
     * {@code NoClassDefFoundError} while enumerating declared methods, and/or ASM-based condition
     * evaluation short-circuits before any bean method that fails its own condition is ever
     * reflectively resolved. That tolerance is an internal implementation detail, not a
     * documented contract, so this restructuring is applied anyway as the textbook-idiomatic
     * Spring Boot shape (many auto-configurations in the wild use exactly this
     * nested-{@code @ConditionalOnClass}-configuration pattern for optional integrations) rather
     * than relying on an unspecified resilience behaviour to keep working — see the Task 25
     * report's Fix round 1 section for the full mechanism-stripping trail on this one.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ProcessEngine.class)
    static class EmbeddedEngineGatewayConfiguration {

        @Bean
        @ConditionalOnMissingBean(EngineGateway.class)
        @ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "embedded",
                matchIfMissing = true)
        EngineGateway embeddedEngineGateway(TaskService taskService, RuntimeService runtimeService) {
            return new EmbeddedEngineGateway(taskService, runtimeService);
        }
    }

    /**
     * Fails fast and says what is missing, instead of leaving the user with a
     * NoSuchBeanDefinitionException for EngineGateway several frames deeper.
     *
     * <p>Deviation from the brief: implemented as a {@link BeanFactoryPostProcessor}, not a
     * plain {@code @Bean Object(...)}. A plain bean is just another singleton competing for a
     * slot in {@code preInstantiateSingletons}' iteration order — confirmed by running
     * {@code AutoConfigurationTest.embeddedModeWithoutAnEngineOnTheClasspathFailsWithAClearMessage}
     * with that shape: {@code CaseManagementAutoConfiguration}'s {@code transitionApplier} (which
     * needs an {@code EngineGateway}) got instantiated first and failed with a bare
     * {@code NoSuchBeanDefinitionException}, exactly the "several frames deeper" failure this
     * check exists to pre-empt — {@code @AutoConfiguration(before = ...)} orders which
     * configuration CLASS gets its {@code @Bean} methods registered first, but does not by
     * itself guarantee registration order among unrelated regular singletons is honoured during
     * eager instantiation. {@code BeanFactoryPostProcessor} beans are a different, earlier phase
     * entirely — {@code AbstractApplicationContext.refresh()} invokes all of them (
     * {@code invokeBeanFactoryPostProcessors}) before it pre-instantiates any ordinary singleton
     * ({@code finishBeanFactoryInitialization}), so this check is structurally guaranteed to run,
     * and throw, before {@code transitionApplier} or anything else downstream is even attempted.
     * The factory method is {@code static} per the standard {@code @Bean}
     * {@code BeanFactoryPostProcessor} guidance, so it does not force early instantiation of the
     * declaring configuration class itself.
     *
     * <p>The presence check itself is a second, related fix: a bare {@code Class.forName(name)}
     * resolves via the CALLER class's own defining classloader — for THIS class, that is
     * whichever classloader first loaded {@code EmbeddedEngineAutoConfiguration.class} (in a real
     * application, the single application classloader, so this distinction never shows up).
     * {@code ApplicationContextRunner.withClassLoader(...)} (used by
     * {@code AutoConfigurationTest} to simulate a downstream consumer without the Operaton engine
     * on the classpath) associates a {@code FilteredClassLoader} with the bean FACTORY, not with
     * classes referenced by direct {@code Class} literal in test code — so a plain
     * {@code Class.forName} call here never observed the simulated absence and the test could not
     * pass for the intended reason. {@link ClassUtils#isPresent} against
     * {@link ConfigurableListableBeanFactory#getBeanClassLoader()} is what {@code @ConditionalOnClass}
     * itself uses internally, and it is what actually honours the classloader
     * {@code ApplicationContextRunner} configures — in production it still resolves to the
     * application's own classloader, so the check's real-world meaning is unchanged.
     */
    @Bean
    @ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "embedded",
            matchIfMissing = true)
    static BeanFactoryPostProcessor embeddedEngineRequirementCheck() {
        return new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                boolean engineOnClasspath = ClassUtils.isPresent(
                        "org.operaton.bpm.engine.ProcessEngine", beanFactory.getBeanClassLoader());
                if (!engineOnClasspath) {
                    throw new IllegalStateException(
                            "casemgmt.engine.mode=embedded requires the Operaton engine on the classpath. "
                                    + "Add org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter, "
                                    + "or set casemgmt.engine.mode=remote.");
                }
            }
        };
    }
}
