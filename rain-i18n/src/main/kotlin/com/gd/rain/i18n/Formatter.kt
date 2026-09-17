package com.gd.rain.i18n

import com.ibm.icu.number.Notation
import com.ibm.icu.number.NumberFormatter
import com.ibm.icu.number.NumberRangeFormatter
import com.ibm.icu.number.Precision
import com.ibm.icu.number.Scale
import com.ibm.icu.text.ConstrainedFieldPosition
import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DateIntervalFormat
import com.ibm.icu.text.FormattedValue
import com.ibm.icu.text.ListFormatter
import com.ibm.icu.text.MeasureFormat
import com.ibm.icu.text.PluralRules
import com.ibm.icu.text.RelativeDateTimeFormatter
import com.ibm.icu.util.Measure
import com.ibm.icu.util.MeasureUnit
import com.ibm.icu.util.NoUnit
import com.ibm.icu.util.ULocale
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Date
import com.ibm.icu.util.Currency as IcuCurrency
import com.ibm.icu.util.TimeZone as IcuTimeZone

/** Locale-stable grouping choices for [NumberFormatOptions]. */
public enum class NumberGrouping {
    AUTO,
    ALWAYS,
    MIN2,
    NEVER,
}

/** Locale-stable sign choices for [NumberFormatOptions]. */
public enum class NumberSign {
    AUTO,
    ALWAYS,
    NEVER,
    EXCEPT_ZERO,
}

/** A closed notation set; custom ICU skeletons never cross the public contract boundary. */
public enum class NumberNotation {
    SIMPLE,
    SCIENTIFIC,
    ENGINEERING,
    COMPACT_SHORT,
    COMPACT_LONG,
}

/** How a unit or currency name is displayed. */
public enum class UnitDisplay {
    NARROW,
    SHORT,
    FULL_NAME,
    ISO_CODE,
}

/** Whether a currency negative amount uses its locale's accounting convention. */
public enum class CurrencySign {
    STANDARD,
    ACCOUNTING,
}

/** Whether insignificant zeroes are retained after rounding. */
public enum class TrailingZeroDisplay {
    AUTO,
    HIDE_IF_WHOLE,
}

/** Immutable, validated numeric formatting options shared by template and direct rendering. */
public data class NumberFormatOptions(
    public val grouping: NumberGrouping = NumberGrouping.AUTO,
    public val sign: NumberSign = NumberSign.AUTO,
    public val notation: NumberNotation = NumberNotation.SIMPLE,
    public val minFractionDigits: Int? = null,
    public val maxFractionDigits: Int? = null,
    public val minSignificantDigits: Int? = null,
    public val maxSignificantDigits: Int? = null,
    public val roundingIncrement: BigDecimal? = null,
    public val roundingMode: RoundingMode = RoundingMode.HALF_EVEN,
    public val trailingZeroDisplay: TrailingZeroDisplay = TrailingZeroDisplay.AUTO,
    public val unitDisplay: UnitDisplay = UnitDisplay.SHORT,
) {
    init {
        require(minFractionDigits == null || minFractionDigits in 0..MAX_DIGITS) { "minimum fraction digits are 0..$MAX_DIGITS" }
        require(maxFractionDigits == null || maxFractionDigits in 0..MAX_DIGITS) { "maximum fraction digits are 0..$MAX_DIGITS" }
        require(minSignificantDigits == null || minSignificantDigits in 1..MAX_DIGITS) {
            "minimum significant digits are 1..$MAX_DIGITS"
        }
        require(maxSignificantDigits == null || maxSignificantDigits in 1..MAX_DIGITS) {
            "maximum significant digits are 1..$MAX_DIGITS"
        }
        require(minFractionDigits == null || maxFractionDigits == null || minFractionDigits <= maxFractionDigits) {
            "minimum fraction digits do not exceed maximum fraction digits"
        }
        require(minSignificantDigits == null || maxSignificantDigits == null || minSignificantDigits <= maxSignificantDigits) {
            "minimum significant digits do not exceed maximum significant digits"
        }
        require(
            (minFractionDigits == null && maxFractionDigits == null) ||
                (minSignificantDigits == null && maxSignificantDigits == null),
        ) {
            "fraction and significant digit precision cannot be combined"
        }
        require(roundingIncrement == null || roundingIncrement.signum() > 0) { "rounding increment is positive" }
        require(roundingIncrement == null || (minSignificantDigits == null && maxSignificantDigits == null)) {
            "rounding increment and significant digits cannot be combined"
        }
    }

    private companion object {
        const val MAX_DIGITS: Int = 100
    }
}

/** An ICU unit identifier that is validated without accepting arbitrary formatter syntax. */
@JvmInline
public value class UnitIdentifier private constructor(
    public val value: String,
) {
    public companion object {
        public fun parse(value: String): UnitIdentifier {
            require(value.isNotEmpty() && value.utf8Size() <= 128) { "a unit identifier is 1..128 UTF-8 bytes" }
            try {
                MeasureUnit.forIdentifier(value)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("an unknown ICU unit identifier $value", failure)
            }
            return UnitIdentifier(value)
        }
    }
}

/** Which endpoint produced a range segment. Shared punctuation and collapsed fields remain explicit. */
public enum class RangePartSource {
    START,
    SHARED,
    END,
}

/** A provenance-preserving text segment in a locale-formatted range. */
public data class FormattedRangePart(
    public val source: RangePartSource,
    public val text: String,
)

/** A range result retains ICU's combined rendering and non-flattened endpoint provenance. */
public data class FormattedRange(
    public val text: String,
    public val parts: List<FormattedRangePart>,
)

/** Closed plural selection modes. */
public enum class PluralType {
    CARDINAL,
    ORDINAL,
}

/** Closed list conjunction choices. */
public enum class ListType {
    AND,
    OR,
    UNITS,
}

/** Closed list width choices. */
public enum class ListWidth {
    WIDE,
    SHORT,
    NARROW,
}

/** A relative-time quantity unit accepted by the formatter. */
public enum class RelativeTimeUnit {
    YEAR,
    QUARTER,
    MONTH,
    WEEK,
    DAY,
    HOUR,
    MINUTE,
    SECOND,
}

/** The only display-name domains exposed by the stable Rain API. */
public enum class DisplayNameKind {
    LANGUAGE,
    REGION,
    LOCALE,
    CURRENCY,
    UNIT,
}

/** A named immutable application formatter policy. */
public data class FormatterPreset(
    public val number: NumberFormatOptions = NumberFormatOptions(),
    public val dateStyle: DateStyle = DateStyle.MEDIUM,
    public val timeStyle: DateStyle = DateStyle.SHORT,
)

/** The four CLDR date/time style levels, independent of JDK process defaults. */
public enum class DateStyle {
    FULL,
    LONG,
    MEDIUM,
    SHORT,
}

/** Immutable named formatter policies; applications can replace one preset without replacing ICU. */
public class FormatterRegistry(
    presets: Map<String, FormatterPreset> = emptyMap(),
) {
    private val presets: Map<String, FormatterPreset> = presets.toSortedMap()

    init {
        require(
            this.presets.keys.all(MessageKey.IDENTIFIER::matches),
        ) { "a formatter preset name matches ${MessageKey.IDENTIFIER.pattern}" }
    }

    public fun preset(name: String): FormatterPreset? = presets[name]

    public fun formatter(
        name: String,
        locale: LocaleTag,
        zone: ZoneId,
        limits: I18nLimits = I18nLimits(),
    ): I18nFormatter = I18nFormatter(locale, zone, limits, requireNotNull(preset(name)) { "formatter preset $name is not declared" })
}

/**
 * Explicit, immutable ICU4J formatter. It owns no cache or thread-local state and accepts only
 * Rain closed values, so the direct SDK has the same safety boundary as template rendering.
 */
public class I18nFormatter(
    public val locale: LocaleTag,
    public val zone: ZoneId,
    private val limits: I18nLimits = I18nLimits(),
    private val preset: FormatterPreset = FormatterPreset(),
) {
    private val icuLocale: ULocale = ULocale.forLanguageTag(locale.value)

    public fun number(
        value: MessageValue,
        options: NumberFormatOptions = preset.number,
    ): String = bounded(numberFormatter(options).format(numeric(value)).toString())

    public fun integer(
        value: MessageValue,
        options: NumberFormatOptions = preset.number,
    ): String = bounded(numberFormatter(options.copy(maxFractionDigits = 0, minFractionDigits = 0)).format(numeric(value)).toString())

    public fun percent(
        value: MessageValue,
        options: NumberFormatOptions = preset.number,
    ): String =
        bounded(
            numberFormatter(options)
                .unit(NoUnit.PERCENT)
                .scale(Scale.powerOfTen(2))
                .format(numeric(value))
                .toString(),
        )

    public fun money(
        value: MessageValue.MoneyValue,
        options: NumberFormatOptions = preset.number,
        currencySign: CurrencySign = CurrencySign.STANDARD,
    ): String = bounded(currencyFormatter(value.currency, options, currencySign).format(value.amount).toString())

    public fun unit(
        value: MessageValue,
        unit: UnitIdentifier,
        options: NumberFormatOptions = preset.number,
    ): String = bounded(numberFormatter(options).unit(MeasureUnit.forIdentifier(unit.value)).format(numeric(value)).toString())

    /** Adds an exact decimal offset before formatting; it never converts a value through `Double`. */
    public fun offset(
        value: MessageValue,
        amount: BigDecimal,
        options: NumberFormatOptions = preset.number,
    ): String = number(offsetValue(value, amount), options)

    public fun numberRange(
        start: MessageValue,
        end: MessageValue,
        options: NumberFormatOptions = preset.number,
    ): FormattedRange = numberRange(start, end, numberFormatterWithoutLocale(options))

    public fun percentRange(
        start: MessageValue,
        end: MessageValue,
        options: NumberFormatOptions = preset.number,
    ): FormattedRange = numberRange(start, end, numberFormatterWithoutLocale(options).unit(NoUnit.PERCENT).scale(Scale.powerOfTen(2)))

    public fun moneyRange(
        start: MessageValue.MoneyValue,
        end: MessageValue.MoneyValue,
        options: NumberFormatOptions = preset.number,
        currencySign: CurrencySign = CurrencySign.STANDARD,
    ): FormattedRange {
        require(start.currency == end.currency) { "a money range uses one currency" }
        return numberRange(start, end, currencyFormatterWithoutLocale(start.currency, options, currencySign))
    }

    public fun unitRange(
        start: MessageValue,
        end: MessageValue,
        unit: UnitIdentifier,
        options: NumberFormatOptions = preset.number,
    ): FormattedRange = numberRange(start, end, numberFormatterWithoutLocale(options).unit(MeasureUnit.forIdentifier(unit.value)))

    public fun plural(
        value: MessageValue,
        type: PluralType = PluralType.CARDINAL,
    ): String {
        val formatter = numberFormatter(NumberFormatOptions())
        val rules = PluralRules.forLocale(icuLocale, type.toIcu())
        return rules.select(formatter.format(numeric(value)))
    }

    /** Selects ICU's locale-specific plural-range category without losing either exact endpoint. */
    public fun pluralRange(
        start: MessageValue,
        end: MessageValue,
        type: PluralType = PluralType.CARDINAL,
    ): String {
        val range = NumberRangeFormatter.withLocale(icuLocale).formatRange(numeric(start), numeric(end))
        return PluralRules.forLocale(icuLocale, type.toIcu()).select(range)
    }

    public fun date(
        value: LocalDate,
        style: DateStyle = preset.dateStyle,
    ): String = bounded(dateFormatter(style).format(value))

    public fun date(
        value: Instant,
        style: DateStyle = preset.dateStyle,
    ): String = bounded(dateFormatter(style).format(Date.from(value)))

    public fun time(
        value: Instant,
        style: DateStyle = preset.timeStyle,
    ): String = bounded(timeFormatter(style).format(Date.from(value)))

    public fun dateTime(
        value: Instant,
        dateStyle: DateStyle = preset.dateStyle,
        timeStyle: DateStyle = preset.timeStyle,
    ): String = bounded(dateTimeFormatter(dateStyle, timeStyle).format(Date.from(value)))

    public fun dateRange(
        start: LocalDate,
        end: LocalDate,
        style: DateStyle = preset.dateStyle,
    ): FormattedRange = temporalRange(start, end, dateSkeleton(style))

    public fun dateTimeRange(
        start: Instant,
        end: Instant,
        dateStyle: DateStyle = preset.dateStyle,
        timeStyle: DateStyle = preset.timeStyle,
    ): FormattedRange = temporalRange(start, end, "${dateSkeleton(dateStyle)}${timeSkeleton(timeStyle)}")

    public fun timeRange(
        start: Instant,
        end: Instant,
        style: DateStyle = preset.timeStyle,
    ): FormattedRange = temporalRange(start, end, timeSkeleton(style))

    public fun list(
        values: List<MessageValue.Text>,
        type: ListType = ListType.AND,
        width: ListWidth = ListWidth.WIDE,
    ): String {
        require(values.size <= limits.maxArguments) { "a list has at most ${limits.maxArguments} values" }
        values.forEach { rejectUnsafeBidi(it) }
        return bounded(ListFormatter.getInstance(icuLocale, type.toIcu(), width.toIcu()).format(values.map(MessageValue.Text::value)))
    }

    public fun relative(
        value: Long,
        unit: RelativeTimeUnit,
    ): String {
        require(value in -MAX_EXACT_RELATIVE..MAX_EXACT_RELATIVE) { "a relative value is within ICU's exact integer range" }
        return bounded(RelativeDateTimeFormatter.getInstance(icuLocale).formatNumeric(value.toDouble(), unit.toIcu()))
    }

    public fun duration(
        value: Duration,
        width: UnitDisplay = UnitDisplay.SHORT,
    ): String {
        require(!value.isNegative) { "a duration is non-negative" }
        val seconds = value.seconds
        val measures = mutableListOf<Measure>()
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val remainingSeconds = seconds % 60
        val secondsWithFraction = BigDecimal.valueOf(remainingSeconds).add(BigDecimal.valueOf(value.nano.toLong(), 9))
        if (hours > 0) measures += Measure(hours, MeasureUnit.HOUR)
        if (minutes > 0) measures += Measure(minutes, MeasureUnit.MINUTE)
        if (secondsWithFraction.signum() > 0 || measures.isEmpty()) measures += Measure(secondsWithFraction, MeasureUnit.SECOND)
        return bounded(MeasureFormat.getInstance(icuLocale, width.toMeasureWidth()).formatMeasures(*measures.toTypedArray()))
    }

    public fun displayName(
        value: String,
        kind: DisplayNameKind,
    ): String {
        val result =
            when (kind) {
                DisplayNameKind.LANGUAGE -> {
                    ULocale.forLanguageTag(LocaleTag.parse(value).value).getDisplayLanguage(icuLocale)
                }

                DisplayNameKind.REGION -> {
                    ULocale("und-$value").getDisplayCountry(icuLocale)
                }

                DisplayNameKind.LOCALE -> {
                    ULocale.forLanguageTag(LocaleTag.parse(value).value).getDisplayName(icuLocale)
                }

                DisplayNameKind.CURRENCY -> {
                    IcuCurrency
                        .getInstance(
                            Currency.getInstance(value).currencyCode,
                        ).getName(icuLocale, IcuCurrency.LONG_NAME, null)
                }

                DisplayNameKind.UNIT -> {
                    MeasureFormat
                        .getInstance(
                            icuLocale,
                            MeasureFormat.FormatWidth.WIDE,
                        ).getUnitDisplayName(MeasureUnit.forIdentifier(UnitIdentifier.parse(value).value))
                }
            }
        return bounded(result)
    }

    private fun numberRange(
        start: MessageValue,
        end: MessageValue,
        formatter: com.ibm.icu.number.UnlocalizedNumberFormatter,
    ): FormattedRange {
        val formatted =
            NumberRangeFormatter
                .with()
                .numberFormatterBoth(
                    formatter,
                ).locale(icuLocale)
                .formatRange(numeric(start), numeric(end))
        return boundedRange(formatted, NumberRangeFormatter.SpanField.NUMBER_RANGE_SPAN)
    }

    private fun temporalRange(
        start: Any,
        end: Any,
        skeleton: String,
    ): FormattedRange {
        val formatter = DateIntervalFormat.getInstance(skeleton, icuLocale)
        formatter.timeZone = IcuTimeZone.getTimeZone(zone.id)
        val formatted =
            when {
                start is LocalDate && end is LocalDate -> formatter.formatToValue(start, end)
                start is Instant && end is Instant -> formatter.formatToValue(start.atZone(zone), end.atZone(zone))
                else -> error("date ranges use values of the same temporal type")
            }
        return boundedRange(formatted, DateIntervalFormat.SpanField.DATE_INTERVAL_SPAN)
    }

    private fun numberFormatter(options: NumberFormatOptions): com.ibm.icu.number.LocalizedNumberFormatter =
        numberFormatterWithoutLocale(options).locale(icuLocale)

    private fun numberFormatterWithoutLocale(options: NumberFormatOptions): com.ibm.icu.number.UnlocalizedNumberFormatter {
        var formatter = NumberFormatter.with()
        formatter = formatter.grouping(options.grouping.toIcu())
        formatter = formatter.sign(options.sign.toIcu())
        formatter = formatter.notation(options.notation.toIcu())
        formatter = formatter.unitWidth(options.unitDisplay.toIcu())
        formatter = formatter.roundingMode(options.roundingMode)
        formatter = formatter.precision(options.precision())
        return formatter
    }

    private fun currencyFormatter(
        currency: Currency,
        options: NumberFormatOptions,
        currencySign: CurrencySign,
    ): com.ibm.icu.number.LocalizedNumberFormatter = currencyFormatterWithoutLocale(currency, options, currencySign).locale(icuLocale)

    private fun currencyFormatterWithoutLocale(
        currency: Currency,
        options: NumberFormatOptions,
        currencySign: CurrencySign,
    ): com.ibm.icu.number.UnlocalizedNumberFormatter {
        require(currencySign == CurrencySign.STANDARD || options.sign == NumberSign.AUTO) {
            "accounting currency sign cannot be combined with an explicit number sign"
        }
        val configured = numberFormatterWithoutLocale(options).unit(IcuCurrency.getInstance(currency.currencyCode))
        return if (currencySign == CurrencySign.ACCOUNTING) configured.sign(NumberFormatter.SignDisplay.ACCOUNTING) else configured
    }

    private fun dateFormatter(style: DateStyle): DateFormat =
        DateFormat.getDateInstance(style.toIcu(), icuLocale).also { it.timeZone = IcuTimeZone.getTimeZone(zone.id) }

    private fun timeFormatter(style: DateStyle): DateFormat =
        DateFormat.getTimeInstance(style.toIcu(), icuLocale).also { it.timeZone = IcuTimeZone.getTimeZone(zone.id) }

    private fun dateTimeFormatter(
        dateStyle: DateStyle,
        timeStyle: DateStyle,
    ): DateFormat =
        DateFormat.getDateTimeInstance(dateStyle.toIcu(), timeStyle.toIcu(), icuLocale).also {
            it.timeZone =
                IcuTimeZone.getTimeZone(zone.id)
        }

    private fun bounded(value: String): String {
        if (value.utf8Size() >
            limits.maxOutputBytes
        ) {
            throw MessageRenderException("formatted output exceeds ${limits.maxOutputBytes} UTF-8 bytes")
        }
        return value
    }

    private fun boundedRange(
        value: FormattedValue,
        span: java.text.Format.Field,
    ): FormattedRange {
        val text = bounded(value.toString())
        val position = ConstrainedFieldPosition()
        position.constrainField(span)
        val spans = mutableListOf<RangeSpan>()
        while (value.nextPosition(position)) {
            spans += RangeSpan(position.fieldValue as Int, position.start, position.limit)
        }
        val parts = mutableListOf<FormattedRangePart>()
        var cursor = 0
        spans.sortedBy(RangeSpan::start).forEach { part ->
            if (cursor < part.start) parts += FormattedRangePart(RangePartSource.SHARED, text.substring(cursor, part.start))
            parts +=
                FormattedRangePart(
                    if (part.endpoint ==
                        0
                    ) {
                        RangePartSource.START
                    } else {
                        RangePartSource.END
                    },
                    text.substring(part.start, part.end),
                )
            cursor = part.end
        }
        if (cursor < text.length) parts += FormattedRangePart(RangePartSource.SHARED, text.substring(cursor))
        return FormattedRange(text, parts)
    }

    private data class RangeSpan(
        val endpoint: Int,
        val start: Int,
        val end: Int,
    )

    private companion object {
        const val MAX_EXACT_RELATIVE: Long = 9_007_199_254_740_992L
    }
}

private fun NumberFormatOptions.precision(): Precision {
    val base =
        when {
            roundingIncrement != null -> {
                Precision.increment(roundingIncrement)
            }

            minSignificantDigits != null || maxSignificantDigits != null -> {
                Precision.minMaxSignificantDigits(minSignificantDigits ?: 1, maxSignificantDigits ?: 100)
            }

            minFractionDigits != null || maxFractionDigits != null -> {
                Precision.minMaxFraction(minFractionDigits ?: 0, maxFractionDigits ?: 100)
            }

            else -> {
                Precision.unlimited()
            }
        }
    return base.trailingZeroDisplay(trailingZeroDisplay.toIcu())
}

private fun NumberGrouping.toIcu(): NumberFormatter.GroupingStrategy =
    when (this) {
        NumberGrouping.AUTO -> NumberFormatter.GroupingStrategy.AUTO
        NumberGrouping.ALWAYS -> NumberFormatter.GroupingStrategy.ON_ALIGNED
        NumberGrouping.MIN2 -> NumberFormatter.GroupingStrategy.MIN2
        NumberGrouping.NEVER -> NumberFormatter.GroupingStrategy.OFF
    }

private fun NumberSign.toIcu(): NumberFormatter.SignDisplay =
    when (this) {
        NumberSign.AUTO -> NumberFormatter.SignDisplay.AUTO
        NumberSign.ALWAYS -> NumberFormatter.SignDisplay.ALWAYS
        NumberSign.NEVER -> NumberFormatter.SignDisplay.NEVER
        NumberSign.EXCEPT_ZERO -> NumberFormatter.SignDisplay.EXCEPT_ZERO
    }

private fun NumberNotation.toIcu(): Notation =
    when (this) {
        NumberNotation.SIMPLE -> Notation.simple()
        NumberNotation.SCIENTIFIC -> Notation.scientific()
        NumberNotation.ENGINEERING -> Notation.engineering()
        NumberNotation.COMPACT_SHORT -> Notation.compactShort()
        NumberNotation.COMPACT_LONG -> Notation.compactLong()
    }

private fun UnitDisplay.toIcu(): NumberFormatter.UnitWidth =
    when (this) {
        UnitDisplay.NARROW -> NumberFormatter.UnitWidth.NARROW
        UnitDisplay.SHORT -> NumberFormatter.UnitWidth.SHORT
        UnitDisplay.FULL_NAME -> NumberFormatter.UnitWidth.FULL_NAME
        UnitDisplay.ISO_CODE -> NumberFormatter.UnitWidth.ISO_CODE
    }

private fun UnitDisplay.toMeasureWidth(): MeasureFormat.FormatWidth =
    when (this) {
        UnitDisplay.NARROW -> MeasureFormat.FormatWidth.NARROW
        UnitDisplay.SHORT -> MeasureFormat.FormatWidth.SHORT
        UnitDisplay.FULL_NAME, UnitDisplay.ISO_CODE -> MeasureFormat.FormatWidth.WIDE
    }

private fun TrailingZeroDisplay.toIcu(): NumberFormatter.TrailingZeroDisplay =
    when (this) {
        TrailingZeroDisplay.AUTO -> NumberFormatter.TrailingZeroDisplay.AUTO
        TrailingZeroDisplay.HIDE_IF_WHOLE -> NumberFormatter.TrailingZeroDisplay.HIDE_IF_WHOLE
    }

private fun PluralType.toIcu(): PluralRules.PluralType =
    when (this) {
        PluralType.CARDINAL -> PluralRules.PluralType.CARDINAL
        PluralType.ORDINAL -> PluralRules.PluralType.ORDINAL
    }

private fun ListType.toIcu(): ListFormatter.Type =
    when (this) {
        ListType.AND -> ListFormatter.Type.AND
        ListType.OR -> ListFormatter.Type.OR
        ListType.UNITS -> ListFormatter.Type.UNITS
    }

private fun ListWidth.toIcu(): ListFormatter.Width =
    when (this) {
        ListWidth.WIDE -> ListFormatter.Width.WIDE
        ListWidth.SHORT -> ListFormatter.Width.SHORT
        ListWidth.NARROW -> ListFormatter.Width.NARROW
    }

private fun RelativeTimeUnit.toIcu(): RelativeDateTimeFormatter.RelativeDateTimeUnit =
    RelativeDateTimeFormatter.RelativeDateTimeUnit.valueOf(name)

private fun DateStyle.toIcu(): Int =
    when (this) {
        DateStyle.FULL -> DateFormat.FULL
        DateStyle.LONG -> DateFormat.LONG
        DateStyle.MEDIUM -> DateFormat.MEDIUM
        DateStyle.SHORT -> DateFormat.SHORT
    }

private fun dateSkeleton(style: DateStyle): String =
    when (style) {
        DateStyle.FULL -> DateFormat.YEAR_MONTH_WEEKDAY_DAY
        DateStyle.LONG -> DateFormat.YEAR_MONTH_DAY
        DateStyle.MEDIUM -> DateFormat.YEAR_ABBR_MONTH_DAY
        DateStyle.SHORT -> DateFormat.YEAR_NUM_MONTH
    }

private fun timeSkeleton(style: DateStyle): String =
    when (style) {
        DateStyle.FULL, DateStyle.LONG -> DateFormat.HOUR_MINUTE_SECOND
        DateStyle.MEDIUM, DateStyle.SHORT -> DateFormat.HOUR_MINUTE
    }

private fun numeric(value: MessageValue): Number =
    when (value) {
        is MessageValue.IntegerValue -> value.value
        is MessageValue.UnsignedIntegerValue -> value.value.value
        is MessageValue.BigIntegerValue -> value.value
        is MessageValue.DecimalValue -> value.value
        is MessageValue.MoneyValue -> value.amount
        else -> throw MessageRenderException("numeric formatting requires an integer or decimal message value")
    }

private fun decimal(value: Number): BigDecimal =
    when (value) {
        is BigDecimal -> value
        is java.math.BigInteger -> BigDecimal(value)
        is Long -> BigDecimal.valueOf(value)
        else -> BigDecimal(value.toString())
    }

internal fun offsetValue(
    value: MessageValue,
    amount: BigDecimal,
): MessageValue.DecimalValue = MessageValue.DecimalValue(decimal(numeric(value)).add(amount))

internal fun Map<String, String>.withoutUniversal(): Map<String, String> = filterKeys { it !in setOf("u:id", "u:dir") }

internal fun Map<String, String>.numberOptions(extra: Set<String> = emptySet()): NumberFormatOptions {
    val allowed = NUMBER_OPTIONS + extra
    require(keys.all { it in allowed }) { "an unsupported numeric formatter option is present" }
    return NumberFormatOptions(
        grouping = enumOption("grouping", NumberGrouping.AUTO),
        sign = enumOption("sign", NumberSign.AUTO),
        notation = enumOption("notation", NumberNotation.SIMPLE),
        minFractionDigits = integerOption("minFractionDigits"),
        maxFractionDigits = integerOption("maxFractionDigits"),
        minSignificantDigits = integerOption("minSignificantDigits"),
        maxSignificantDigits = integerOption("maxSignificantDigits"),
        roundingIncrement = decimalOption("roundingIncrement"),
        roundingMode = enumOption("roundingMode", RoundingMode.HALF_EVEN),
        trailingZeroDisplay = enumOption("trailingZeroDisplay", TrailingZeroDisplay.AUTO),
        unitDisplay = enumOption("unitDisplay", UnitDisplay.SHORT),
    )
}

internal fun Map<String, String>.currencySign(): CurrencySign {
    require(keys.all { it in NUMBER_OPTIONS + "currencySign" }) { "an unsupported currency formatter option is present" }
    return enumOption("currencySign", CurrencySign.STANDARD)
}

internal fun Map<String, String>.dateStyle(key: String = "style"): DateStyle {
    val allowed = if (key == "style") setOf("style") else setOf("dateStyle", "timeStyle")
    require(keys.all { it in allowed }) { "an unsupported date/time formatter option is present" }
    return enumOption(key, DateStyle.MEDIUM)
}

internal fun Map<String, String>.required(name: String): String = requireNotNull(this[name]) { "formatter option $name is required" }

internal fun Map<String, String>.requiredDecimal(name: String): BigDecimal =
    try {
        BigDecimal(required(name))
    } catch (_: NumberFormatException) {
        throw MessageRenderException("formatter option $name is an exact decimal literal")
    }

private inline fun <reified T : Enum<T>> Map<String, String>.enumOption(
    name: String,
    default: T,
): T {
    val value = this[name] ?: return default
    return enumValues<T>().firstOrNull { entry -> entry.name.equals(value.replace('-', '_'), ignoreCase = true) }
        ?: throw MessageRenderException("formatter option $name has an unsupported value")
}

private fun Map<String, String>.integerOption(name: String): Int? {
    val value = this[name] ?: return null
    return value.toIntOrNull() ?: throw MessageRenderException("formatter option $name is an integer")
}

private fun Map<String, String>.decimalOption(name: String): BigDecimal? =
    this[name]?.let { value ->
        try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            throw MessageRenderException("formatter option $name is an exact decimal literal")
        }
    }

private val NUMBER_OPTIONS: Set<String> =
    setOf(
        "grouping",
        "sign",
        "notation",
        "minFractionDigits",
        "maxFractionDigits",
        "minSignificantDigits",
        "maxSignificantDigits",
        "roundingIncrement",
        "roundingMode",
        "trailingZeroDisplay",
        "unitDisplay",
    )
