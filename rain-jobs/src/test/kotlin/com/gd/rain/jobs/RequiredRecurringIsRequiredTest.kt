package com.gd.rain.jobs

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * Gap 9: `rain.jobs.required-recurring` has no default. A deployment that does not say which recurring work it runs is
 * refused; one that runs none says so with an empty list.
 */
class RequiredRecurringIsRequiredTest {
    private fun problems(environment: MockEnvironment) =
        RainConfigurationValidator
            .validate(environment, listOf(JobsConfigurationContributor()), emptyList())
            .problems
            .filter { it.path.startsWith("rain.jobs") }

    private fun base(): MockEnvironment =
        MockEnvironment()
            .withProperty("rain.deployment.stage", "test")
            .withProperty("rain.jobs.workers.tickets.summarize", "1")
            .withProperty("rain.jobs.drain-grace", "20s")
            .withProperty("rain.jobs.reserved-connections", "5")

    @Test
    fun `an unstated list is a required leaf`() {
        assertThat(problems(base()).map { it.path to it.code }).containsExactly("rain.jobs.required-recurring" to ProblemCode.REQUIRED)
    }

    @Test
    fun `an explicitly empty list is a statement`() {
        assertThat(problems(base().withProperty("rain.jobs.required-recurring", ""))).isEmpty()
    }
}
