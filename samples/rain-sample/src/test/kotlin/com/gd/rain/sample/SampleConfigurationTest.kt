package com.gd.rain.sample

import com.gd.rain.boot.config.ConfigurationContributors
import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.boot.config.ValidationReport
import com.gd.rain.boot.runtime.CommandDeclarations
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.sample.stand.Stand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** The sample's configuration as rain validates it before any bean exists: `application.yml` plus what one process states. */
class SampleConfigurationTest {
    private val classLoader = javaClass.classLoader

    private fun validate(
        stated: Map<String, String>,
        environment: Map<String, String> = emptyMap(),
        withApplicationYml: Boolean = true,
    ): ValidationReport {
        val sources = StandardEnvironment()
        sources.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        sources.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        sources.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment),
        )
        sources.propertySources.addFirst(MapPropertySource("stated", stated))
        if (withApplicationYml) {
            YamlPropertySourceLoader()
                .load(
                    "application.yml",
                    ClassPathResource("application.yml"),
                ).forEach(sources.propertySources::addLast)
        }
        return RainConfigurationValidator.validate(
            sources,
            ConfigurationContributors.load(classLoader),
            CommandDeclarations.load(classLoader),
        )
    }

    private fun process(vararg entries: Pair<String, String>): Map<String, String> =
        linkedMapOf("rain.deployment.stage" to "test", "rain.access.token.signing-key" to Stand.SIGNING_KEY) + entries

    @ParameterizedTest
    @ValueSource(
        strings = [
            "rain.runtime.roles=api",
            "rain.runtime.roles=worker",
            "rain.runtime.roles=api,worker",
            "rain.runtime.command=ticket-report",
        ],
    )
    fun `application yml with a stage and a runtime selection has no fatal problem`(selection: String) {
        val (key, value) = selection.split('=', limit = 2)

        val report = validate(process(key to value))

        assertThat(report.fatal).isEmpty()
    }

    @Test
    fun `an event stream that would outlive the request budget contradicts rain web`() {
        val report = validate(process("rain.runtime.roles" to "api", "sample.tickets.events.stream-for" to "60s"))

        val problem = report.fatal.single()
        assertThat(problem.path).isEqualTo("sample.tickets.events.stream-for")
        assertThat(problem.code).isEqualTo(ProblemCode.CONTRADICTS)
        assertThat(problem.message).contains("rain.web.request-budget PT1M")
    }

    @Test
    fun `the seed states one agent for every role and names no other role`() {
        val report =
            validate(
                process(
                    "rain.runtime.roles" to "api",
                    "sample.seed.agents.janitor.identifier" to "janitor@helpdesk.example",
                    "sample.seed.agents.janitor.display-name" to "Jo",
                    "sample.seed.agents.supervisor.identifier" to " Admin@Helpdesk.Example ",
                ),
            )

        assertThat(report.fatal.map { it.path to it.code }).containsExactlyInAnyOrder(
            "sample.seed.agents.janitor" to ProblemCode.INVALID,
            "sample.seed.agents" to ProblemCode.CONTRADICTS,
        )
    }

    @Test
    fun `in prod the initial password stated in a file is refused, and from the environment it is accepted`() {
        val prod = mapOf("rain.deployment.stage" to "prod", "rain.runtime.command" to "seed")
        val key = mapOf("RAIN_ACCESS_TOKEN_SIGNINGKEY" to Stand.SIGNING_KEY)

        val fromFile = validate(prod + ("sample.seed.initial-password" to Stand.PASSWORD), environment = key)
        val fromEnvironment = validate(prod, environment = key + ("SAMPLE_SEED_INITIALPASSWORD" to Stand.PASSWORD))

        assertThat(fromFile.fatal.map { it.path to it.code }).containsExactly("sample.seed.initial-password" to ProblemCode.INVALID)
        assertThat(fromFile.fatal.single().message).doesNotContain(Stand.PASSWORD)
        assertThat(fromEnvironment.fatal).isEmpty()
    }

    @Test
    fun `nothing stated names every value the helpdesk needs, beside rain's`() {
        val report = validate(mapOf("spring.application.name" to "rain-sample"), withApplicationYml = false)

        val byPath = report.fatal.associateBy { it.path }
        assertThat(byPath.keys).contains("sample.tickets", "sample.seed")
        assertThat(checkNotNull(byPath["sample.tickets"]).message)
            .contains("sample.tickets.events.stream-for", "sample.tickets.summary.pool", "sample.tickets.report.page-size")
        assertThat(checkNotNull(byPath["sample.seed"]).message).contains("sample.seed.agents")
    }
}
