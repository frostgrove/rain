package com.gd.rain.i18n

/**
 * Finite ceilings the catalog kernel applies before parsing, compiling or producing proportional
 * output. A source artifact may narrow these limits, but it can never widen the operator's local
 * limits.
 */
public data class I18nLimits(
    public val maxCatalogBytes: Int = 16 * 1024 * 1024,
    public val maxArtifactBytes: Int = 16 * 1024 * 1024,
    public val maxMessages: Int = 10_000,
    public val maxLocales: Int = 128,
    public val maxArguments: Int = 64,
    public val maxIdentifierBytes: Int = 128,
    public val maxTemplateBytes: Int = 64 * 1024,
    public val maxDescriptionBytes: Int = 16 * 1024,
    public val maxOutputBytes: Int = 256 * 1024,
    public val maxOutputParts: Int = 16_384,
    public val maxNestingDepth: Int = 32,
    public val maxLocaleRangeBytes: Int = 256,
    public val maxLocaleRanges: Int = 32,
    public val maxExplanationBytes: Int = 16 * 1024,
) {
    init {
        require(maxCatalogBytes in 1..MAX_CATALOG_BYTES) { "maxCatalogBytes is 1..$MAX_CATALOG_BYTES" }
        require(maxArtifactBytes in 1..MAX_CATALOG_BYTES) { "maxArtifactBytes is 1..$MAX_CATALOG_BYTES" }
        require(maxMessages in 1..MAX_MESSAGES) { "maxMessages is 1..$MAX_MESSAGES" }
        require(maxLocales in 1..MAX_LOCALES) { "maxLocales is 1..$MAX_LOCALES" }
        require(maxArguments in 0..MAX_ARGUMENTS) { "maxArguments is 0..$MAX_ARGUMENTS" }
        require(maxIdentifierBytes in 1..MAX_IDENTIFIER_BYTES) { "maxIdentifierBytes is 1..$MAX_IDENTIFIER_BYTES" }
        require(maxTemplateBytes in 1..MAX_TEMPLATE_BYTES) { "maxTemplateBytes is 1..$MAX_TEMPLATE_BYTES" }
        require(maxDescriptionBytes in 0..MAX_DESCRIPTION_BYTES) {
            "maxDescriptionBytes is 0..$MAX_DESCRIPTION_BYTES"
        }
        require(maxOutputBytes in 1..MAX_OUTPUT_BYTES) { "maxOutputBytes is 1..$MAX_OUTPUT_BYTES" }
        require(maxOutputParts in 1..MAX_OUTPUT_PARTS) { "maxOutputParts is 1..$MAX_OUTPUT_PARTS" }
        require(maxNestingDepth in 1..MAX_NESTING_DEPTH) { "maxNestingDepth is 1..$MAX_NESTING_DEPTH" }
        require(maxLocaleRangeBytes in 1..MAX_LOCALE_RANGE_BYTES) {
            "maxLocaleRangeBytes is 1..$MAX_LOCALE_RANGE_BYTES"
        }
        require(maxLocaleRanges in 1..MAX_LOCALE_RANGES) { "maxLocaleRanges is 1..$MAX_LOCALE_RANGES" }
        require(maxExplanationBytes in 1..MAX_EXPLANATION_BYTES) {
            "maxExplanationBytes is 1..$MAX_EXPLANATION_BYTES"
        }
    }

    public companion object {
        public const val MAX_CATALOG_BYTES: Int = 1 shl 28
        public const val MAX_MESSAGES: Int = 1_000_000
        public const val MAX_LOCALES: Int = 4_096
        public const val MAX_ARGUMENTS: Int = 1_024
        public const val MAX_IDENTIFIER_BYTES: Int = 1_024
        public const val MAX_TEMPLATE_BYTES: Int = 1 shl 20
        public const val MAX_DESCRIPTION_BYTES: Int = 1 shl 20
        public const val MAX_OUTPUT_BYTES: Int = 1 shl 28
        public const val MAX_OUTPUT_PARTS: Int = 1 shl 20
        public const val MAX_NESTING_DEPTH: Int = 256
        public const val MAX_LOCALE_RANGE_BYTES: Int = 4_096
        public const val MAX_LOCALE_RANGES: Int = 4_096
        public const val MAX_EXPLANATION_BYTES: Int = 1 shl 20
    }
}

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

internal fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            }

            character.isLowSurrogate() -> {
                return true
            }

            else -> {
                index++
            }
        }
    }
    return false
}
