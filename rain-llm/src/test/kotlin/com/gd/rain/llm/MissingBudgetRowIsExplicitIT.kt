package com.gd.rain.llm

import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.AdmissionGate
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.BreakerRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Gap 40: a configured pool whose budget row does not exist is an explicit refusal. The old statement
 * found no row, granted nothing and was indistinguishable from a full pool, so an ask waited out its whole
 * budget and reported contention.
 */
@Tag("integration")
class MissingBudgetRowIsExplicitIT {
    @Test
    fun `an ask for a pool with no budget row is refused at once and writes nothing`() {
        val database = LlmSlotsIT.Database("llm_missing_row", provisioned = false)
        val pause = ClockPause(database.clock)

        assertThatThrownBy { database.replica("replica-a", pause).acquire(LlmSlotsIT.POOL, LlmClass.INTERACTIVE, database.soon()) }
            .isInstanceOf(LlmPoolMissing::class.java)
            .hasMessageContaining("rain_llm.llm_budget")

        assertThat(pause.pauses).describedAs("a missing row is not waited on").isEmpty()
        assertThat(database.slotIds()).isEmpty()
        assertThat(database.budgetRows()).isZero()
    }

    @Test
    fun `through the gateway the refusal reaches neither the model nor the breaker`() {
        val database = LlmSlotsIT.Database("llm_missing_gateway", provisioned = false)
        val settings = LlmSettings.of(enabledProperties(pools = mapOf("default" to LlmPoolProperties(bulk = 6, interactive = 8))))
        val container =
            CircuitBreakerRegistry
                .of(CircuitBreakerConfig.custom().slidingWindow(1, 1, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).build())
                .also { it.circuitBreaker(MODEL_BREAKER.value) }
        val breakers = BreakerRegistry(container, listOf(BreakerDeclaration(MODEL_BREAKER, null, Importance.DEGRADING)))
        val model = ScriptedChatModel().answers("Four.")
        val gateway =
            LlmGateway(
                settings,
                database.replica("replica-a"),
                AdmissionGate(breakers),
                breakers,
                { model },
                emptyList(),
                TokenCounters(emptyList()),
                database.clock,
            )

        assertThatThrownBy { gateway.complete("default", LlmClass.BULK, ASK) }.isInstanceOf(LlmPoolMissing::class.java)
        assertThat(model.prompts).isEmpty()
        assertThat(
            container
                .find(MODEL_BREAKER.value)
                .get()
                .metrics.numberOfBufferedCalls,
        ).isZero()

        database.store.ensurePools(settings.pools.keys)
        assertThat(gateway.complete("default", LlmClass.BULK, ASK).text).isEqualTo("Four.")
        assertThat(database.slotIds()).describedAs("the slot was given back").isEmpty()
    }
}
