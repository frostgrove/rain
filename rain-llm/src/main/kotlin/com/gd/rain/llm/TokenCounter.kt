package com.gd.rain.llm

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.knuddels.jtokkit.api.EncodingType
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator

/**
 * How many tokens a prompt occupies in one model's context window, framing included.
 *
 * A counter is declared per model: by `rain.llm.models.<id>` ([EncodingTokenCounter]) or as an
 * application bean. Without one, [LlmGateway.outputBudget] answers [OutputBudget.NotEvaluated]; no
 * characters-per-token ratio stands in for it.
 */
public interface TokenCounter {
    public val model: String

    public fun count(messages: List<LlmMessage>): Int
}

/**
 * The tokens of every message's content under [encoding], plus [messageOverhead] tokens per message and
 * [promptOverhead] per prompt — the model's chat framing, stated by the deployment.
 */
public class EncodingTokenCounter(
    override val model: String,
    encoding: EncodingType,
    private val messageOverhead: Int,
    private val promptOverhead: Int,
) : TokenCounter {
    private val tokens = JTokkitTokenCountEstimator(encoding)

    init {
        require(messageOverhead >= 0 && promptOverhead >= 0) { "chat framing overheads are not negative" }
    }

    override fun count(messages: List<LlmMessage>): Int =
        Math.toIntExact(
            messages.fold(promptOverhead.toLong()) { total, message ->
                total + messageOverhead +
                    tokens.estimate(message.content)
            },
        )
}

/** Every declared counter, by model. A model with two counters has none that can be trusted. */
public class TokenCounters(
    counters: List<TokenCounter>,
) {
    private val byModel: Map<String, List<TokenCounter>> = counters.groupBy(TokenCounter::model)

    public fun forModel(model: String): TokenCounterLookup {
        val declared = byModel[model] ?: return TokenCounterLookup.Missing
        return if (declared.size == 1) TokenCounterLookup.Found(declared.single()) else TokenCounterLookup.Conflicting(declared.size)
    }

    public fun problems(): List<ConfigurationProblem> =
        byModel.filterValues { it.size > 1 }.toSortedMap().map { (model, declared) ->
            ConfigurationProblem(
                "${LlmProperties.PREFIX}.models.$model",
                ProblemCode.CONTRADICTS,
                "model $model has ${declared.size} token counters: ${declared.joinToString(", ") { it.javaClass.name }}",
            )
        }

    public companion object {
        /** The counters `rain.llm.models` declares, followed by the application's beans. */
        public fun of(
            settings: LlmSettings,
            beans: List<TokenCounter>,
        ): TokenCounters =
            TokenCounters(
                settings.encodings.toSortedMap().map { (model, declared) ->
                    EncodingTokenCounter(
                        model,
                        EncodingType.fromName(checkNotNull(declared.encoding)).orElseThrow(),
                        checkNotNull(declared.messageOverhead),
                        checkNotNull(declared.promptOverhead),
                    )
                } + beans,
            )
    }
}

public sealed interface TokenCounterLookup {
    public data class Found(
        public val counter: TokenCounter,
    ) : TokenCounterLookup

    public data object Missing : TokenCounterLookup

    public data class Conflicting(
        public val counters: Int,
    ) : TokenCounterLookup
}

/** How many answer tokens fit next to a prompt. */
public sealed interface OutputBudget {
    /** The prompt leaves room for all [tokens] asked for. */
    public data class Fits(
        public val tokens: Int,
    ) : OutputBudget

    /** The prompt of [promptTokens] leaves [shortBy] tokens too few for the answer asked for. */
    public data class NoRoom(
        public val promptTokens: Int,
        public val shortBy: Long,
    ) : OutputBudget

    /** The prompt could not be measured. */
    public data class NotEvaluated(
        public val reason: String,
    ) : OutputBudget
}
