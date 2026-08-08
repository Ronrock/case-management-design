package org.casemgmt;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module-local architecture check, deliberately scoped to what it can actually see.
 *
 * <p><b>Scope, stated honestly (Task 27).</b> ArchUnit imports from the RUNNING module's
 * classpath, and {@code case-management-core} cannot depend on {@code rest}, either gateway, the
 * starter or the PoC application — so this file sees {@code case-management-core} and nothing
 * else. The exclusion clause below for {@code engine.embedded} / {@code engine.remote} /
 * {@code starter} is retained only so the rule text states the intended repository-wide invariant;
 * from here those packages are empty and the clause is inert. Read it as documentation of intent,
 * not as coverage.
 *
 * <p>The repository-wide enforcement lives in {@code case-management-poc-app}:
 * {@code CrossModuleArchitectureTest} runs the same rule from the one module that pulls in all
 * five, and asserts up front that its import really covered each of them.
 * {@code NoCaseTypeVocabularyTest} covers the string-literal half that no bytecode rule can.
 * The former class-name rule that used to sit here — no type named after the PoC's case type
 * outside {@code org.casemgmt.poc} — was removed rather than duplicated: over core alone it could
 * never fail, and spelling the case type's own name here was itself the leak it claimed to
 * prevent.
 */
@AnalyzeClasses(packages = "org.casemgmt",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Core must stay engine-free: that is what makes both deployment modes possible
     * and keeps the state-machine tests free of engine setup (spec §3.2).
     * operaton-juel is an expression library, not the engine, so it is exempt.
     */
    @ArchTest
    static final ArchRule coreDoesNotDependOnOperaton = noClasses()
            .that().resideInAPackage("org.casemgmt..")
            .and().resideOutsideOfPackages("org.casemgmt.engine.embedded..",
                    "org.casemgmt.engine.remote..", "org.casemgmt.starter..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.operaton.bpm.engine..");
}
