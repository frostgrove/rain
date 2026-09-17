package com.gd.rain.i18n

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/** A closed value domain a message contract may name. Arbitrary objects never enter rendering. */
public sealed interface MessageValue {
    public data class Text(
        public val value: String,
    ) : MessageValue {
        init {
            require(!value.hasUnpairedSurrogate()) { "text has an unpaired UTF-16 surrogate" }
        }
    }

    public data class BooleanValue(
        public val value: Boolean,
    ) : MessageValue

    public data class IntegerValue(
        public val value: Long,
    ) : MessageValue

    public data class UnsignedIntegerValue(
        public val value: UnsignedLong,
    ) : MessageValue

    public data class BigIntegerValue(
        public val value: BigInteger,
    ) : MessageValue

    /** [value] retains its scale: `1` and `1.0` remain distinct for plural rules. */
    public data class DecimalValue(
        public val value: BigDecimal,
    ) : MessageValue

    public data class MoneyValue(
        public val amount: BigDecimal,
        public val currency: Currency,
    ) : MessageValue

    public data class DateValue(
        public val value: LocalDate,
    ) : MessageValue

    public data class InstantValue(
        public val value: Instant,
    ) : MessageValue

    /** An enum's stable wire member, not its Java/Kotlin class name. */
    public data class EnumValue(
        public val value: String,
    ) : MessageValue {
        init {
            require(MessageKey.IDENTIFIER.matches(value)) { "an enum value matches ${MessageKey.IDENTIFIER.pattern}" }
        }
    }

    /** Explicit null differs from omitting an optional argument. */
    public data object Null : MessageValue
}

/**
 * An unsigned 64-bit integer with a JVM- and Java-friendly representation.
 *
 * Kotlin callers may use [of]; Java callers can use [parse] or [fromBigInteger], avoiding Kotlin
 * unsigned ABI name mangling.
 */
public class UnsignedLong private constructor(
    public val value: BigInteger,
) : Comparable<UnsignedLong> {
    init {
        require(value.signum() >= 0 && value <= MAXIMUM) { "an unsigned long is in 0..18446744073709551615" }
    }

    override fun compareTo(other: UnsignedLong): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is UnsignedLong && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()

    public companion object {
        private val MAXIMUM: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

        @JvmStatic
        public fun of(value: ULong): UnsignedLong = UnsignedLong(BigInteger(value.toString()))

        @JvmStatic
        public fun fromBigInteger(value: BigInteger): UnsignedLong = UnsignedLong(value)

        @JvmStatic
        public fun parse(value: String): UnsignedLong {
            require(value.isNotEmpty() && value.all(Char::isDigit)) { "an unsigned long is decimal digits" }
            return UnsignedLong(BigInteger(value))
        }
    }
}

/** Missing, explicit null and a supplied value are three distinct binding states. */
public sealed interface OptionalValue<out T> {
    public data object Absent : OptionalValue<Nothing>

    public data object Null : OptionalValue<Nothing>

    public data class Present<T>(
        public val value: T,
    ) : OptionalValue<T>
}
