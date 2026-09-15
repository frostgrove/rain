package com.gd.rain.architecture

import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.test.RainArchRules
import com.gd.rain.web.problem.ErrorCodeRegistrar
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An application registers the catalogs of every module it uses, and a value two catalogs declare refuses its start.
 * Every catalog object rain and its samples declare is found on the classpath and registered together here, so a code
 * two modules both need lives in rain-core instead of being declared twice.
 */
class ErrorCatalogsRegisterTogetherTest {
    @Test
    fun `every error catalog of rain and its samples registers together, each code declared once`() {
        val catalogs =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages(RainArchRules.ROOT)
                .filter { !it.isInterface && it.isAssignableTo(ErrorCodeCatalog::class.java) && it.tryGetField("INSTANCE").isPresent }
                .map { it.reflect().getField("INSTANCE").get(null) as ErrorCodeCatalog }

        assertThat(catalogs.map(ErrorCodeCatalog::owner)).contains("rain-core", "rain-web", "rain-crud", "rain-access")
        ErrorCodeRegistrar.register(catalogs)
    }
}
