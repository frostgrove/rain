package com.gd.rain.llm

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.resilience.BreakerName
import java.time.Duration

/** Why an ask of the model did not produce an answer. Each case is distinct; none is a guess about another. */
public sealed class LlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Every slot of the class stayed taken for the whole call budget. Not a model failure: the breaker is not told. */
public class LlmBudgetExhausted(
    public val pool: String,
    public val klass: LlmClass,
) : LlmException("llm: every ${klass.name.lowercase()} slot of pool \"$pool\" stayed taken for the whole call budget")

/** The pool is configured but its `rain_llm.llm_budget` row does not exist. Not a model failure. */
public class LlmPoolMissing(
    public val pool: String,
) : LlmException(
        "llm: pool \"$pool\" is configured but rain_llm.llm_budget has no row for it; rows are created when a process with a role starts",
    )

/** The breaker withholds model calls; [retryAfter] is null when it was forced open with no scheduled end. */
public class LlmBreakerHeld(
    public val breaker: BreakerName,
    public val retryAfter: Duration?,
) : LlmException("llm: breaker $breaker withholds model calls" + (retryAfter?.let { " for $it" } ?: " until it is released"))

/** `ChatModel.call` failed. The only failure the breaker is told about. */
public class LlmCallFailed(
    public val breaker: BreakerName,
    cause: RuntimeException,
) : LlmException("llm: the model call failed: ${cause.message ?: cause.javaClass.name}", cause)

/** The model answered, and the answer holds no usable text. The dependency is up, so the breaker records a success. */
public class LlmEmptyAnswer(
    detail: String,
) : LlmException("llm: the model answered without usable text: $detail")

/** The thread was interrupted while it waited for a slot. */
public class LlmInterrupted(
    cause: InterruptedException,
) : LlmException("llm: waiting for a slot was interrupted", cause)

/** The codes rain-llm declares. */
public object RainLlmErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-llm"

    public val LLM_BUSY: ErrorCode = ErrorCode.of("llm_busy", "the model is busy; try again")
    public val LLM_UNAVAILABLE: ErrorCode = ErrorCode.of("llm_unavailable", "the model is not answering; try again later")
    public val LLM_BAD_ANSWER: ErrorCode = ErrorCode.of("llm_bad_answer", "the model's answer could not be used; try again")

    override val codes: List<ErrorCode> = listOf(LLM_BUSY, LLM_UNAVAILABLE, LLM_BAD_ANSWER)
}

/** Refusals a client may retry become retryable faults; a missing pool row or an interrupt stays an internal failure. */
public object LlmFaultTranslator : FaultTranslator {
    override fun translate(failure: Throwable): Fault? =
        when (failure) {
            is LlmBudgetExhausted -> {
                Fault.retryable(RainLlmErrorCodes.LLM_BUSY)
            }

            is LlmBreakerHeld -> {
                Fault.retryable(
                    RainLlmErrorCodes.LLM_UNAVAILABLE,
                    retryAfter = failure.retryAfter?.takeIf(LlmProperties::positive),
                )
            }

            is LlmCallFailed -> {
                Fault.retryable(RainLlmErrorCodes.LLM_UNAVAILABLE)
            }

            is LlmEmptyAnswer -> {
                Fault.retryable(RainLlmErrorCodes.LLM_BAD_ANSWER)
            }

            else -> {
                null
            }
        }
}
