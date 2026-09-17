package com.gd.rain.i18n

/**
 * The parser and ICU/CLDR/tzdb backend a node has actually been configured to run.
 *
 * It is deliberately separate from a catalog revision: a catalog may change wording while the
 * runtime stays compatible, but a node must never activate an artifact compiled for different
 * grammar, formatter or Unicode data identities.
 */
public data class CatalogRuntimeIdentity(
    public val profile: String = "rain-mf2/v1",
    public val engine: String = "rain-i18n/1",
    public val icuClDrTzdbIdentity: String,
) {
    init {
        require(valid(profile)) { "a runtime profile is 1..128 printable ASCII characters" }
        require(valid(engine)) { "a runtime engine is 1..128 printable ASCII characters" }
        require(valid(icuClDrTzdbIdentity)) { "a runtime ICU/CLDR/tzdb identity is 1..128 printable ASCII characters" }
    }

    /** Checks every input that can change template or locale/time-zone interpretation. */
    public fun accepts(identity: CatalogIdentity): Boolean =
        profile == identity.profile && engine == identity.engine && icuClDrTzdbIdentity == identity.icuClDrTzdbIdentity

    private companion object {
        fun valid(value: String): Boolean =
            value.isNotEmpty() && value.length <= 128 && value.all { character -> character.code in 0x21..0x7E }
    }
}
