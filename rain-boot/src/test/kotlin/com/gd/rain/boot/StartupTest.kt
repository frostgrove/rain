package com.gd.rain.boot

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.seed.SeedCommand
import com.gd.rain.boot.seed.Seeder
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** A real `SpringApplication` start, so the listener, the post-processor and the auto-configuration all take part. */
class StartupTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Seeding {
        val ran = mutableListOf<String>()
        val err = ByteArrayOutputStream()

        @Bean
        fun commandOutput(): CommandOutput = CommandOutput(PrintStream(ByteArrayOutputStream()), PrintStream(err))

        @Bean
        fun second(): Seeder = recording("b-second", 10)

        @Bean
        fun first(): Seeder = recording("a-first", 10)

        @Bean
        fun earliest(): Seeder = recording("z-earliest", 0)

        private fun recording(
            name: String,
            order: Int,
        ): Seeder =
            object : Seeder {
                override val name = name
                override val order = order

                override fun seed() {
                    ran += name
                }
            }
    }

    @Test
    fun `a start without a stage is refused before any bean exists`() {
        assertThatThrownBy { start(Application::class.java, NAME, "rain.runtime.roles=api") }
            .isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
                assertThat(refusal.problems.map { it.path to it.code })
                    .containsExactly("rain.deployment.stage" to ProblemCode.REQUIRED)
            }
    }

    @Test
    fun `a valid start with roles builds the context`() {
        start(Application::class.java, NAME, "rain.runtime.roles=api", "rain.deployment.stage=test").use { context ->
            assertThat(context.getBean(DeploymentStage::class.java)).isEqualTo(DeploymentStage.TEST)
        }
    }

    @Test
    fun `the seed command runs every seeder by order then name and exits zero`() {
        val context = start(Seeding::class.java, NAME, "rain.runtime.command=seed", "rain.deployment.stage=test")
        val seeding = context.getBean(Seeding::class.java)

        assertThat(seeding.ran).containsExactly("z-earliest", "a-first", "b-second")
        assertThat(seeding.err.toString()).contains("seeding complete: ran=3")
        assertThat(SpringApplication.exit(context)).isZero()
    }

    @Test
    fun `config-check starts with no role and reports ok`() {
        val context = start(Application::class.java, NAME, "rain.runtime.command=config-check", "rain.deployment.stage=dev")

        assertThat(context.getBeanNamesForType(SeedCommand::class.java)).isEmpty()
        assertThat(SpringApplication.exit(context)).isZero()
    }

    private fun start(
        source: Class<*>,
        vararg properties: String,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(source)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .properties(*properties)
            .run()

    private companion object {
        const val NAME = "spring.application.name=sample"
    }
}
