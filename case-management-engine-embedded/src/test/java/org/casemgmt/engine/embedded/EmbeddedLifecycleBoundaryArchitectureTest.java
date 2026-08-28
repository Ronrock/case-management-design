package org.casemgmt.engine.embedded;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.JdbcCaseProjectionPort;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Enforces that the embedded adapter reports facts and never applies lifecycle effects itself. */
class EmbeddedLifecycleBoundaryArchitectureTest {

    private static final JavaClasses EMBEDDED_ADAPTER = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.casemgmt.engine.embedded");

    private static final List<Class<?>> FORBIDDEN_EFFECT_WRITERS = List.of(
            CaseProjectionPort.class,
            JdbcCaseProjectionPort.class,
            PlanItemRepository.class,
            CaseTaskRepository.class,
            MilestoneRepository.class,
            AppliedObservationRepository.class,
            AuditRepository.class,
            EventRepository.class,
            SlaRepository.class,
            WebhookRepository.class);

    /** Read-only authority lookup is allowed; these methods are the projection-write surface. */
    private static final Map<String, Set<String>> FORBIDDEN_REPOSITORY_CALLS = Map.of(
            "org.casemgmt.repo.CaseRepository", Set.of(
                    "insert", "update", "updateCancellationReason", "applyCanonicalPatch",
                    "updateSlaStatusMonotonic"),
            "org.casemgmt.repo.LinkedProcessRepository", Set.of(
                    "insert", "insertRoot", "markState", "markSync"));

    @Test
    void importContainsTheProductionEmbeddedAdapter() {
        assertThat(EMBEDDED_ADAPTER.stream()
                .anyMatch(type -> type.getName().equals(EmbeddedEngineEventBridge.class.getName())))
                .as("the boundary rule must import the production event bridge")
                .isTrue();
    }

    @Test
    void embeddedAdapterCannotBypassTheCommonLifecycleHandler() {
        for (Class<?> writer : FORBIDDEN_EFFECT_WRITERS) {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("org.casemgmt.engine.embedded..")
                    .should().dependOnClassesThat().areAssignableTo(writer)
                    .because("embedded adapters may construct observations, but projection, "
                            + "audit, event, SLA and claim writes belong to the common handler");
            rule.check(EMBEDDED_ADAPTER);
        }

        classes().that().resideInAPackage("org.casemgmt.engine.embedded..")
                .should(notCallRepositoryProjectionWriters())
                .because("correlation may read authority, but lifecycle projection writes "
                        + "must be performed by the common handler")
                .check(EMBEDDED_ADAPTER);
    }

    private static ArchCondition<JavaClass> notCallRepositoryProjectionWriters() {
        return new ArchCondition<>("not call repository projection writers directly") {
            @Override
            public void check(JavaClass adapter, ConditionEvents events) {
                adapter.getMethodCallsFromSelf().stream()
                        .filter(call -> FORBIDDEN_REPOSITORY_CALLS
                                .getOrDefault(call.getTargetOwner().getName(), Set.of())
                                .contains(call.getName()))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(adapter,
                                adapter.getName() + " directly calls "
                                        + call.getTargetOwner().getName() + "."
                                        + call.getName())));
            }
        };
    }
}
