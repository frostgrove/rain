package com.gd.rain.llm

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** Gap 40: a call budget that is not positive is refused, never read as "no budget" or raised to a floor. */
class ZeroCallBudgetRefusedTest {
    @Test
    fun `a zero or negative call budget is refused`() {
        listOf("0s", "-5s").forEach { written ->
            assertThat(llmProblems(ENABLED_SECTION + ("rain.llm.call-budget" to written)).map { it.path to it.code })
                .describedAs(written)
                .containsExactly("rain.llm.call-budget" to ProblemCode.INVALID)
        }
    }

    @Test
    fun `a missing call budget is required, not derived from the timeout`() {
        assertThat(llmProblems(ENABLED_SECTION - "rain.llm.call-budget").map { it.path to it.code })
            .containsExactly("rain.llm.call-budget" to ProblemCode.REQUIRED)
    }

    @Test
    fun `settings refuse a zero budget too`() {
        assertThatThrownBy { LlmSettings.of(enabledProperties(callBudget = Duration.ZERO)) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .hasMessageContaining("rain.llm.call-budget")
    }

    @Test
    fun `the wait for a slot never outlasts the call budget`() {
        val fixture = GatewayFixture(enabledProperties(callBudget = Duration.ofSeconds(1), slotPollInterval = Duration.ofMillis(300)))
        fixture.store.occupy("default", 2)

        assertThatThrownBy { fixture.gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmBudgetExhausted::class.java)

        assertThat(fixture.pause.pauses).containsExactly(
            Duration.ofMillis(300),
            Duration.ofMillis(300),
            Duration.ofMillis(300),
            Duration.ofMillis(100),
        )
        assertThat(fixture.clock.instant()).isEqualTo(START.plusSeconds(1))
    }
}
