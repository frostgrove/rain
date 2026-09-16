package com.gd.rain.sample

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder

/** A deployment that states nothing is refused by one start, naming the missing values of every module it uses. */
@Tag("integration")
class ConfigRefusalAcrossModulesE2E {
    @Test
    fun `an empty deployment is refused once, naming every module's missing values`() {
        val failure =
            catchThrowable {
                SpringApplicationBuilder(SampleApplication::class.java)
                    .logStartupInfo(false)
                    .run("--spring.config.location=classpath:/refusal/nothing-stated.yml")
                    .close()
            }

        val refusals = generateSequence(failure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().toList()
        assertThat(refusals).hasSize(1)
        val byPath = refusals.single().problems.associateBy { it.path }
        assertThat(byPath.keys).containsExactlyInAnyOrder(
            "rain.deployment.stage",
            "rain.runtime",
            "rain.web",
            "rain.persistence",
            "rain.jobs",
            "rain.access",
            "sample.tickets",
            "sample.seed",
        )
        assertThat(byPath.values.map { it.code }.distinct()).containsExactly(ProblemCode.REQUIRED)
        mapOf(
            "rain.web" to listOf("rain.web.body-limit", "rain.web.request-budget", "rain.web.client-address"),
            "rain.persistence" to listOf("rain.persistence.statement-timeout"),
            "rain.jobs" to listOf("rain.jobs.required-recurring", "rain.jobs.drain-grace", "rain.jobs.reserved-connections"),
            "rain.access" to listOf("rain.access.web.base-path", "rain.access.token.signing-key", "rain.access.attempts.store"),
            "sample.tickets" to listOf("sample.tickets.pages.max-limit", "sample.tickets.events.stream-for", "sample.tickets.summary.pool"),
            "sample.seed" to listOf("sample.seed.agents"),
        ).forEach { (section, leaves) ->
            assertThat(checkNotNull(byPath[section]).message).describedAs(section).contains(leaves)
        }
    }
}
