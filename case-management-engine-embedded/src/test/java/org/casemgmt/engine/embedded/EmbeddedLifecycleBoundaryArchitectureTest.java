package org.casemgmt.engine.embedded;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.base.DescribedPredicate;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.engine.OutboxEngineGateway;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.observation.SlaLifecyclePort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;
import java.util.function.Consumer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Enforces that the embedded adapter reports facts and never applies lifecycle effects itself. */
class EmbeddedLifecycleBoundaryArchitectureTest {

    private static final Set<String> ALLOWED_CASE_MANAGEMENT_TYPES = Set.of(
            "org.casemgmt.engine.EngineException",
            "org.casemgmt.engine.EngineGateway",
            "org.casemgmt.engine.EngineProcessRef",
            "org.casemgmt.engine.EngineTaskQuery",
            "org.casemgmt.engine.EngineTaskRef",
            "org.casemgmt.engine.HumanTaskRequest",
            "org.casemgmt.engine.MessageCorrelationRequest",
            "org.casemgmt.engine.StartProcessByKeyRequest",
            "org.casemgmt.engine.StartProcessRequest",
            "org.casemgmt.observation.ActivityLifecycleObservation",
            "org.casemgmt.observation.EngineObservation",
            "org.casemgmt.observation.EngineObservationHandler",
            "org.casemgmt.observation.EngineProcessAuthorityLookup",
            "org.casemgmt.observation.LegacyPlanModelObservationHandler",
            "org.casemgmt.observation.MilestoneObservation",
            "org.casemgmt.observation.ProcessCaseAuthority",
            "org.casemgmt.observation.ProcessObservation",
            "org.casemgmt.observation.UserTaskObservation",
            "org.casemgmt.orchestration.DeploymentResourceManifest",
            "org.casemgmt.orchestration.EngineDeploymentIdentity",
            "org.casemgmt.orchestration.OrchestrationDeploymentPort",
            "org.casemgmt.orchestration.OrchestrationMode");

    private static final JavaClasses EMBEDDED_ADAPTER = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.casemgmt.engine.embedded");

    @Test
    void importContainsTheProductionEmbeddedAdapter() {
        assertThat(EMBEDDED_ADAPTER.stream()
                .anyMatch(type -> type.getName().equals(EmbeddedEngineEventBridge.class.getName())))
                .as("the boundary rule must import the production event bridge")
                .isTrue();
    }

    @Test
    void embeddedAdapterCannotBypassTheCommonLifecycleHandler() {
        lifecycleOwnershipRule().check(EMBEDDED_ADAPTER);
    }

    @Test
    void repositoryDependencyMutationFailsTheBoundary() {
        var result = lifecycleOwnershipRule().evaluate(importFixture(RepositoryBypass.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(CaseRepository.class.getName());
    }

    @Test
    void eventAndSlaMethodReferenceMutationFailsTheBoundary() {
        var result = lifecycleOwnershipRule().evaluate(importFixture(EventAndSlaBypass.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains(EventPublisher.class.getName())
                .contains(SlaLifecyclePort.class.getName());
    }

    @Test
    void directJdbcMutationFailsTheBoundary() {
        var result = lifecycleOwnershipRule().evaluate(importFixture(JdbcBypass.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(JdbcClient.class.getName());
    }

    @Test
    void effectFacadeInAnOtherwiseAllowedNamespaceFailsTheBoundary() {
        var result = lifecycleOwnershipRule().evaluate(importFixture(EffectFacadeBypass.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains(OutboxEngineGateway.class.getName());
    }

    private static JavaClasses importFixture(Class<?> fixture) {
        return new ClassFileImporter().importClasses(fixture);
    }

    private static ArchRule lifecycleOwnershipRule() {
        return noClasses()
                .that().resideInAPackage("org.casemgmt.engine.embedded..")
                .should().dependOnClassesThat(forbiddenLifecycleSurface())
                .because("embedded adapters may look up engine facts and emit observations, "
                        + "but all CM persistence and lifecycle effects belong to core");
    }

    private static DescribedPredicate<JavaClass> forbiddenLifecycleSurface() {
        return new DescribedPredicate<>("a non-allowlisted CM type or JDBC surface") {
            @Override
            public boolean test(JavaClass type) {
                String name = type.getName();
                return (name.startsWith("org.casemgmt.") && !isAllowedCaseManagementType(name))
                        || name.startsWith("org.springframework.jdbc.")
                        || name.equals("javax.sql.DataSource")
                        || name.equals("java.sql.Connection");
            }
        };
    }

    private static boolean isAllowedCaseManagementType(String name) {
        if (name.startsWith("org.casemgmt.engine.embedded.")) {
            return true;
        }
        return ALLOWED_CASE_MANAGEMENT_TYPES.stream()
                .anyMatch(allowed -> name.equals(allowed) || name.startsWith(allowed + "$"));
    }

    static final class RepositoryBypass {
        private CaseRepository cases;
    }

    static final class EventAndSlaBypass {
        private SlaLifecyclePort sla;

        Consumer<CaseEvent> publishWith(EventPublisher publisher) {
            return publisher::publish;
        }
    }

    static final class JdbcBypass {
        private JdbcClient jdbc;
    }

    static final class EffectFacadeBypass {
        private OutboxEngineGateway gateway;
    }
}
