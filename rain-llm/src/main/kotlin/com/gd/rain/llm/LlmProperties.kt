package com.gd.rain.llm

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import com.gd.rain.resilience.BreakerName
import com.knuddels.jtokkit.api.EncodingType
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `rain.llm` — admission and budgeting around the application's Spring AI `ChatModel` bean.
 *
 * The section is optional; when it is present [enabled] is stated, and when it is enabled every leaf
 * that decides behaviour is stated too. Nothing is inferred: the provider's own transport settings
 * belong to the application's `ChatModel`, and rain reads none of them. The one declared default is
 * [slotPollInterval], a tuning number.
 */
@ConfigurationProperties(LlmProperties.PREFIX)
public data class LlmProperties(
    public val enabled: Boolean,
    /** The model the `ChatModel` is asked for; also the key its token counter is looked up by. */
    public val model: String? = null,
    /** How long one model call may hold a slot: the lease a slot is written with. */
    public val timeout: Duration? = null,
    /** How long one ask may wait for a slot before it is refused. */
    public val callBudget: Duration? = null,
    /** The model's context window in tokens: prompt plus answer. */
    public val contextWindow: Int? = null,
    /** The declared breaker model calls are accounted against. */
    public val breaker: String? = null,
    /** The slot pools, each with its ceiling per request class. */
    public val pools: Map<String, LlmPoolProperties> = emptyMap(),
    /** Token counters declared by encoding, keyed by model. */
    public val models: Map<String, LlmModelProperties> = emptyMap(),
    /** How often a waiting ask tries for a slot again; never longer than the ask has left. */
    public val slotPollInterval: Duration = DEFAULT_SLOT_POLL_INTERVAL,
) {
    /** The one validation of this section: stated values always, required leaves when [enabled]. */
    public fun problems(): List<ConfigurationProblem> =
        problems {
            expect(positive(slotPollInterval), "$PREFIX.slot-poll-interval") { "is ${slotPollInterval.written()}; it has to be positive" }
            timeout?.let { expect(positive(it), "$PREFIX.timeout") { "is ${it.written()}; it has to be positive" } }
            callBudget?.let { expect(positive(it), "$PREFIX.call-budget") { "is ${it.written()}; it has to be positive" } }
            contextWindow?.let { expect(it >= 1, "$PREFIX.context-window") { "is $it; a context window holds at least one token" } }
            model?.let { expect(it.isNotBlank(), "$PREFIX.model") { "is blank" } }
            breaker?.let { expect(BreakerName.isWellFormed(it), "$PREFIX.breaker") { "\"$it\" does not match ${BreakerName.PATTERN}" } }
            pools.toSortedMap().forEach { (name, pool) -> addAll(pool.problems("$PREFIX.pools.$name", name)) }
            models.toSortedMap().forEach { (id, declared) -> addAll(declared.problems("$PREFIX.models.$id")) }

            if (!enabled) return@problems
            expect(model != null, "$PREFIX.model", ProblemCode.REQUIRED) { REQUIRED_WHEN_ENABLED }
            expect(timeout != null, "$PREFIX.timeout", ProblemCode.REQUIRED) { REQUIRED_WHEN_ENABLED }
            expect(callBudget != null, "$PREFIX.call-budget", ProblemCode.REQUIRED) { REQUIRED_WHEN_ENABLED }
            expect(contextWindow != null, "$PREFIX.context-window", ProblemCode.REQUIRED) { REQUIRED_WHEN_ENABLED }
            expect(breaker != null, "$PREFIX.breaker", ProblemCode.REQUIRED) { REQUIRED_WHEN_ENABLED }
            expect(
                pools.isNotEmpty(),
                "$PREFIX.pools",
                ProblemCode.REQUIRED,
            ) { "no pool is configured; an ask names the pool it is admitted to" }
        }

    public companion object {
        public const val PREFIX: String = "rain.llm"
        public const val ENABLED: String = "$PREFIX.enabled"

        /** A pool name: lower case, digits, `_`, `.` and `-`, starting with a letter, at most 64 characters. */
        public const val POOL_PATTERN: String = "^[a-z][a-z0-9_.-]{0,63}$"

        public val DEFAULT_SLOT_POLL_INTERVAL: Duration = Duration.ofMillis(200)

        private val POOL = Regex(POOL_PATTERN)
        private const val REQUIRED_WHEN_ENABLED = "no value is provided; rain.llm is enabled"

        internal fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative

        internal fun isPoolName(name: String): Boolean = POOL.matches(name)
    }
}

/** `rain.llm.pools.<name>` — how many slots of the pool each request class may hold at most. */
public data class LlmPoolProperties(
    public val bulk: Int? = null,
    public val interactive: Int? = null,
) : ConfigurationSection {
    internal fun problems(
        path: String,
        name: String,
    ): List<ConfigurationProblem> =
        problems {
            expect(LlmProperties.isPoolName(name), path) { "pool name \"$name\" does not match ${LlmProperties.POOL_PATTERN}" }
            expect(bulk != null, "$path.bulk", ProblemCode.REQUIRED) { "no value is provided" }
            expect(interactive != null, "$path.interactive", ProblemCode.REQUIRED) { "no value is provided" }
            bulk?.let { expect(it >= 1, "$path.bulk") { "is $it; a ceiling admits at least one call" } }
            interactive?.let { expect(it >= 1, "$path.interactive") { "is $it; a ceiling admits at least one call" } }
            if (bulk != null && interactive != null && bulk >= 1 && interactive >= 1) {
                expect(bulk <= interactive, "$path.bulk", ProblemCode.CONTRADICTS) {
                    "is $bulk, above the interactive ceiling $interactive; interactive work may go at least as deep into a pool as bulk work"
                }
            }
        }
}

/**
 * `rain.llm.models.<id>` — a token counter declared by encoding: the tokens of every message's content
 * under [encoding], plus the model's chat framing stated as [messageOverhead] tokens per message and
 * [promptOverhead] tokens per prompt.
 */
public data class LlmModelProperties(
    /** A tokenizer encoding name: `r50k_base`, `p50k_base`, `p50k_edit`, `cl100k_base` or `o200k_base`. */
    public val encoding: String? = null,
    public val messageOverhead: Int? = null,
    public val promptOverhead: Int? = null,
) : ConfigurationSection {
    internal fun problems(path: String): List<ConfigurationProblem> =
        problems {
            expect(encoding != null, "$path.encoding", ProblemCode.REQUIRED) { "no value is provided" }
            encoding?.let { name ->
                expect(EncodingType.fromName(name).isPresent, "$path.encoding") {
                    "\"$name\" is not one of ${EncodingType.entries.joinToString(", ") { it.getName() }}"
                }
            }
            expect(messageOverhead != null, "$path.message-overhead", ProblemCode.REQUIRED) { "no value is provided" }
            expect(promptOverhead != null, "$path.prompt-overhead", ProblemCode.REQUIRED) { "no value is provided" }
            messageOverhead?.let { expect(it >= 0, "$path.message-overhead") { "is $it; it cannot be negative" } }
            promptOverhead?.let { expect(it >= 0, "$path.prompt-overhead") { "is $it; it cannot be negative" } }
        }
}

/** Declares the `rain.llm` section to rain's configuration validation. */
public class LlmConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(SectionSpec(LlmProperties.PREFIX, LlmProperties::class, Presence.OPTIONAL) { properties, _ -> properties.problems() })
}

/** One pool's ceilings. */
public data class LlmPool(
    public val name: String,
    public val bulk: Int,
    public val interactive: Int,
) {
    public fun ceiling(klass: LlmClass): Int =
        when (klass) {
            LlmClass.BULK -> bulk
            LlmClass.INTERACTIVE -> interactive
        }
}

/** The validated `rain.llm` section of an enabled deployment: every leaf is present. */
public class LlmSettings private constructor(
    public val model: String,
    public val timeout: Duration,
    public val callBudget: Duration,
    public val contextWindow: Int,
    public val breaker: BreakerName,
    public val pools: Map<String, LlmPool>,
    public val encodings: Map<String, LlmModelProperties>,
    public val slotPollInterval: Duration,
) {
    /** The configured pool [name]; an ask naming a pool the deployment does not configure is a programming error. */
    public fun pool(name: String): LlmPool =
        pools[name] ?: throw IllegalArgumentException("no pool \"$name\" is configured under ${LlmProperties.PREFIX}.pools")

    public companion object {
        /** Refuses with every problem [LlmProperties.problems] finds, and refuses a section that is not enabled. */
        public fun of(properties: LlmProperties): LlmSettings {
            val found = properties.problems()
            if (found.isNotEmpty()) throw ConfigurationProblemsException(found)
            check(properties.enabled) { "${LlmProperties.ENABLED} is false; there are no settings to use" }
            return LlmSettings(
                model = checkNotNull(properties.model),
                timeout = checkNotNull(properties.timeout),
                callBudget = checkNotNull(properties.callBudget),
                contextWindow = checkNotNull(properties.contextWindow),
                breaker = BreakerName(checkNotNull(properties.breaker)),
                pools =
                    properties.pools.mapValues { (name, pool) ->
                        LlmPool(name, checkNotNull(pool.bulk), checkNotNull(pool.interactive))
                    },
                encodings = properties.models,
                slotPollInterval = properties.slotPollInterval,
            )
        }
    }
}
