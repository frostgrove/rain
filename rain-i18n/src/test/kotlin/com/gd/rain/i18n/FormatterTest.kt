package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

class FormatterTest {
    private val formatter = I18nFormatter(LocaleTag.parse("en-US"), ZoneId.of("America/New_York"))

    @Test
    fun `formats exact decimal values and preserves range provenance`() {
        val number = formatter.number(MessageValue.DecimalValue(BigDecimal("1234.50")))
        val range = formatter.numberRange(MessageValue.IntegerValue(12), MessageValue.IntegerValue(34))

        assertThat(number).contains("1", "234")
        assertThat(range.text).contains("12", "34")
        assertThat(range.parts.map(FormattedRangePart::source)).contains(RangePartSource.START, RangePartSource.END)
        assertThat(range.parts.joinToString(separator = "") { it.text }).isEqualTo(range.text)
    }

    @Test
    fun `formats money unit list dates and relative values through ICU`() {
        val money = formatter.money(MessageValue.MoneyValue(BigDecimal("12.30"), Currency.getInstance("USD")))
        val unit = formatter.unit(MessageValue.IntegerValue(3), UnitIdentifier.parse("meter"))
        val percent = formatter.percent(MessageValue.DecimalValue(BigDecimal("0.25")))
        val list = formatter.list(listOf(MessageValue.Text("alpha"), MessageValue.Text("beta")))
        val date = formatter.date(LocalDate.of(2026, 9, 17))
        val instant = formatter.dateTime(Instant.parse("2026-09-17T12:30:00Z"))
        val relative = formatter.relative(-2, RelativeTimeUnit.DAY)
        val duration = formatter.duration(Duration.ofMillis(1_500))
        val pluralRange = formatter.pluralRange(MessageValue.DecimalValue(BigDecimal("1.0")), MessageValue.IntegerValue(2))
        val dateRange = formatter.dateRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19))
        val timeRange = formatter.timeRange(Instant.parse("2026-09-17T12:30:00Z"), Instant.parse("2026-09-17T14:30:00Z"))

        assertThat(money).isNotBlank()
        assertThat(unit).contains("3")
        assertThat(percent).contains("25")
        assertThat(list).contains("alpha", "beta")
        assertThat(date).isNotBlank()
        assertThat(instant).isNotBlank()
        assertThat(relative).isNotBlank()
        assertThat(duration).contains("1.5")
        assertThat(pluralRange).isNotBlank()
        assertThat(dateRange.parts.joinToString(separator = "") { it.text }).isEqualTo(dateRange.text)
        assertThat(timeRange.parts.joinToString(separator = "") { it.text }).isEqualTo(timeRange.text)
    }

    @Test
    fun `rejects contradictory precision and inexact relative input`() {
        assertThatThrownBy {
            NumberFormatOptions(minFractionDigits = 1, maxSignificantDigits = 2)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            formatter.relative(9_007_199_254_740_993L, RelativeTimeUnit.DAY)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
