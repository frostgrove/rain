package com.gd.rain.llm

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Gap 40: an enabled section states its timeout; there is no 90-second fallback anywhere. */
class MissingTimeoutRefusedTest {
    @Test
    fun `an enabled section without a timeout refuses start-up`() {
        assertThat(llmProblems(ENABLED_SECTION - "rain.llm.timeout").map { it.path to it.code })
            .containsExactly("rain.llm.timeout" to ProblemCode.REQUIRED)
    }

    @Test
    fun `settings built without the validator refuse the same way`() {
        assertThatThrownBy { LlmSettings.of(enabledProperties().copy(timeout = null)) }
            .isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
                assertThat(refusal.problems.map { it.path to it.code }).containsExactly("rain.llm.timeout" to ProblemCode.REQUIRED)
            }
    }

    @Test
    fun `the complete section reports nothing`() {
        assertThat(llmProblems(ENABLED_SECTION)).isEmpty()
    }
}
