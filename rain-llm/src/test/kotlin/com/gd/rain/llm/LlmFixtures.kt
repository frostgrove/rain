package com.gd.rain.llm

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.AdmissionGate
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.BreakerName
import com.gd.rain.resilience.BreakerRegistry
import com.gd.rain.test.MutableClock
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

val START: Instant = Instant.parse("2026-09-15T12:00:00Z")
val MODEL_BREAKER = BreakerName("model")
val ASK = LlmRequest(listOf(LlmMessage.system("Answer briefly."), LlmMessage.user("What is two plus two?")))

fun enabledProperties(
    pools: Map<String, LlmPoolProperties> = mapOf("default" to LlmPoolProperties(bulk = 1, interactive = 2)),
    models: Map<String, LlmModelProperties> = emptyMap(),
    contextWindow: Int = 8192,
    callBudget: Duration = Duration.ofSeconds(1),
    slotPollInterval: Duration = Duration.ofMillis(300),
): LlmProperties =
    LlmProperties(
        enabled = true,
        model = "test-model",
        timeout = Duration.ofSeconds(30),
        callBudget = callBudget,
        contextWindow = contextWindow,
        breaker = MODEL_BREAKER.value,
        pools = pools,
        models = models,
        slotPollInterval = slotPollInterval,
    )

/** The properties of a complete, enabled section, as a deployment writes them. */
val ENABLED_SECTION: Map<String, String> =
    mapOf(
        "rain.llm.enabled" to "true",
        "rain.llm.model" to "test-model",
        "rain.llm.timeout" to "30s",
        "rain.llm.call-budget" to "10s",
        "rain.llm.context-window" to "8192",
        "rain.llm.breaker" to "model",
        "rain.llm.pools.default.bulk" to "1",
        "rain.llm.pools.default.interactive" to "2",
    )

/** Every problem the validator reports under `rain.llm` for [properties], with a valid runtime selection around them. */
fun llmProblems(properties: Map<String, String>): List<ConfigurationProblem> {
    val environment = StandardEnvironment()
    environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
    environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
    val values = mapOf("spring.application.name" to "sample", "rain.runtime.roles" to "api", "rain.deployment.stage" to "test") + properties
    environment.propertySources.addFirst(MapPropertySource("test", HashMap<String, Any>(values)))
    return RainConfigurationValidator
        .validate(environment, listOf(LlmConfigurationContributor()), emptyList())
        .problems
        .filter { it.path.startsWith(LlmProperties.PREFIX) }
}

/** Slots in memory: a pool admits up to its ceiling; a pool the store was not given has no budget row. */
class MemorySlotStore(
    vararg pools: String,
) : LlmSlotStore {
    private val present = pools.toMutableSet()
    private val held = linkedMapOf<UUID, String>()
    val attempts = AtomicInteger()
    val ensured = CopyOnWriteArrayList<List<String>>()

    @Synchronized
    override fun tryAcquire(
        pool: String,
        ceiling: Int,
        id: UUID,
        holder: String,
        lease: Duration,
    ): SlotGrant {
        attempts.incrementAndGet()
        return when {
            pool !in present -> SlotGrant.PoolMissing
            held.values.count { it == pool } >= ceiling -> SlotGrant.Full
            else -> SlotGrant.Granted.also { held[id] = pool }
        }
    }

    @Synchronized
    override fun release(id: UUID) {
        held.remove(id)
    }

    @Synchronized
    override fun ensurePools(pools: Collection<String>) {
        ensured += pools.sorted()
        present += pools
    }

    @Synchronized
    fun held(): Int = held.size

    /** Slots held by someone else; the ids are how that someone gives them back. */
    @Synchronized
    fun occupy(
        pool: String,
        slots: Int,
    ): List<UUID> = List(slots) { UUID.randomUUID().also { held[it] = pool } }
}

/** A pause that moves the clock instead of sleeping, runs [onPause] with the pause's ordinal, and remembers every pause. */
class ClockPause(
    private val clock: MutableClock,
    private val onPause: (Int) -> Unit = {},
) : SlotPause {
    val pauses = CopyOnWriteArrayList<Duration>()

    override fun pause(duration: Duration) {
        pauses += duration
        onPause(pauses.size)
        clock.advance(duration)
    }
}

/** A counter that always answers [tokens]. */
class FixedTokenCounter(
    override val model: String,
    private val tokens: Int,
) : TokenCounter {
    override fun count(messages: List<LlmMessage>): Int = tokens
}

/** A gateway over an in-memory slot store, a scripted model and a breaker that opens after [failuresToOpen] failures. */
class GatewayFixture(
    properties: LlmProperties = enabledProperties(),
    failuresToOpen: Int = 2,
    val store: MemorySlotStore = MemorySlotStore("default"),
    counters: List<TokenCounter> = emptyList(),
    customizers: List<LlmRequestCustomizer> = emptyList(),
) {
    val clock = MutableClock(START)
    val pause = ClockPause(clock)
    val model = ScriptedChatModel()
    val settings = LlmSettings.of(properties)
    val container: CircuitBreakerRegistry =
        CircuitBreakerRegistry
            .of(
                CircuitBreakerConfig
                    .custom()
                    .slidingWindow(failuresToOpen, failuresToOpen, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .failureRateThreshold(100f)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .clock(clock)
                    .build(),
            ).also { it.circuitBreaker(MODEL_BREAKER.value) }
    val breakers = BreakerRegistry(container, listOf(BreakerDeclaration(MODEL_BREAKER, "model_unavailable", Importance.DEGRADING)))
    val slots = LlmSlots(store, settings.timeout, settings.slotPollInterval, clock, { UUID.randomUUID() }, "test/1", pause)
    val gateway =
        LlmGateway(settings, slots, AdmissionGate(breakers), breakers, { model }, customizers, TokenCounters.of(settings, counters), clock)

    val breaker: CircuitBreaker get() = container.find(MODEL_BREAKER.value).get()
}
