package com.gd.rain.i18n.tool

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogSourceCodec
import com.gd.rain.i18n.CatalogSourceDecoding
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.SourceCodecProblem
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec

/** The two deterministic, grammar-safe pseudo locale profiles provided by the offline tool. */
public enum class PseudoLocaleProfile(
    public val locale: LocaleTag,
) {
    ACCENT(LocaleTag.parse("en-XA")),
    RTL(LocaleTag.parse("ar-XB")),
}

/** Pseudo-localization either returns another canonical checked source or exact source diagnostics. */
public sealed interface CatalogToolPseudo {
    public data class Pseudoed(
        private val source: ByteArray,
    ) : CatalogToolPseudo {
        public fun sourceBytes(): ByteArray = source.copyOf()
    }

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolPseudo
}

/**
 * Adds an approved pseudo translation for every source message without touching the MF2 syntax.
 *
 * Braced expressions and allowed markup are copied byte-for-byte. Only literal runs around them
 * receive an accent or RTL-shaped representation. The final source is compiled again, so this
 * helper cannot accidentally declare a pseudo artifact whose grammar or review stamps are stale.
 */
public class PseudoLocalizer(
    private val sourceCodec: CatalogSourceCodec = CatalogSourceCodec(),
) {
    public fun pseudo(
        source: ByteArray,
        profile: PseudoLocaleProfile,
    ): CatalogToolPseudo =
        when (val decoded = sourceCodec.decode(source)) {
            is CatalogSourceDecoding.Decoded -> pseudoDecoded(decoded.source, profile)
            is CatalogSourceDecoding.Refused -> CatalogToolPseudo.Invalid(decoded.problems)
        }

    private fun pseudoDecoded(
        source: CatalogSpec,
        profile: PseudoLocaleProfile,
    ): CatalogToolPseudo {
        if (profile.locale == source.sourceLocale) {
            return CatalogToolPseudo.Invalid(listOf(SourceCodecProblem("pseudoLocale", "must differ from sourceLocale")))
        }
        val policy = pseudoPolicy(source.localePolicy, source.sourceLocale, profile.locale)
        val messages = source.messages.map { message -> pseudoMessage(source, message, profile) }
        val pseudoSource = source.copy(localePolicy = policy, messages = messages)
        return when (val compilation = CatalogCompiler.compile(pseudoSource)) {
            is CatalogCompilation.Compiled -> {
                CatalogToolPseudo.Pseudoed(sourceCodec.encode(pseudoSource))
            }

            is CatalogCompilation.Refused -> {
                CatalogToolPseudo.Invalid(compilation.problems.map { SourceCodecProblem(it.path, it.message) })
            }
        }
    }

    private fun pseudoPolicy(
        policy: LocalePolicy,
        sourceLocale: LocaleTag,
        pseudo: LocaleTag,
    ): LocalePolicy =
        LocalePolicy(
            supported = policy.supported + pseudo,
            defaultLocale = policy.defaultLocale,
            parents = policy.parents + (pseudo to sourceLocale),
            matchMode = policy.matchMode,
            defaultOnMiss = policy.defaultOnMiss,
        )

    private fun pseudoMessage(
        catalog: CatalogSpec,
        message: MessageSpec,
        profile: PseudoLocaleProfile,
    ): MessageSpec {
        val sourceDigest = CatalogDigests.source(catalog.identity.profile, catalog.sourceLocale, message.source, message.description)
        val text = pseudoTemplate(message.source, profile)
        val translation =
            TranslationSpec(
                profile.locale,
                text,
                TranslationReview.APPROVED,
                sourceDigest,
                CatalogDigests.review(sourceDigest, profile.locale, text),
            )
        return message.copy(translations = message.translations.filterNot { it.locale == profile.locale } + translation)
    }

    private fun pseudoTemplate(
        template: String,
        profile: PseudoLocaleProfile,
    ): String {
        val output = StringBuilder(template.length + 16)
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                output.append(transform(literal.toString(), profile))
                literal.clear()
            }
        }

        var index = 0
        while (index < template.length) {
            when (template[index]) {
                '{' -> {
                    flushLiteral()
                    val end = bracedEnd(template, index)
                    output.append(template, index, end + 1)
                    index = end + 1
                }

                '<' -> {
                    flushLiteral()
                    val end = template.indexOf('>', index).takeIf { it >= 0 } ?: template.lastIndex
                    output.append(template, index, end + 1)
                    index = end + 1
                }

                else -> {
                    literal.append(template[index])
                    index++
                }
            }
        }
        flushLiteral()
        return output.toString()
    }

    private fun bracedEnd(
        value: String,
        start: Int,
    ): Int {
        var depth = 0
        var index = start
        while (index < value.length) {
            when (value[index]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return value.lastIndex
    }

    private fun transform(
        literal: String,
        profile: PseudoLocaleProfile,
    ): String =
        when (profile) {
            PseudoLocaleProfile.ACCENT -> "［" + literal.map(::accent).joinToString("") + "］"
            PseudoLocaleProfile.RTL -> "［" + literal.reversed().map(::accent).joinToString("") + "］"
        }

    private fun accent(character: Char): Char =
        when (character) {
            'a' -> 'à'
            'b' -> 'ƀ'
            'c' -> 'ç'
            'd' -> 'ď'
            'e' -> 'ē'
            'f' -> 'ƒ'
            'g' -> 'ğ'
            'h' -> 'ħ'
            'i' -> 'ī'
            'j' -> 'ĵ'
            'k' -> 'ķ'
            'l' -> 'ľ'
            'm' -> 'ḿ'
            'n' -> 'ñ'
            'o' -> 'ō'
            'p' -> 'ṕ'
            'q' -> 'ʠ'
            'r' -> 'ř'
            's' -> 'ş'
            't' -> 'ŧ'
            'u' -> 'ū'
            'v' -> 'ṽ'
            'w' -> 'ŵ'
            'x' -> 'ẋ'
            'y' -> 'ŷ'
            'z' -> 'ž'
            'A' -> 'À'
            'B' -> 'Ƀ'
            'C' -> 'Ç'
            'D' -> 'Ď'
            'E' -> 'Ē'
            'F' -> 'Ƒ'
            'G' -> 'Ğ'
            'H' -> 'Ħ'
            'I' -> 'Ī'
            'J' -> 'Ĵ'
            'K' -> 'Ķ'
            'L' -> 'Ľ'
            'M' -> 'Ḿ'
            'N' -> 'Ñ'
            'O' -> 'Ō'
            'P' -> 'Ṕ'
            'Q' -> 'Ɋ'
            'R' -> 'Ř'
            'S' -> 'Ş'
            'T' -> 'Ŧ'
            'U' -> 'Ū'
            'V' -> 'Ṽ'
            'W' -> 'Ŵ'
            'X' -> 'Ẋ'
            'Y' -> 'Ŷ'
            'Z' -> 'Ž'
            else -> character
        }
}
