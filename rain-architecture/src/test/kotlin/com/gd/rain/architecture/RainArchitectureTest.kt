package com.gd.rain.architecture

import com.gd.rain.test.RainArchRules
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every rain module and sample, held to RainArchRules. The samples live under `com.gd.rain.sample`, so the module rules
 * already keep them off other modules' internal and auto-configuration packages.
 */
class RainArchitectureTest {
    private val production: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(RainArchRules.ROOT, "com.gd.rain.sample")

    // rain-test's rule fixtures (package `fixture`) break the rules on purpose, to prove each rule fires.
    private val tests: JavaClasses =
        ClassFileImporter().withImportOption { location -> !location.contains("/fixture/") }.importPaths(
            checkNotNull(System.getProperty("rain.architecture.testClasses")) { "the build passes the modules' test class directories" }
                .split(File.pathSeparator)
                .map(Path::of)
                .filter(Files::isDirectory),
        )

    @Test
    fun `no module uses another module's internal packages`() {
        RainArchRules.internalPackagesStayInTheirModule().check(production)
    }

    @Test
    fun `only auto-configuration uses another module's auto-configuration`() {
        RainArchRules.autoConfigurationStaysAmongAutoConfigurations().check(production)
    }

    @Test
    fun `nothing depends on the packages rain was ported from`() {
        RainArchRules.noDependencyOn("com.gd.lease..", "com.gd.framework..").check(production)
        RainArchRules.noDependencyOn("com.gd.lease..", "com.gd.framework..").check(tests)
    }

    @Test
    fun `no test sleeps`() {
        RainArchRules.testsDoNotSleep().check(tests)
    }
}
