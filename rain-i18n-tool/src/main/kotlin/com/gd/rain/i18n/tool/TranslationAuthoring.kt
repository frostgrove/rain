package com.gd.rain.i18n.tool

import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogSourceCodec
import com.gd.rain.i18n.CatalogSourceDecoding
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.SourceCodecProblem
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec

/** The explicit translator decision that may alter a source translation's review state. */
public enum class TranslationReviewAction {
    APPROVE,
    REJECT,
}

/** Source-mutating authoring commands return canonical source bytes or path-addressed diagnostics. */
public sealed interface CatalogToolSourceMutation {
    public data class Mutated(
        private val source: ByteArray,
    ) : CatalogToolSourceMutation {
        public fun sourceBytes(): ByteArray = source.copyOf()
    }

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolSourceMutation
}

/** A deterministic non-pruning merge also reports messages retained solely from the baseline. */
public sealed interface CatalogToolMerge {
    public data class Merged(
        private val source: ByteArray,
        public val carriedTranslations: Int,
        public val retainedObsoleteKeys: List<MessageKey>,
    ) : CatalogToolMerge {
        public fun sourceBytes(): ByteArray = source.copyOf()
    }

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolMerge
}

/**
 * Offline source review and merge operations. They do not activate a release and never overwrite
 * source wording: compilation/strict coverage remain the separate `check` decision.
 */
public class TranslationAuthoringTool(
    private val sourceCodec: CatalogSourceCodec = CatalogSourceCodec(),
) {
    /** Stamps or removes approval for exactly one declared non-source translation. */
    public fun review(
        source: ByteArray,
        key: MessageKey,
        locale: LocaleTag,
        action: TranslationReviewAction,
    ): CatalogToolSourceMutation =
        when (val decoded = sourceCodec.decode(source)) {
            is CatalogSourceDecoding.Decoded -> reviewDecoded(decoded.source, key, locale, action)
            is CatalogSourceDecoding.Refused -> CatalogToolSourceMutation.Invalid(decoded.problems)
        }

    /**
     * Carries translation work only when source wording and binding contract are identical.
     * Messages missing from [incoming] remain in output and are named as obsolete; callers must
     * explicitly prune them in a later command rather than losing work during extraction merge.
     */
    public fun merge(
        baseline: ByteArray,
        incoming: ByteArray,
    ): CatalogToolMerge {
        val base = sourceCodec.decode(baseline)
        val next = sourceCodec.decode(incoming)
        val problems = sourceProblems("baseline", base) + sourceProblems("incoming", next)
        if (problems.isNotEmpty()) return CatalogToolMerge.Invalid(problems.sorted())
        val baselineSource = (base as CatalogSourceDecoding.Decoded).source
        val incomingSource = (next as CatalogSourceDecoding.Decoded).source
        if (!compatibleCatalogs(baselineSource, incomingSource)) {
            return CatalogToolMerge.Invalid(
                listOf(
                    SourceCodecProblem("incoming", "catalog identity, source locale, locale policy, zone and limits differ from baseline"),
                ),
            )
        }
        val baselineMessages = baselineSource.messages.associateBy(MessageSpec::key)
        val incomingKeys = incomingSource.messages.map(MessageSpec::key).toSet()
        var carried = 0
        val merged =
            incomingSource.messages.map { message ->
                val previous = baselineMessages[message.key] ?: return@map message
                if (!structurallyCompatible(baselineSource, previous, incomingSource, message)) return@map message
                val presentLocales = message.translations.map(TranslationSpec::locale).toSet()
                val carriedEntries = previous.translations.filterNot { it.locale in presentLocales }
                carried += carriedEntries.size
                message.copy(translations = message.translations + carriedEntries)
            } + baselineSource.messages.filterNot { it.key in incomingKeys }
        val output = incomingSource.copy(messages = merged.sortedBy(MessageSpec::key))
        return CatalogToolMerge.Merged(
            sourceCodec.encode(output),
            carried,
            baselineSource.messages
                .map(MessageSpec::key)
                .filterNot { it in incomingKeys }
                .sorted(),
        )
    }

    private fun reviewDecoded(
        source: CatalogSpec,
        key: MessageKey,
        locale: LocaleTag,
        action: TranslationReviewAction,
    ): CatalogToolSourceMutation {
        val index = source.messages.indexOfFirst { it.key == key }
        if (index < 0) return CatalogToolSourceMutation.Invalid(listOf(SourceCodecProblem("messages", "does not declare $key")))
        if (locale == source.sourceLocale || locale !in source.localePolicy.supported) {
            return CatalogToolSourceMutation.Invalid(listOf(SourceCodecProblem("locale", "is not a non-source supported locale")))
        }
        val message = source.messages[index]
        val translationIndex = message.translations.indexOfFirst { it.locale == locale }
        if (translationIndex < 0) {
            return CatalogToolSourceMutation.Invalid(
                listOf(SourceCodecProblem("messages[$index].translations", "does not declare $locale")),
            )
        }
        val sourceDigest = CatalogDigests.source(source.identity.profile, source.sourceLocale, message.source, message.description)
        val previous = message.translations[translationIndex]
        val reviewed =
            when (action) {
                TranslationReviewAction.APPROVE -> {
                    TranslationSpec(
                        locale,
                        previous.text,
                        TranslationReview.APPROVED,
                        sourceDigest,
                        CatalogDigests.review(sourceDigest, locale, previous.text),
                    )
                }

                TranslationReviewAction.REJECT -> {
                    TranslationSpec(locale, previous.text, TranslationReview.REJECTED)
                }
            }
        val translations = message.translations.toMutableList().also { it[translationIndex] = reviewed }
        val messages = source.messages.toMutableList().also { it[index] = message.copy(translations = translations) }
        return CatalogToolSourceMutation.Mutated(sourceCodec.encode(source.copy(messages = messages)))
    }

    private fun structurallyCompatible(
        baseline: CatalogSpec,
        previous: MessageSpec,
        incoming: CatalogSpec,
        next: MessageSpec,
    ): Boolean =
        CatalogDigests.contract(previous) == CatalogDigests.contract(next) &&
            CatalogDigests.source(baseline.identity.profile, baseline.sourceLocale, previous.source, previous.description) ==
            CatalogDigests.source(incoming.identity.profile, incoming.sourceLocale, next.source, next.description)

    private fun compatibleCatalogs(
        baseline: CatalogSpec,
        incoming: CatalogSpec,
    ): Boolean =
        baseline.identity.profile == incoming.identity.profile && baseline.identity.engine == incoming.identity.engine &&
            baseline.identity.icuClDrTzdbIdentity == incoming.identity.icuClDrTzdbIdentity &&
            baseline.sourceLocale == incoming.sourceLocale &&
            baseline.localePolicy == incoming.localePolicy && baseline.requiredLocales == incoming.requiredLocales &&
            baseline.defaultZone == incoming.defaultZone && baseline.limits == incoming.limits

    private fun sourceProblems(
        name: String,
        decoded: CatalogSourceDecoding,
    ): List<SourceCodecProblem> =
        when (decoded) {
            is CatalogSourceDecoding.Decoded -> emptyList()
            is CatalogSourceDecoding.Refused -> decoded.problems.map { SourceCodecProblem("$name.${it.path}", it.message) }
        }
}
