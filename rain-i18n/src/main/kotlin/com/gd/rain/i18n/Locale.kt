package com.gd.rain.i18n

import com.ibm.icu.util.LocaleMatcher
import com.ibm.icu.util.ULocale
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** A canonical BCP-47 language tag, never a JVM default locale. */
@JvmInline
public value class LocaleTag private constructor(
    public val value: String,
) : Comparable<LocaleTag> {
    override fun compareTo(other: LocaleTag): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        /** Parses and canonicalizes BCP-47 spelling without consulting the process default locale. */
        public fun parse(value: String): LocaleTag {
            require(value.isNotEmpty() && value.utf8Size() <= MAX_BYTES) { "a locale tag is 1..$MAX_BYTES UTF-8 bytes" }
            require(!value.hasUnpairedSurrogate()) { "a locale tag has an unpaired UTF-16 surrogate" }
            val parsed =
                try {
                    Locale.Builder().setLanguageTag(value).build()
                } catch (failure: java.util.IllformedLocaleException) {
                    throw IllegalArgumentException("an invalid BCP-47 locale tag \"$value\"", failure)
                }
            val canonical = parsed.toLanguageTag()
            require(canonical != "und" || value.equals("und", ignoreCase = true)) {
                "an invalid BCP-47 locale tag \"$value\""
            }
            return LocaleTag(canonical)
        }

        public const val MAX_BYTES: Int = 256
    }
}

/** Where a locale candidate came from. A source is observability, not authorization. */
public enum class LocaleSource {
    EXPLICIT,
    USER,
    PROTOCOL,
    TENANT,
    APPLICATION,
}

/** Whether a supported locale must match structurally or may use ICU's versioned distance data. */
public enum class LocaleMatchMode {
    LOOKUP,
    BEST_FIT,
}

/** What a route does after all supplied preferences miss the declared supported set. */
public enum class DefaultOnMiss {
    USE_DEFAULT,
    REFUSE,
}

/** One already validated locale candidate, supplied in precedence order. */
public data class LocaleChoice(
    public val source: LocaleSource,
    public val locale: LocaleTag,
)

/** Why a resolver chose an outcome. It is intentionally a closed, low-cardinality diagnostic. */
public enum class LocaleResolutionReason {
    EXACT,
    LOOKUP,
    BEST_FIT,
    WILDCARD,
    POLICY_DEFAULT,
    UNSUPPORTED,
    EXCLUDED,
    MALFORMED,
    LIMIT,
}

/** The result of locale negotiation; template fallback happens separately while rendering a message. */
public sealed interface LocaleResolution {
    public class Resolved(
        public val locale: LocaleTag,
        public val requested: LocaleTag?,
        public val source: LocaleSource,
        public val reason: LocaleResolutionReason,
        internal val resolverToken: Long = 0,
    ) : LocaleResolution {
        override fun equals(other: Any?): Boolean =
            other is Resolved && locale == other.locale && requested == other.requested && source == other.source && reason == other.reason

        override fun hashCode(): Int = listOf(locale, requested, source, reason).hashCode()

        override fun toString(): String = "Resolved(locale=$locale, requested=$requested, source=$source, reason=$reason)"
    }

    public data class Refused(
        public val reason: LocaleResolutionReason,
    ) : LocaleResolution
}

/** The explicit, immutable policy a [LocaleResolver] follows. */
public data class LocalePolicy(
    public val supported: Set<LocaleTag>,
    public val defaultLocale: LocaleTag,
    public val parents: Map<LocaleTag, LocaleTag> = emptyMap(),
    public val matchMode: LocaleMatchMode = LocaleMatchMode.LOOKUP,
    public val defaultOnMiss: DefaultOnMiss = DefaultOnMiss.USE_DEFAULT,
) {
    init {
        require(supported.isNotEmpty()) { "a locale policy supports at least one locale" }
        require(defaultLocale in supported) { "the default locale is supported" }
        require(parents.keys.all { it in supported } && parents.values.all { it in supported }) {
            "a locale parent connects two supported locales"
        }
        parents.keys.forEach { start ->
            val seen = mutableSetOf<LocaleTag>()
            var current: LocaleTag? = start
            while (current != null) {
                require(seen.add(current)) { "locale parents contain a cycle at $current" }
                current = parents[current]
            }
        }
    }
}

/**
 * Deterministic locale negotiation over a declared supported set.
 *
 * It does not read `Locale.getDefault()`, expand a generic language to an arbitrary script, or
 * remember the previous request. Callers supply precedence explicitly in [resolve].
 */
public class LocaleResolver(
    public val policy: LocalePolicy,
    private val limits: I18nLimits = I18nLimits(),
) {
    private val token: Long = TOKENS.incrementAndGet()
    private val supported: List<LocaleTag> = policy.supported.sorted()
    private val matcher: LocaleMatcher? =
        if (policy.matchMode == LocaleMatchMode.BEST_FIT) {
            LocaleMatcher
                .builder()
                .setNoDefaultLocale()
                .setSupportedULocales(supported.map(::icuLocale))
                .build()
        } else {
            null
        }

    init {
        require(policy.supported.size <= limits.maxLocales) {
            "a locale policy supports at most ${limits.maxLocales} locales"
        }
    }

    /** Resolves candidates in their supplied precedence order. */
    public fun resolve(choices: List<LocaleChoice>): LocaleResolution {
        require(choices.size <= limits.maxLocaleRanges) {
            "a locale request names at most ${limits.maxLocaleRanges} locale candidates"
        }
        choices.forEach { choice ->
            require(choice.locale.value.utf8Size() <= limits.maxLocaleRangeBytes) {
                "a locale request range is at most ${limits.maxLocaleRangeBytes} UTF-8 bytes"
            }
            val matched = match(choice.locale)
            if (matched != null) {
                return resolved(matched.locale, choice.locale, choice.source, matched.reason)
            }
        }
        return defaultOrRefuse()
    }

    /** Parses repeated `Accept-Language` fields and resolves their weighted, non-excluded ranges. */
    public fun resolveAcceptLanguage(fields: List<String>): LocaleResolution =
        when (val parsed = AcceptLanguage.parse(fields, limits)) {
            is AcceptLanguage.Parse.Refused -> LocaleResolution.Refused(parsed.reason)
            is AcceptLanguage.Parse.Parsed -> resolveRanges(parsed.ranges)
        }

    /** The declared template fallback chain, independent from negotiation and ICU best-fit. */
    public fun fallbackChain(locale: LocaleTag): List<LocaleTag> {
        require(locale in policy.supported) { "a template fallback locale is supported by this resolver" }
        val chain = mutableListOf<LocaleTag>()
        val seen = mutableSetOf<LocaleTag>()
        var current: LocaleTag? = locale
        while (current != null && seen.add(current)) {
            if (current in policy.supported) chain += current
            val explicit = policy.parents[current]
            if (explicit != null) {
                current = explicit
                continue
            }
            val separator = current.value.lastIndexOf('-')
            current = if (separator < 0) null else LocaleTag.parse(current.value.substring(0, separator))
        }
        return chain.distinct()
    }

    private fun resolveRanges(ranges: List<LanguageRange>): LocaleResolution {
        val exclusions = ranges.filter { it.quality == 0 }
        ranges.filter { it.quality > 0 }.forEach { range ->
            if (range.wildcard) {
                val locale =
                    supported.firstOrNull { candidate -> exclusions.none { exclusion -> exclusion.matches(candidate) } }
                        ?: return LocaleResolution.Refused(LocaleResolutionReason.EXCLUDED)
                return resolved(locale, null, LocaleSource.PROTOCOL, LocaleResolutionReason.WILDCARD)
            }
            val requested = requireNotNull(range.locale)
            val matched = match(requested)
            if (matched != null && exclusions.none { exclusion -> exclusion.matches(matched.locale) }) {
                return resolved(matched.locale, requested, LocaleSource.PROTOCOL, matched.reason)
            }
        }
        return if (exclusions.any { exclusion -> exclusion.matches(policy.defaultLocale) }) {
            LocaleResolution.Refused(LocaleResolutionReason.EXCLUDED)
        } else {
            defaultOrRefuse()
        }
    }

    private fun defaultOrRefuse(): LocaleResolution =
        when (policy.defaultOnMiss) {
            DefaultOnMiss.USE_DEFAULT -> {
                resolved(policy.defaultLocale, null, LocaleSource.APPLICATION, LocaleResolutionReason.POLICY_DEFAULT)
            }

            DefaultOnMiss.REFUSE -> {
                LocaleResolution.Refused(LocaleResolutionReason.UNSUPPORTED)
            }
        }

    private fun match(requested: LocaleTag): Match? {
        if (requested in policy.supported) return Match(requested, LocaleResolutionReason.EXACT)
        lookup(requested)?.let { return Match(it, LocaleResolutionReason.LOOKUP) }
        if (policy.matchMode == LocaleMatchMode.BEST_FIT) {
            val matched = requireNotNull(matcher).getBestMatch(icuLocale(requested)) ?: return null
            val tag = LocaleTag.parse(matched.toLanguageTag())
            if (tag in policy.supported) return Match(tag, LocaleResolutionReason.BEST_FIT)
        }
        return null
    }

    private fun lookup(requested: LocaleTag): LocaleTag? {
        var current: LocaleTag? = requested
        val visited = mutableSetOf<LocaleTag>()
        while (current != null && visited.add(current)) {
            if (current in policy.supported) return current
            val explicit = policy.parents[current]
            if (explicit != null) {
                current = explicit
                continue
            }
            val value = current.value
            val separator = value.lastIndexOf('-')
            current = if (separator < 0) null else LocaleTag.parse(value.substring(0, separator))
        }
        return null
    }

    private data class Match(
        val locale: LocaleTag,
        val reason: LocaleResolutionReason,
    )

    private fun icuLocale(tag: LocaleTag): ULocale = ULocale.forLanguageTag(tag.value)

    internal fun owns(resolution: LocaleResolution.Resolved): Boolean = resolution.resolverToken == token

    private fun resolved(
        locale: LocaleTag,
        requested: LocaleTag?,
        source: LocaleSource,
        reason: LocaleResolutionReason,
    ): LocaleResolution.Resolved = LocaleResolution.Resolved(locale, requested, source, reason, token)

    private companion object {
        val TOKENS: AtomicLong = AtomicLong()
    }
}

private fun LanguageRange.matches(locale: LocaleTag): Boolean =
    wildcard || requireNotNull(this.locale).let { excluded -> locale == excluded || locale.value.startsWith("${excluded.value}-") }

/** A quality-sorted, bounded `Accept-Language` range. */
public data class LanguageRange(
    public val locale: LocaleTag?,
    public val wildcard: Boolean,
    public val quality: Int,
) {
    init {
        require(wildcard != (locale != null)) { "a language range is exactly a locale or wildcard" }
        require(quality in 0..1_000) { "a language range quality is 0..1000" }
    }
}

/** Strict parser for repeated `Accept-Language` fields. */
public object AcceptLanguage {
    public sealed interface Parse {
        public data class Parsed(
            public val ranges: List<LanguageRange>,
        ) : Parse

        public data class Refused(
            public val reason: LocaleResolutionReason,
        ) : Parse
    }

    public fun parse(
        fields: List<String>,
        limits: I18nLimits = I18nLimits(),
    ): Parse {
        if (fields.sumOf(String::utf8Size) > limits.maxLocaleRangeBytes) return Parse.Refused(LocaleResolutionReason.LIMIT)
        val found = mutableListOf<IndexedRange>()
        fields.forEach { field ->
            field.split(',').forEach { raw ->
                if (found.size == limits.maxLocaleRanges) return Parse.Refused(LocaleResolutionReason.LIMIT)
                parseOne(raw.trim())?.let { found += IndexedRange(found.size, it) }
                    ?: return Parse.Refused(LocaleResolutionReason.MALFORMED)
            }
        }
        return Parse.Parsed(
            found.sortedWith(compareByDescending<IndexedRange> { it.range.quality }.thenBy { it.index }).map(IndexedRange::range),
        )
    }

    private fun parseOne(raw: String): LanguageRange? {
        if (raw.isEmpty()) return null
        val pieces = raw.split(';')
        val range = pieces.first().trim()
        if (range.isEmpty()) return null
        var quality = 1_000
        var qualityStated = false
        pieces.drop(1).forEach { parameter ->
            val equals = parameter.indexOf('=')
            if (equals <= 0 || !parameter.substring(0, equals).trim().equals("q", ignoreCase = true)) return null
            if (qualityStated) return null
            quality = parseQuality(parameter.substring(equals + 1).trim()) ?: return null
            qualityStated = true
        }
        return if (range == "*") {
            LanguageRange(locale = null, wildcard = true, quality = quality)
        } else {
            val locale =
                try {
                    LocaleTag.parse(range)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            LanguageRange(locale = locale, wildcard = false, quality = quality)
        }
    }

    private fun parseQuality(value: String): Int? {
        if (value == "0" || value == "0.0" || value == "0.00" || value == "0.000") return 0
        if (value == "1" || value == "1.0" || value == "1.00" || value == "1.000") return 1_000
        val match = Regex("0\\.([0-9]{1,3})").matchEntire(value) ?: return null
        return match.groupValues[1].padEnd(3, '0').toInt()
    }

    private data class IndexedRange(
        val index: Int,
        val range: LanguageRange,
    )
}
