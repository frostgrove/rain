package com.gd.rain.core

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** rain-core is what a consumer's domain code depends on, so it may not pull a framework in with it. */
class CoreDependenciesTest {
    private val production =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.gd.rain.core")

    @Test
    fun `rain-core depends on no framework or serialisation library`() {
        noClasses()
            .that()
            .resideInAPackage("com.gd.rain.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "tools.jackson..", "com.fasterxml..", "jakarta..", "org.jooq..")
            .check(production)
    }
}
