package com.gd.rain.llm

import com.gd.rain.core.error.FaultKind
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.prompt.ChatOptions
import java.time.Duration

/** The gateway speaks Spring AI's vocabulary to the model and its own to the caller. */
class LlmGatewayTest {
    @Test
    fun `messages, model, temperature and output ceiling reach the model, and the answer comes back`() {
        val fixture = GatewayFixture()
        fixture.model.answers("Four.", finishReason = "stop", promptTokens = 21, completionTokens = 2)

        val answer =
            fixture.gateway.complete(
                "default",
                LlmClass.INTERACTIVE,
                LlmRequest(
                    listOf(LlmMessage.system("Answer briefly."), LlmMessage.user("Two plus two?"), LlmMessage.assistant("Thinking.")),
                    temperature = 0.2,
                    maxOutputTokens = 64,
                ),
            )

        assertThat(answer).isEqualTo(LlmResponse("Four.", "stop", LlmUsage(21, 2)))
        val prompt = fixture.model.prompts.single()
        assertThat(prompt.instructions.map { it.messageType to it.text }).containsExactly(
            MessageType.SYSTEM to "Answer briefly.",
            MessageType.USER to "Two plus two?",
            MessageType.ASSISTANT to "Thinking.",
        )
        val options = checkNotNull(prompt.options)
        assertThat(options.model).isEqualTo("test-model")
        assertThat(options.temperature).isEqualTo(0.2)
        assertThat(options.maxTokens).isEqualTo(64)
    }

    @Test
    fun `options the request does not state are not sent, and unreported usage stays unknown`() {
        val fixture = GatewayFixture()
        fixture.model.answers("Four.")

        val answer = fixture.gateway.complete("default", LlmClass.BULK, ASK)

        assertThat(answer.usage).isNull()
        val options =
            checkNotNull(
                fixture.model.prompts
                    .single()
                    .options,
            )
        assertThat(options.temperature).isNull()
        assertThat(options.maxTokens).isNull()
    }

    @Test
    fun `customizers shape the options in bean order`() {
        val seen = mutableListOf<String>()
        val fixture =
            GatewayFixture(
                customizers =
                    listOf(
                        LlmRequestCustomizer { _, options ->
                            seen += "first:${options.model}"
                            options.mutate().model("provider-model").build()
                        },
                        LlmRequestCustomizer { request, options ->
                            seen += "second:${options.model}:${request.messages.size}"
                            ChatOptions
                                .builder()
                                .model(options.model)
                                .topP(0.5)
                                .build()
                        },
                    ),
            )
        fixture.model.answers("Four.")

        fixture.gateway.complete("default", LlmClass.BULK, ASK)

        assertThat(seen).containsExactly("first:test-model", "second:provider-model:2")
        assertThat(
            checkNotNull(
                fixture.model.prompts
                    .single()
                    .options,
            ).topP,
        ).isEqualTo(0.5)
    }

    @Test
    fun `the slot is given back after an answer and after a failure`() {
        val fixture = GatewayFixture()
        fixture.model.answers("Four.").fails(IllegalStateException("reset by peer"))

        fixture.gateway.complete("default", LlmClass.BULK, ASK)
        assertThat(fixture.store.held()).isZero()

        assertThatThrownBy { fixture.gateway.complete("default", LlmClass.BULK, ASK) }
            .isInstanceOf(LlmCallFailed::class.java)
            .hasRootCauseMessage("reset by peer")
        assertThat(fixture.store.held()).isZero()
    }

    @Test
    fun `an open breaker refuses with the wait, and the refusal is a retryable fault`() {
        val fixture = GatewayFixture(failuresToOpen = 1)
        fixture.model.fails(IllegalStateException("down"))
        assertThatThrownBy { fixture.gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmCallFailed::class.java)
        fixture.clock.advance(Duration.ofSeconds(15))

        val refusal = runCatching { fixture.gateway.complete("default", LlmClass.BULK, ASK) }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(LlmBreakerHeld::class.java)
        assertThat((refusal as LlmBreakerHeld).retryAfter).isEqualTo(Duration.ofSeconds(45))
        val fault = LlmFaultTranslator.translate(refusal)
        assertThat(fault?.kind).isEqualTo(FaultKind.RETRYABLE)
        assertThat(fault?.code).isEqualTo(RainLlmErrorCodes.LLM_UNAVAILABLE)
        assertThat(fault?.retryAfter).isEqualTo(Duration.ofSeconds(45))
    }

    @Test
    fun `a rested breaker lets one probe reach the model, and its answer closes the breaker`() {
        val fixture = GatewayFixture(failuresToOpen = 1)
        fixture.model.fails(IllegalStateException("down")).answers("Back.")
        assertThatThrownBy { fixture.gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmCallFailed::class.java)
        fixture.clock.advance(Duration.ofMinutes(1).plusMillis(1))

        assertThat(fixture.gateway.complete("default", LlmClass.BULK, ASK).text).isEqualTo("Back.")
        assertThat(fixture.breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `the translator maps each refusal and leaves internal failures to the transport`() {
        assertThat(LlmFaultTranslator.translate(LlmBudgetExhausted("default", LlmClass.BULK))?.code).isEqualTo(RainLlmErrorCodes.LLM_BUSY)
        assertThat(LlmFaultTranslator.translate(LlmEmptyAnswer("empty"))?.code).isEqualTo(RainLlmErrorCodes.LLM_BAD_ANSWER)
        assertThat(LlmFaultTranslator.translate(LlmBreakerHeld(MODEL_BREAKER, null))?.retryAfter).isNull()
        assertThat(LlmFaultTranslator.translate(LlmPoolMissing("default"))).isNull()
        assertThat(LlmFaultTranslator.translate(IllegalStateException("other"))).isNull()
    }
}
