package com.gd.rain.crud.autoconfigure

import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.crud.error.RainCrudErrorCodes
import com.gd.rain.persistence.fault.DatabaseViolationMapper
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import com.gd.rain.web.problem.ErrorCodeRegistrar
import com.gd.rain.web.problem.RainWebErrorCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RainCrudAutoConfigurationTest {
    @Test
    fun `rain-crud's error codes join the registry rain-web builds`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainWebErrorAutoConfiguration::class.java, RainCrudAutoConfiguration::class.java))
            .run { context ->
                val registry = context.getBean(ErrorCodeRegistry::class.java)

                RainCrudErrorCodes.codes.forEach { assertThat(registry.ownerOf(it)).describedAs(it.value).isEqualTo("rain-crud") }
                assertThat(context).hasSingleBean(DatabaseViolationMapper::class.java)
            }
    }

    @Test
    fun `rain-crud's codes collide with no code rain-core or rain-web declares`() {
        val registry = ErrorCodeRegistrar.register(listOf(RainErrorCodes, RainWebErrorCodes, RainCrudErrorCodes))

        assertThat(registry.size).isEqualTo(RainErrorCodes.codes.size + RainWebErrorCodes.codes.size + RainCrudErrorCodes.codes.size)
    }

    @Test
    fun `every registered auto-configuration is one`() {
        val imports =
            javaClass.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .flatMap { it.readText().lines() }
                .filter(String::isNotBlank)

        assertThat(imports).contains(RainCrudAutoConfiguration::class.java.name)
        assertThat(Class.forName(RainCrudAutoConfiguration::class.java.name).isAnnotationPresent(AutoConfiguration::class.java)).isTrue()
    }
}
