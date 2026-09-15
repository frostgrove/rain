package com.gd.rain.llm

import com.knuddels.jtokkit.api.EncodingType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Gap 39: the output budget comes from a declared token counter, and a prompt that leaves no room is
 * told so. The old budget divided characters by two and then returned a floor of 256 tokens whether the
 * window had them or not.
 */
class NoRoomIsNotFloorTest {
    @Test
    fun `a prompt that leaves room gets exactly what was asked for`() {
        val fixture = GatewayFixture(enabledProperties(contextWindow = 1000), counters = listOf(FixedTokenCounter("test-model", 900)))

        assertThat(fixture.gateway.outputBudget(ASK.messages, 100)).isEqualTo(OutputBudget.Fits(100))
    }

    @Test
    fun `a prompt that leaves too little room is told how many tokens short it is, not handed a floor`() {
        val fixture = GatewayFixture(enabledProperties(contextWindow = 1000), counters = listOf(FixedTokenCounter("test-model", 950)))

        assertThat(fixture.gateway.outputBudget(ASK.messages, 300)).isEqualTo(OutputBudget.NoRoom(promptTokens = 950, shortBy = 250))
    }

    @Test
    fun `a prompt larger than the window is short by more than the answer`() {
        val fixture = GatewayFixture(enabledProperties(contextWindow = 1000), counters = listOf(FixedTokenCounter("test-model", 1200)))

        assertThat(fixture.gateway.outputBudget(ASK.messages, 100)).isEqualTo(OutputBudget.NoRoom(promptTokens = 1200, shortBy = 300))
    }

    @Test
    fun `without a counter for the model the budget is not evaluated`() {
        val fixture = GatewayFixture(counters = listOf(FixedTokenCounter("another-model", 1)))

        assertThat(fixture.gateway.outputBudget(ASK.messages, 100)).isInstanceOf(OutputBudget.NotEvaluated::class.java)
    }

    @Test
    fun `two counters for the model are not a choice the gateway makes`() {
        val fixture =
            GatewayFixture(
                enabledProperties(models = mapOf("test-model" to LlmModelProperties("cl100k_base", 3, 3))),
                counters = listOf(FixedTokenCounter("test-model", 1)),
            )

        assertThat(
            fixture.gateway.outputBudget(ASK.messages, 100),
        ).isEqualTo(OutputBudget.NotEvaluated("model test-model has 2 token counters"))
    }

    @Test
    fun `an encoding counter adds the declared framing to the content's tokens`() {
        val counter = EncodingTokenCounter("test-model", EncodingType.CL100K_BASE, messageOverhead = 4, promptOverhead = 3)

        assertThat(counter.count(listOf(LlmMessage.user("hello world")))).isEqualTo(2 + 4 + 3)
        assertThat(counter.count(listOf(LlmMessage.system("hello world"), LlmMessage.user("hello world")))).isEqualTo(2 * (2 + 4) + 3)
    }

    @Test
    fun `a counter declared by configuration measures the prompt`() {
        val fixture =
            GatewayFixture(enabledProperties(contextWindow = 20, models = mapOf("test-model" to LlmModelProperties("cl100k_base", 4, 3))))

        assertThat(fixture.gateway.outputBudget(listOf(LlmMessage.user("hello world")), 11)).isEqualTo(OutputBudget.Fits(11))
        assertThat(fixture.gateway.outputBudget(listOf(LlmMessage.user("hello world")), 12)).isEqualTo(OutputBudget.NoRoom(9, 1))
    }
}
