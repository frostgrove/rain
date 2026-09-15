package com.gd.rain.core.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ConfigurationProblemTest {
    @Test
    fun `one problem is summarised in the singular`() {
        val rendered =
            ConfigurationProblemsException.render(
                listOf(ConfigurationProblem("rain.web.body-limit", ProblemCode.INVALID, "is zero")),
            )

        assertThat(rendered).isEqualTo("the configuration has 1 problem:\n  - rain.web.body-limit [invalid]: is zero")
    }

    @Test
    fun `several problems are listed in the order they were found, with their source`() {
        val rendered =
            ConfigurationProblemsException.render(
                listOf(
                    ConfigurationProblem("rain.deployment.stage", ProblemCode.REQUIRED, "no value is provided"),
                    ConfigurationProblem("rain.jobs.workers.x", ProblemCode.UNKNOWN_KEY, "no definition", source = "application.yml"),
                ),
            )

        assertThat(rendered).isEqualTo(
            "the configuration has 2 problems:\n" +
                "  - rain.deployment.stage [required]: no value is provided\n" +
                "  - rain.jobs.workers.x [unknown_key]: no definition (from application.yml)",
        )
    }

    @Test
    fun `a refusal names at least one problem`() {
        assertThatThrownBy { ConfigurationProblemsException(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only a rule that could not be evaluated is not fatal`() {
        assertThat(ProblemCode.entries.filterNot { it.fatal }).containsExactly(ProblemCode.NOT_EVALUATED)
    }

    @Test
    fun `the collector records exactly the expectations that do not hold, in order`() {
        val found =
            problems {
                expect(true, "rain.a") { "never evaluated" }
                expect(false, "rain.b", ProblemCode.REQUIRED) { "b is missing" }
                expect(false, "rain.c") { "c is wrong" }
            }

        assertThat(found).containsExactly(
            ConfigurationProblem("rain.b", ProblemCode.REQUIRED, "b is missing"),
            ConfigurationProblem("rain.c", ProblemCode.INVALID, "c is wrong"),
        )
    }

    @Test
    fun `the message of a passing expectation is never built`() {
        var built = false

        problems { expect(true, "rain.a") { "x".also { built = true } } }

        assertThat(built).isFalse()
    }
}
