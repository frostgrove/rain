package com.gd.rain.core.error

import java.math.BigDecimal

/**
 * One bounded value carried by a violation for later presentation. Values are never written to the
 * problem body directly: a localizer must explicitly bind them to a declared message contract.
 */
public sealed interface ViolationParameterValue {
    public data class Text(
        public val value: String,
    ) : ViolationParameterValue {
        init {
            require(value.utf8Bytes() <= MAX_TEXT_BYTES && !value.hasUnpairedSurrogate()) {
                "a violation text parameter is valid UTF-8 of at most $MAX_TEXT_BYTES bytes"
            }
        }
    }

    public data class BooleanValue(
        public val value: Boolean,
    ) : ViolationParameterValue

    public data class IntegerValue(
        public val value: Long,
    ) : ViolationParameterValue

    public data class DecimalValue(
        public val value: BigDecimal,
    ) : ViolationParameterValue {
        init {
            require(value.precision() <= MAX_DECIMAL_DIGITS && value.scale() in -MAX_DECIMAL_SCALE..MAX_DECIMAL_SCALE) {
                "a violation decimal parameter has at most $MAX_DECIMAL_DIGITS digits and scale within " +
                    "-$MAX_DECIMAL_SCALE..$MAX_DECIMAL_SCALE"
            }
        }
    }

    private companion object {
        const val MAX_TEXT_BYTES: Int = 4 shl 10
        const val MAX_DECIMAL_DIGITS: Int = 128
        const val MAX_DECIMAL_SCALE: Int = 128
    }
}

/** One explicitly named value available to a violation-message binding. */
public data class ViolationParameter(
    public val name: String,
    public val value: ViolationParameterValue,
) {
    init {
        require(NAME.matches(name)) { "a violation parameter name matches ${NAME.pattern}" }
    }

    public companion object {
        public val NAME: Regex = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}

/** A bounded, immutable, duplicate-free set of typed violation parameters. */
public class ViolationParameters private constructor(
    entries: List<ViolationParameter>,
) : Comparable<ViolationParameters> {
    public val entries: List<ViolationParameter> = entries.sortedBy(ViolationParameter::name).toList()
    private val byName: Map<String, ViolationParameterValue> = this.entries.associate { it.name to it.value }

    init {
        require(this.entries.size <= MAX_ENTRIES) { "a violation carries at most $MAX_ENTRIES parameters" }
        require(byName.size == this.entries.size) { "a violation parameter is supplied at most once" }
    }

    public operator fun get(name: String): ViolationParameterValue? = byName[name]

    public fun text(name: String): String? = (byName[name] as? ViolationParameterValue.Text)?.value

    public fun boolean(name: String): Boolean? = (byName[name] as? ViolationParameterValue.BooleanValue)?.value

    public fun integer(name: String): Long? = (byName[name] as? ViolationParameterValue.IntegerValue)?.value

    public fun decimal(name: String): BigDecimal? = (byName[name] as? ViolationParameterValue.DecimalValue)?.value

    override fun compareTo(other: ViolationParameters): Int {
        for (index in entries.indices) {
            if (index >= other.entries.size) return 1
            val byParameterName = entries[index].name.compareTo(other.entries[index].name)
            if (byParameterName != 0) return byParameterName
            val byValue = compareValues(entries[index].value, other.entries[index].value)
            if (byValue != 0) return byValue
        }
        return if (other.entries.size > entries.size) -1 else 0
    }

    override fun equals(other: Any?): Boolean = other is ViolationParameters && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "ViolationParameters(entries=$entries)"

    public companion object {
        public const val MAX_ENTRIES: Int = 16
        public val EMPTY: ViolationParameters = ViolationParameters(emptyList())

        public fun of(vararg entries: ViolationParameter): ViolationParameters = ViolationParameters(entries.toList())

        public fun build(block: Builder.() -> Unit): ViolationParameters = Builder().apply(block).finish()

        private fun compareValues(
            first: ViolationParameterValue,
            second: ViolationParameterValue,
        ): Int {
            val byType = typeOrder(first).compareTo(typeOrder(second))
            if (byType != 0) return byType
            return when (first) {
                is ViolationParameterValue.Text -> {
                    first.value.compareTo((second as ViolationParameterValue.Text).value)
                }

                is ViolationParameterValue.BooleanValue -> {
                    first.value.compareTo((second as ViolationParameterValue.BooleanValue).value)
                }

                is ViolationParameterValue.IntegerValue -> {
                    first.value.compareTo((second as ViolationParameterValue.IntegerValue).value)
                }

                is ViolationParameterValue.DecimalValue -> {
                    second as ViolationParameterValue.DecimalValue
                    val byNumber = first.value.compareTo(second.value)
                    if (byNumber != 0) byNumber else first.value.scale().compareTo(second.value.scale())
                }
            }
        }

        private fun typeOrder(value: ViolationParameterValue): Int =
            when (value) {
                is ViolationParameterValue.Text -> 0
                is ViolationParameterValue.BooleanValue -> 1
                is ViolationParameterValue.IntegerValue -> 2
                is ViolationParameterValue.DecimalValue -> 3
            }
    }

    /** The low-level SDK builder; every entry still constructs a closed [ViolationParameterValue]. */
    public class Builder internal constructor() {
        private val entries = mutableListOf<ViolationParameter>()

        public fun value(
            name: String,
            value: ViolationParameterValue,
        ) {
            entries += ViolationParameter(name, value)
        }

        public fun text(
            name: String,
            value: String,
        ) {
            value(name, ViolationParameterValue.Text(value))
        }

        public fun boolean(
            name: String,
            value: Boolean,
        ) {
            value(name, ViolationParameterValue.BooleanValue(value))
        }

        public fun integer(
            name: String,
            value: Long,
        ) {
            value(name, ViolationParameterValue.IntegerValue(value))
        }

        public fun decimal(
            name: String,
            value: BigDecimal,
        ) {
            value(name, ViolationParameterValue.DecimalValue(value))
        }

        internal fun finish(): ViolationParameters = ViolationParameters(entries.toList())
    }
}

private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

private fun String.hasUnpairedSurrogate(): Boolean {
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
