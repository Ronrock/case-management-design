package org.casemgmt;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

    /** No case-type knowledge leaks out of the PoC app (spec Global Constraints). */
    @ArchTest
    static final ArchRule noDomainVocabularyInTheService = noClasses()
            .that().resideInAPackage("org.casemgmt..")
            .and().resideOutsideOfPackage("org.casemgmt.poc..")
            .should().haveSimpleNameContaining("Complaint");
}
