package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineEventBridge;
import org.casemgmt.engine.embedded.ProcessCaseCorrelation;
import org.casemgmt.engine.embedded.EmbeddedOrchestrationDeploymentPort;
import org.casemgmt.engine.embedded.ProcessActivityClassifier;
import org.casemgmt.engine.embedded.RepositoryProcessActivityClassifier;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.projection.CaseProjectionPort;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
     * <p><b>This nesting is the only thing preventing that failure. It is not optional, and it is
     * not cargo cult.</b> An attempt to reproduce the {@code NoClassDefFoundError} in-process with
     * a widened {@code FilteredClassLoader} (hiding {@code ProcessEngine}, {@code TaskService},
     * {@code RuntimeService} AND {@code EmbeddedEngineGateway} together) did NOT trigger it — but
     * that non-reproduction was a HARNESS ARTIFACT, adjudicated by bytecode inspection rather than
     * by taking either side's word for it, and it says nothing whatever about whether the original
     * shape was safe:
     * <ul>
     *   <li>{@code FilteredClassLoader} cannot isolate anything here. {@code javap} of
     *       spring-boot-test-4.0.7 shows its constructor calls {@code super(new URL[0], parent)}:
     *       it owns ZERO URLs and therefore never DEFINES a class — every non-filtered name is
     *       delegated to, and defined by, the application classloader. The configuration class is
     *       also handed to {@code ApplicationContextRunner} as a {@code Class} literal from test
     *       source, so it is app-loaded regardless of the filter.
     *       {@code Class.getDeclaredMethods()} resolves parameter types through the DEFINING
     *       loader — the application loader, which sees {@code TaskService} on this module's own
     *       test classpath. Non-reproduction was structurally guaranteed.</li>
     *   <li>The tempting explanation that Spring tolerates the error is FALSE. {@code javap} of
     *       spring-core-7.0.8 {@code ReflectionUtils.getDeclaredMethods} shows an exception table
     *       catching {@code Throwable} and rethrowing
     *       {@code IllegalStateException("Failed to introspect Class...")}. Spring explicitly does
     *       NOT swallow a {@code NoClassDefFoundError} there.</li>
     *   <li>The other tempting explanation, ASM short-circuiting, does not apply either: the outer
     *       class's own {@code @ConditionalOnProperty} matched, so it was registered and
     *       instantiated, at which point {@code buildAutowiringMetadata} runs
     *       {@code getDeclaredMethods} unconditionally — a method-level {@code @ConditionalOnClass}
     *       notwithstanding.</li>
     * </ul>
     *
     * <p>With the nesting in place the outer class carries NO Operaton and no
     * {@code org.casemgmt.engine.embedded} type in any signature (only a no-arg
     * {@code BeanFactoryPostProcessor} returning a Spring type), so the failure is structurally
     * impossible in both modes. No test pins this, and realistically none can in-process: any
     * in-JVM attempt still resolves this configuration class through a loader that can see the
     * optional jar. The guard IS the code shape. Un-nesting it re-opens the failure with nothing
     * in the suite to notice. See the Task 25 report and the Task 27 {@code FINDINGS.md} entry
     * "harness isolation" for the full trail.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ProcessEngine.class)
    @ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "embedded",
            matchIfMissing = true)
    static class EmbeddedEngineGatewayConfiguration {

        @Bean
        @ConditionalOnMissingBean(EngineGateway.class)
        @ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "embedded",
                matchIfMissing = true)
        EngineGateway embeddedEngineGateway(TaskService taskService, RuntimeService runtimeService) {
            return new EmbeddedEngineGateway(taskService, runtimeService);
        }

        @Bean
        @ConditionalOnBean(RuntimeService.class)
        ProcessCaseCorrelation processCaseCorrelation(RuntimeService runtimeService) {
            return processInstanceId -> {
                // Task create events are published from inside Operaton's start command,
                // before the new runtime row is necessarily visible to a query. The caseId
                // process variable is already present in the command context, however, so it
                // is the reliable correlation source on this path. Keep the business-key
                // lookup as a fallback for observations that do not carry that variable.
                Object caseId = runtimeService.getVariable(
                        processInstanceId, EmbeddedEngineGateway.CASE_ID_VARIABLE);
                if (caseId != null) {
                    return caseId.toString();
                }
                var instance = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId).singleResult();
                return instance == null ? null : instance.getBusinessKey();
            };
        }

        @Bean
        EmbeddedEngineEventBridge embeddedEngineEventBridge(
                CaseProjectionPort projections, ProcessCaseCorrelation correlation,
                ProcessActivityClassifier classifier) {
            // Do not guard this with @ConditionalOnBean(CaseProjectionPort.class).
            // This auto-configuration deliberately runs before CaseManagementAutoConfiguration,
            // which imports the repository configuration that declares that port. Evaluating
            // the condition here therefore skips the bridge even though the dependency exists
            // by bean-instantiation time. Required method parameters provide the correct
            // fail-fast behaviour without making registration order observable.
            return new EmbeddedEngineEventBridge(projections, correlation, classifier);
        }

        @Bean
        @ConditionalOnBean(RepositoryService.class)
        ProcessActivityClassifier processActivityClassifier(RepositoryService repositoryService) {
            return new RepositoryProcessActivityClassifier(repositoryService);
        }

        @Bean
        @ConditionalOnBean(RepositoryService.class)
        OrchestrationDeploymentPort embeddedOrchestrationDeploymentPort(
                RepositoryService repositoryService) {
            return new EmbeddedOrchestrationDeploymentPort(repositoryService);
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
