package com.gd.rain.llm

import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** Gap 40: one validator; explicit `enabled`; every required leaf reported in one pass; ceilings refused, never clamped. */
class LlmPropertiesTest {
    @Test
    fun `an application without the section reports nothing`() {
        assertThat(llmProblems(emptyMap())).isEmpty()
    }

    @Test
    fun `a stated section states whether it is enabled`() {
        assertThat(llmProblems(mapOf("rain.llm.model" to "m")).map { it.path to it.code })
            .containsExactly("rain.llm.enabled" to ProblemCode.REQUIRED)
    }

    @Test
    fun `a disabled section needs nothing else`() {
        assertThat(llmProblems(mapOf("rain.llm.enabled" to "false"))).isEmpty()
    }

    @Test
    fun `an enabled section reports every missing leaf at once`() {
        assertThat(llmProblems(mapOf("rain.llm.enabled" to "true")).map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.llm.model" to ProblemCode.REQUIRED,
            "rain.llm.timeout" to ProblemCode.REQUIRED,
            "rain.llm.call-budget" to ProblemCode.REQUIRED,
            "rain.llm.context-window" to ProblemCode.REQUIRED,
            "rain.llm.breaker" to ProblemCode.REQUIRED,
            "rain.llm.pools" to ProblemCode.REQUIRED,
        )
    }

    @Test
    fun `ceilings are refused as written, not clamped`() {
        val problems =
            llmProblems(
                ENABLED_SECTION +
                    mapOf(
                        "rain.llm.pools.default.bulk" to "5",
                        "rain.llm.pools.default.interactive" to "3",
                        "rain.llm.pools.batch.bulk" to "0",
                        "rain.llm.pools.batch.interactive" to "1",
                        "rain.llm.pools.lonely.bulk" to "1",
                    ),
            )

        assertThat(problems.map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.llm.pools.default.bulk" to ProblemCode.CONTRADICTS,
            "rain.llm.pools.batch.bulk" to ProblemCode.INVALID,
            "rain.llm.pools.lonely.interactive" to ProblemCode.REQUIRED,
        )
    }

    @Test
    fun `a malformed pool name, breaker name, context window and poll interval are refused`() {
        val properties =
            enabledProperties(
                pools = mapOf("Default Pool" to LlmPoolProperties(1, 1)),
                contextWindow = 0,
                slotPollInterval = Duration.ZERO,
            ).copy(breaker = "Model")

        assertThat(properties.problems().map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.llm.slot-poll-interval" to ProblemCode.INVALID,
            "rain.llm.context-window" to ProblemCode.INVALID,
            "rain.llm.breaker" to ProblemCode.INVALID,
            "rain.llm.pools.Default Pool" to ProblemCode.INVALID,
        )
    }

    @Test
    fun `a model counter states its encoding and both overheads`() {
        val problems =
            llmProblems(
                ENABLED_SECTION +
                    mapOf(
                        "rain.llm.models.test-model.encoding" to "letters_base",
                        "rain.llm.models.other.message-overhead" to "-1",
                    ),
            )

        assertThat(problems.map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.llm.models.other.encoding" to ProblemCode.REQUIRED,
            "rain.llm.models.other.message-overhead" to ProblemCode.INVALID,
            "rain.llm.models.other.prompt-overhead" to ProblemCode.REQUIRED,
            "rain.llm.models.test-model.encoding" to ProblemCode.INVALID,
            "rain.llm.models.test-model.message-overhead" to ProblemCode.REQUIRED,
            "rain.llm.models.test-model.prompt-overhead" to ProblemCode.REQUIRED,
        )
    }

    @Test
    fun `a misspelt key is reported`() {
        assertThat(llmProblems(ENABLED_SECTION + ("rain.llm.call-budjet" to "1s")).map { it.path to it.code })
            .containsExactly("rain.llm.call-budjet" to ProblemCode.UNKNOWN_KEY)
    }

    @Test
    fun `settings carry the stated values and the pools' ceilings per class`() {
        val settings = LlmSettings.of(enabledProperties(pools = mapOf("default" to LlmPoolProperties(bulk = 6, interactive = 8))))

        assertThat(settings.pool("default").ceiling(LlmClass.BULK)).isEqualTo(6)
        assertThat(settings.pool("default").ceiling(LlmClass.INTERACTIVE)).isEqualTo(8)
        assertThat(settings.timeout).isEqualTo(Duration.ofSeconds(30))
        assertThat(settings.breaker).isEqualTo(MODEL_BREAKER)
    }
}
