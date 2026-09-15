package com.gd.rain.test

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import fixture.app.UsesRainInternal
import fixture.app.UsesRainPublic
import fixture.rain.alpha.Sleeps
import fixture.rain.alpha.UsesBetaAutoConfiguration
import fixture.rain.alpha.UsesBetaInternal
import fixture.rain.alpha.UsesBetaPublicAndOwnInternal
import fixture.rain.alpha.autoconfigure.AlphaAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Each rule fires on the fixture that breaks it and stays quiet on the one that keeps it. */
class RainArchRulesTest {
    private val root = "fixture.rain"

    private fun violations(
        rule: ArchRule,
        vararg classes: Class<*>,
    ): List<String> = rule.evaluate(ClassFileImporter().importClasses(*classes)).failureReport.details

    @Test
    fun `another module's internal package is refused, a public class and the module's own internals are not`() {
        // One violation per use: the constructor parameter, the field and its getter.
        assertThat(violations(RainArchRules.internalPackagesStayInTheirModule(root), UsesBetaInternal::class.java))
            .isNotEmpty()
            .allMatch { it.contains("UsesBetaInternal") && it.contains("fixture.rain.beta.internal.Hidden") }
        assertThat(violations(RainArchRules.internalPackagesStayInTheirModule(root), UsesBetaPublicAndOwnInternal::class.java)).isEmpty()
    }

    @Test
    fun `another module's auto-configuration is refused outside auto-configuration and allowed inside it`() {
        assertThat(violations(RainArchRules.autoConfigurationStaysAmongAutoConfigurations(root), UsesBetaAutoConfiguration::class.java))
            .isNotEmpty()
        assertThat(violations(RainArchRules.autoConfigurationStaysAmongAutoConfigurations(root), AlphaAutoConfiguration::class.java))
            .isEmpty()
    }

    @Test
    fun `an application using an internal class is refused, one using the public API is not`() {
        assertThat(violations(RainArchRules.applicationUsesOnlyPublicApi(root), UsesRainInternal::class.java)).isNotEmpty()
        assertThat(violations(RainArchRules.applicationUsesOnlyPublicApi(root), UsesRainPublic::class.java)).isEmpty()
    }

    @Test
    fun `a dependency on a named package is refused`() {
        assertThat(violations(RainArchRules.noDependencyOn("fixture.rain.beta.internal.."), UsesBetaInternal::class.java)).isNotEmpty()
        assertThat(violations(RainArchRules.noDependencyOn("fixture.rain.beta.internal.."), UsesRainPublic::class.java)).isEmpty()
    }

    @Test
    fun `a sleeping class is refused`() {
        assertThat(violations(RainArchRules.testsDoNotSleep(), Sleeps::class.java)).isNotEmpty()
        assertThat(violations(RainArchRules.testsDoNotSleep(), UsesRainPublic::class.java)).isEmpty()
    }
}
