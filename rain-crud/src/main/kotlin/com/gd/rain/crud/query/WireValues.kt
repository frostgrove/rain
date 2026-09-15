package com.gd.rain.crud.query

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A wire value read as its field's kind, or the reason it cannot be. */
public sealed interface WireValue {
    public data class Read(
        public val value: Any,
    ) : WireValue

    public data class Refused(
        public val reason: String,
    ) : WireValue
}

/**
 * How dialect v1 reads a value, per kind. Each kind has exactly one accepted spelling; nothing is tried
 * in turn and no zone is assumed.
 *
 * - `TIMESTAMP`: RFC 3339 `date-time` — seconds present, an optional fraction of up to nine digits, and an
 *   offset `Z` or `±hh:mm`. A date alone or a date-time without an offset is refused.
 * - `DATE`: `yyyy-mm-dd`; a date-time is refused.
 * - `BOOLEAN`: `true` or `false`.
 * - `UUID`: canonical 8-4-4-4-12 hexadecimal.
 * - `INT`, `LONG`: an optional `-` and decimal digits without leading zeros, within the kind's range.
 * - `DECIMAL`: the same, with an optional fraction; no exponent.
 * - `TEXT`: the value as sent.
 */
public object WireValues {
    private val DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})$")
    private val LOCAL_DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")
    private val DECIMAL = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")

    public fun read(
        raw: String,
        kind: FieldKind,
    ): WireValue =
        when (kind) {
            FieldKind.TEXT -> WireValue.Read(raw)
            FieldKind.BOOLEAN -> boolean(raw)
            FieldKind.INT -> integer(raw) { it.toIntOrNull() }
            FieldKind.LONG -> integer(raw) { it.toLongOrNull() }
            FieldKind.DECIMAL -> if (DECIMAL.matches(raw)) WireValue.Read(BigDecimal(raw)) else WireValue.Refused("is not a decimal number")
            FieldKind.UUID -> ResourceSchema.canonicalUuid(raw)?.let(WireValue::Read) ?: WireValue.Refused("is not a canonical UUID")
            FieldKind.TIMESTAMP -> timestamp(raw)
            FieldKind.DATE -> date(raw)
        }

    private fun boolean(raw: String): WireValue =
        when (raw) {
            "true" -> WireValue.Read(true)
            "false" -> WireValue.Read(false)
            else -> WireValue.Refused("is not true or false")
        }

    private fun integer(
        raw: String,
        parse: (String) -> Any?,
    ): WireValue {
        if (!INTEGER.matches(raw)) return WireValue.Refused("is not an integer")
        return parse(raw)?.let(WireValue::Read) ?: WireValue.Refused("is outside the range of this field")
    }

    private fun timestamp(raw: String): WireValue {
        if (!DATE_TIME.matches(raw)) {
            return when {
                DATE.matches(raw) -> WireValue.Refused("is a date; this field is a timestamp and needs a time and an offset")
                LOCAL_DATE_TIME.matches(raw) -> WireValue.Refused("has no offset; a timestamp ends in Z or ±hh:mm")
                else -> WireValue.Refused("is not an RFC 3339 date-time")
            }
        }
        // RFC 3339 letters are case-insensitive; the pattern only lets `T` and `Z` through, so upper-casing them is exact.
        val canonical = raw.replace('t', 'T').replace('z', 'Z')
        return try {
            WireValue.Read(OffsetDateTime.parse(canonical, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant())
        } catch (_: DateTimeParseException) {
            WireValue.Refused("is not a valid date-time")
        }
    }

    private fun date(raw: String): WireValue {
        if (!DATE.matches(raw)) {
            return if (DATE_TIME.matches(raw) || LOCAL_DATE_TIME.matches(raw)) {
                WireValue.Refused("is a date-time; this field is a date")
            } else {
                WireValue.Refused("is not a yyyy-mm-dd date")
            }
        }
        return try {
            WireValue.Read(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE))
        } catch (_: DateTimeParseException) {
            WireValue.Refused("is not a valid date")
        }
    }
}
