package com.gd.rain.llm

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Gap 38: only a failure of `ChatModel.call` counts against the breaker. The old gateway counted every
 * runtime exception inside its try — slot contention first among them — so a busy pool opened the breaker
 * on a model that was answering.
 */
class SlotContentionKeepsBreakerClosedTest {
    @Test
    fun `slot contention, however often, leaves the breaker closed and unrecorded`() {
        val fixture = GatewayFixture(failuresToOpen = 2)
        fixture.store.occupy("default", 2)

        repeat(5) {
            assertThatThrownBy { fixture.gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmBudgetExhausted::class.java)
        }

        assertThat(fixture.breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(fixture.breaker.metrics.numberOfBufferedCalls).isZero()
        assertThat(fixture.model.prompts).isEmpty()
    }

    @Test
    fun `a pool without a budget row, an unconfigured pool and a failing customizer leave the breaker unrecorded`() {
        val missingRow = GatewayFixture(store = MemorySlotStore())
        repeat(3) {
            assertThatThrownBy { missingRow.gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmPoolMissing::class.java)
        }

        val unconfigured = GatewayFixture()
        assertThatThrownBy {
            unconfigured.gateway.complete(
                "elsewhere",
                LlmClass.BULK,
                ASK,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        val broken = GatewayFixture(customizers = listOf(LlmRequestCustomizer { _, _ -> error("the provider options could not be built") }))
        repeat(3) {
            assertThatThrownBy {
                broken.gateway.complete(
                    "default",
                    LlmClass.BULK,
                    ASK,
                )
            }.hasMessage("the provider options could not be built")
        }

        listOf(missingRow, unconfigured, broken).forEach { fixture ->
            assertThat(fixture.breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
            assertThat(fixture.breaker.metrics.numberOfBufferedCalls).isZero()
        }
        assertThat(broken.store.attempts.get()).describedAs("a prompt that cannot be built takes no slot").isZero()
    }

    @Test
    fun `model call failures are what open the breaker, and an open breaker takes no slot`() {
        val fixture = GatewayFixture(failuresToOpen = 2)
        fixture.model.fails(IllegalStateException("connection refused")).fails(IllegalStateException("connection refused"))

        repeat(2) {
            assertThatThrownBy { fixture.gateway.complete("default", LlmClass.INTERACTIVE, ASK) }.isInstanceOf(LlmCallFailed::class.java)
        }
        assertThat(fixture.breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        val attempts = fixture.store.attempts.get()

        assertThatThrownBy { fixture.gateway.complete("default", LlmClass.INTERACTIVE, ASK) }.isInstanceOf(LlmBreakerHeld::class.java)
        assertThat(fixture.store.attempts.get()).isEqualTo(attempts)
        assertThat(fixture.store.held()).describedAs("every slot was given back").isZero()
    }

    @Test
    fun `an unusable answer is still an answer, so the breaker records a success`() {
        val fixture = GatewayFixture(failuresToOpen = 1)
        fixture.model.answers("   ")

        assertThatThrownBy { fixture.gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmEmptyAnswer::class.java)

        assertThat(fixture.breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(fixture.breaker.metrics.numberOfSuccessfulCalls).isEqualTo(1)
    }
}
