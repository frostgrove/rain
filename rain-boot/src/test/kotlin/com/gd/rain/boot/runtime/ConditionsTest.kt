package com.gd.rain.boot.runtime

import com.gd.rain.core.config.ConfigurationProblemsException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class ConditionsTest {
    @Configuration(proxyBeanMethods = false)
    class Contributions {
        @Bean
        @ConditionalOnRainRole(RuntimeRole.API)
        fun route(): String = "route"

        @Bean
        @ConditionalOnRainRole(RuntimeRole.WORKER)
        fun scheduler(): Int = 1

        @Bean
        @ConditionalOnRainCommand("seed")
        fun seedOnly(): Long = 2L

        @Bean
        @ConditionalOnRainCommand
        fun anyCommand(): Double = 3.0
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(Contributions::class.java)

    @Test
    fun `an api-only process builds api contributions and no worker ones`() {
        runner.withPropertyValues("rain.runtime.roles=api").run { context ->
            assertThat(context)
                .hasBean("route")
                .doesNotHaveBean("scheduler")
                .doesNotHaveBean("seedOnly")
                .doesNotHaveBean("anyCommand")
        }
    }

    @Test
    fun `a worker-only process builds no routes`() {
        runner.withPropertyValues("rain.runtime.roles=worker").run { context ->
            assertThat(context).hasBean("scheduler").doesNotHaveBean("route")
        }
    }

    @Test
    fun `a command activates the roles it declares and its own contributions`() {
        runner.withPropertyValues("rain.runtime.command=seed").run { context ->
            assertThat(context)
                .hasBean("seedOnly")
                .hasBean("anyCommand")
                .doesNotHaveBean("route")
                .doesNotHaveBean("scheduler")
        }
    }

    @Test
    fun `a command with no roles builds no role contributions`() {
        runner.withPropertyValues("rain.runtime.command=config-check").run { context ->
            assertThat(context).hasBean("anyCommand").doesNotHaveBean("seedOnly").doesNotHaveBean("route")
        }
    }

    @Test
    fun `an invalid selection fails the context instead of matching nothing`() {
        runner.withPropertyValues("rain.runtime.roles=wrker").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().isInstanceOf(ConfigurationProblemsException::class.java)
        }
    }

    @Test
    fun `no selection at all fails the context`() {
        runner.run { context ->
            assertThat(context.startupFailure).rootCause().isInstanceOf(ConfigurationProblemsException::class.java)
        }
    }
}
