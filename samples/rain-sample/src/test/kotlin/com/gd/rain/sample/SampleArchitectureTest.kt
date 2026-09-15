package com.gd.rain.sample

import com.gd.rain.test.RainArchRules
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** The sample is an application: it reaches rain through public API only, as any product would have to. */
class SampleArchitectureTest {
    private val sample: JavaClasses = ClassFileImporter().importPackages("com.gd.rain.sample")

    @Test
    fun `no class of the sample depends on a rain internal or auto-configuration package`() {
        noClasses()
            .that()
            .resideInAPackage("com.gd.rain.sample..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.gd.rain..internal..", "com.gd.rain..autoconfigure..")
            .because("an application reaches rain only through its public API")
            .check(sample)
    }

    @Test
    fun `the module rules rain keeps hold for the sample`() {
        RainArchRules.internalPackagesStayInTheirModule().check(sample)
        RainArchRules.autoConfigurationStaysAmongAutoConfigurations().check(sample)
    }

    @Test
    fun `no test of the sample sleeps`() {
        RainArchRules.testsDoNotSleep().check(sample)
    }
}
