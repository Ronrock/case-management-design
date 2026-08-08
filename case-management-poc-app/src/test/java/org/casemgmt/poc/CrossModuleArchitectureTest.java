package org.casemgmt.poc;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architecture rules, run from the ONE module that can actually see every other module.
 *
 * <p><b>Why this exists next to {@code case-management-core}'s own {@code ArchitectureTest}
 * (Task 27, carried Task 25 Minor).</b> ArchUnit imports from the RUNNING module's classpath.
 * {@code case-management-core} cannot depend on {@code rest}, either gateway, the starter or this
 * application — that acyclic dependency direction is the whole point of the module split — so the
 * rules living there structurally cannot see four of the five modules, and their exclusion clauses
 * for {@code engine.embedded} / {@code engine.remote} / {@code starter} / {@code poc} read as
 * coverage that does not exist. This module sits at the bottom of the dependency graph and pulls
 * in all five, so the same rules evaluated here are the ones with real reach. Core keeps its copy
 * for fast module-local feedback; this is the authoritative run.
 *
 * <p>The rules are checked as ordinary JUnit assertions over an explicit
 * {@link ClassFileImporter} import rather than through {@code @AnalyzeClasses}/{@code @ArchTest},
 * so that {@link #theImportActuallySeesEveryModule()} can assert on the imported set directly.
 * That guard exists because <b>a rule's reach is invisible in its result</b>: a rule that examined
 * only {@code core} reports exactly what a rule that examined all five modules reports, as long as
 * both find nothing. Verified rather than assumed: pointing the importer at a nonexistent package
 * makes all three tests fail here, because ArchUnit 1.4.1 defaults
 * {@code archRule.failOnEmptyShould} to true and refuses a rule that checked zero classes — so the
 * TOTALLY empty import is already caught by the library. What the library cannot catch, and this
 * guard can, is a PARTIALLY empty import: drop {@code case-management-rest} from this module's
 * POM, or rename a package, and both rules below still evaluate happily over whatever remains and
 * still report success. That is the shape of the eight vacuous mechanisms this project has already
 * found, so the covered modules are pinned one marker package at a time rather than by a count.
 *
 * <p>What these rules still do NOT cover, deliberately and stated rather than implied: they match
 * TYPE names and TYPE dependencies. A {@code case.caseDefKey().equals("complaint")} branch or a
 * bare {@code "complaint"} string literal is invisible to bytecode-level structural analysis.
 * {@link NoCaseTypeVocabularyTest} covers that half by scanning source text.
 */
class CrossModuleArchitectureTest {

    /**
     * Every {@code org.casemgmt} class on this module's runtime classpath: this application's own
     * classes plus the jars of core, rest, both gateways and the starter. Test classes are
     * excluded, so nothing here sees any module's own fixtures.
     */
    private static final JavaClasses ALL_MODULES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.casemgmt");

    /** One package that only exists if the corresponding module's classes were really imported. */
    private static final List<String> ONE_MARKER_PACKAGE_PER_MODULE = List.of(
            "org.casemgmt.service",          // case-management-core
            "org.casemgmt.rest.controller",  // case-management-rest
            "org.casemgmt.engine.embedded",  // case-management-engine-embedded
            "org.casemgmt.engine.remote",    // case-management-engine-remote
            "org.casemgmt.starter",          // case-management-spring-boot-starter
            "org.casemgmt.poc");             // case-management-poc-app

    @Test
    void theImportActuallySeesEveryModule() {
        for (String markerPackage : ONE_MARKER_PACKAGE_PER_MODULE) {
            assertThat(ALL_MODULES.stream()
                    .anyMatch(c -> c.getPackageName().startsWith(markerPackage)))
                    .as("no class imported from '%s' — the rules below would pass vacuously; "
                            + "check this module's POM and the package names", markerPackage)
                    .isTrue();
        }
    }

    /**
     * Core must stay engine-free: that is what makes both deployment modes possible and keeps the
     * state-machine tests free of engine setup (spec §3.2). The two gateway modules are where the
     * engine is allowed to appear; the starter wires them; this application boots one. Everything
     * else — {@code domain}, {@code repo}, {@code service}, {@code event}, {@code sla},
     * {@code error} and the whole {@code rest} module — must not name an engine type at all.
     * {@code operaton-juel} is an expression library, not the engine, so it is exempt by package.
     */
    @Test
    void onlyTheGatewaysTheStarterAndTheApplicationDependOnTheOperatonEngine() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("org.casemgmt..")
                .and().resideOutsideOfPackages("org.casemgmt.engine.embedded..",
                        "org.casemgmt.engine.remote..", "org.casemgmt.starter..", "org.casemgmt.poc..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.operaton.bpm.engine..");

        rule.check(ALL_MODULES);
    }

    /**
     * No case-type knowledge leaks out of {@code case-management-poc-app} (spec Global
     * Constraints) — the type-name half. See {@link NoCaseTypeVocabularyTest} for the text half,
     * which is the one that catches a literal.
     */
    @Test
    void noCaseTypeNamedTypesOutsideThePocApplication() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("org.casemgmt..")
                .and().resideOutsideOfPackage("org.casemgmt.poc..")
                .should().haveSimpleNameContaining("Complaint");

        rule.check(ALL_MODULES);
    }
}
