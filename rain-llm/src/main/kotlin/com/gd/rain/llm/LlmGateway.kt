package com.gd.rain.llm

import com.gd.rain.resilience.AdmissionGate
import com.gd.rain.resilience.AdmissionState
import com.gd.rain.resilience.BreakerRegistry
import com.gd.rain.resilience.Reservation
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import java.time.Clock

/**
 * Asks the application's Spring AI [ChatModel], with cluster-wide admission and breaker accounting.
 *
 * Transport, provider retries, authentication and request encoding belong to Spring AI. An ask goes:
 *
 * 1. the prompt is built (customizers included) — a failure here touches neither a slot nor the breaker;
 * 2. a breaker that reads as withholding refuses at once, before a slot is taken;
 * 3. a slot of the pool and class is taken, waiting at most the call budget;
 * 4. the breaker's permission is reserved, atomically;
 * 5. `ChatModel.call` runs — **its failure is the only one the breaker is told about**; an answer, even an
 *    unusable one, is a success for the breaker.
 */
public class LlmGateway(
    private val settings: LlmSettings,
    private val slots: LlmSlots,
    private val gate: AdmissionGate,
    private val breakers: BreakerRegistry,
    private val model: () -> ChatModel,
    customizers: List<LlmRequestCustomizer>,
    private val counters: TokenCounters,
    private val clock: Clock,
) {
    private val prompts = LlmPrompts(settings.model, customizers)

    public fun complete(
        pool: String,
        klass: LlmClass,
        request: LlmRequest,
    ): LlmResponse {
        val admittedTo = settings.pool(pool)
        val prompt = prompts.prompt(request)
        when (val admission = gate.admission(settings.breaker)) {
            is AdmissionState.Held -> throw LlmBreakerHeld(settings.breaker, admission.retryAfter)
            AdmissionState.ForcedOpen -> throw LlmBreakerHeld(settings.breaker, null)
            AdmissionState.Unrestricted, AdmissionState.Probing -> Unit
        }
        val chat = model()
        val deadline = clock.instant().plus(settings.callBudget)
        slots.acquire(admittedTo, klass, deadline).use {
            val permit =
                when (val reservation = gate.reserve(settings.breaker)) {
                    is Reservation.Admitted -> reservation
                    is Reservation.Held -> throw LlmBreakerHeld(settings.breaker, reservation.retryAfter)
                    Reservation.ForcedOpen -> throw LlmBreakerHeld(settings.breaker, null)
                }
            permit.use {
                val answer: ChatResponse =
                    try {
                        chat.call(prompt)
                    } catch (failure: RuntimeException) {
                        breakers.failed(permit, failure)
                        throw LlmCallFailed(settings.breaker, failure)
                    }
                breakers.succeeded(permit)
                return prompts.answer(answer)
            }
        }
    }

    /**
     * How many answer tokens fit next to [messages] in the model's context window, when [want] are asked
     * for: all of them, or exactly how many too few — never a floor handed out without room.
     */
    public fun outputBudget(
        messages: List<LlmMessage>,
        want: Int,
    ): OutputBudget {
        require(want >= 1) { "an answer is asked for at least one token, got $want" }
        val counter =
            when (val lookup = counters.forModel(settings.model)) {
                is TokenCounterLookup.Found -> lookup.counter

                TokenCounterLookup.Missing -> return OutputBudget.NotEvaluated("no token counter is declared for model ${settings.model}")

                is TokenCounterLookup.Conflicting -> return OutputBudget.NotEvaluated(
                    "model ${settings.model} has ${lookup.counters} token counters",
                )
            }
        val promptTokens = counter.count(messages)
        val room = settings.contextWindow.toLong() - promptTokens
        return if (room >= want) OutputBudget.Fits(want) else OutputBudget.NoRoom(promptTokens, want - room)
    }
}
