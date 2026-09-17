package com.gd.rain.i18n.test

import com.gd.rain.i18n.ArgumentSpec
import com.gd.rain.i18n.ArgumentType
import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.DateStyle
import com.gd.rain.i18n.DisplayNameKind
import com.gd.rain.i18n.FormattedRangePart
import com.gd.rain.i18n.I18nFormatter
import com.gd.rain.i18n.ListType
import com.gd.rain.i18n.LocaleChoice
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleResolution
import com.gd.rain.i18n.LocaleSource
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageArguments
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.MessageValue
import com.gd.rain.i18n.Mf2Profile
import com.gd.rain.i18n.NumberFormatOptions
import com.gd.rain.i18n.OutputKind
import com.gd.rain.i18n.PluralType
import com.gd.rain.i18n.RangePartSource
import com.gd.rain.i18n.RelativeTimeUnit
import com.gd.rain.i18n.RichPart
import com.gd.rain.i18n.RichPartKind
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import com.gd.rain.i18n.UnitDisplay
import com.gd.rain.i18n.UnitIdentifier
import com.gd.rain.i18n.ViewSpec
import com.gd.rain.i18n.bind
import com.gd.rain.i18n.view
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

class FormatterGoldenMatrixTest {
    @Test
    fun `matches the complete ICU 78 point 3 formatter golden matrix`() {
        assertThat(listOf("en-US", "ru", "kk").map(::row))
            .containsExactly(
                goldenRow(
                    "en-US",
                    "1,234.5",
                    "1,234",
                    "12.5%",
                    "${'$'}12.3",
                    "3 meters",
                    "3.25",
                    "12.3–34.5",
                    "10% – 25%",
                    "${'$'}12.3 – ${'$'}14.5",
                    "3–5 m",
                    "other",
                    "two",
                    "other",
                    "September 17, 2026",
                    "5:30 PM",
                    "Sep 17, 2026, 5:30 PM",
                    "Sep 17 – 19, 2026",
                    "Sep 17, 2026, 5:30 – 7:30 PM",
                    "5:30 – 7:30 PM",
                    "alpha, beta, and gamma",
                    "2 days ago",
                    "1 hr, 1 min, 1.5 sec",
                    "English",
                    "Kazakhstan",
                    "English (United States)",
                    "US Dollar",
                    "meters",
                ),
                goldenRow(
                    "ru",
                    "1 234,5",
                    "1 234",
                    "12,5 %",
                    "12,3 ${'$'}",
                    "3 метра",
                    "3,25",
                    "12,3–34,5",
                    "10–25 %",
                    "12,3–14,5 ${'$'}",
                    "3–5 м",
                    "few",
                    "other",
                    "few",
                    "17 сентября 2026 г.",
                    "17:30",
                    "17 сент. 2026 г., 17:30",
                    "17–19 сент. 2026 г.",
                    "17 сент. 2026 г., 17:30–19:30",
                    "17:30–19:30",
                    "alpha, beta и gamma",
                    "2 дня назад",
                    "1 ч 1 мин 1,5 с",
                    "английский",
                    "Казахстан",
                    "английский (Соединенные Штаты)",
                    "доллар США",
                    "метры",
                ),
                goldenRow(
                    "kk",
                    "1 234,5",
                    "1 234",
                    "12,5%",
                    "12,3 ${'$'}",
                    "3 метр",
                    "3,25",
                    "12,3–34,5",
                    "10% – 25%",
                    "12,3–14,5 ${'$'}",
                    "3–5 м",
                    "other",
                    "other",
                    "other",
                    "2026 ж. 17 қыркүйек",
                    "17:30",
                    "2026 ж. 17 қыр., 17:30",
                    "2026 ж. 17–19 қыр.",
                    "2026 ж. 17 қыр., 17:30–19:30",
                    "17:30–19:30",
                    "alpha, beta, gamma",
                    "2 күн бұрын",
                    "1 сағ 1 мин 1,5 с",
                    "ағылшын тілі",
                    "Қазақстан",
                    "ағылшын тілі (Америка Құрама Штаттары)",
                    "АҚШ доллары",
                    "метр",
                ),
            )

        listOf("en-US", "ru", "kk")
            .map { locale -> I18nFormatter(LocaleTag.parse(locale), ZoneId.of("Asia/Almaty")) }
            .flatMap { formatter ->
                listOf(
                    formatter.numberRange(MessageValue.IntegerValue(12), MessageValue.IntegerValue(34)),
                    formatter.dateRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19)),
                    formatter.timeRange(Instant.parse("2026-09-17T12:30:00Z"), Instant.parse("2026-09-17T14:30:00Z")),
                )
            }.forEach { range ->
                assertThat(range.parts.joinToString(separator = "", transform = FormattedRangePart::text)).isEqualTo(range.text)
                assertThat(range.parts.map(FormattedRangePart::source)).contains(RangePartSource.START, RangePartSource.END)
            }
    }

    @Test
    fun `renders the same typed rich message through English Russian and Kazakh goldens`() {
        val snapshot = multilingualSnapshot()
        val contract = checkNotNull(snapshot.contract(MessageKey("fixture", "summary")))
        val rendered =
            listOf("en-US", "ru", "kk").associate { locale ->
                val resolution =
                    snapshot.resolve(listOf(LocaleChoice(LocaleSource.EXPLICIT, LocaleTag.parse(locale)))) as LocaleResolution.Resolved
                locale to
                    snapshot.view(ViewSpec(resolution, ZoneId.of("Asia/Almaty"))).render(
                        snapshot.bind(
                            contract,
                            MessageArguments.build {
                                text("name", "Алия")
                                decimal("count", BigDecimal("1234.50"))
                            },
                        ),
                    )
            }

        assertThat(rendered.mapValues { (_, value) -> value.text })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "en-US" to "Hello, \u2068Алия\u2069! You have \u20681,234.5\u2069 tasks.",
                    "ru" to "Здравствуйте, \u2068Алия\u2069! У вас \u20681 234,5\u2069 задач.",
                    "kk" to "Сәлем, \u2068Алия\u2069! Сізде \u20681 234,5\u2069 тапсырма бар.",
                ),
            )
        assertThat(rendered["en-US"]!!.templateLocale).isEqualTo(LocaleTag.parse("en"))
        assertThat(rendered["ru"]!!.templateLocale).isEqualTo(LocaleTag.parse("ru"))
        assertThat(rendered["kk"]!!.templateLocale).isEqualTo(LocaleTag.parse("kk"))
        rendered.values.forEach { value ->
            assertThat(value.parts.map(RichPart::kind)).contains(
                RichPartKind.MARKUP_OPEN,
                RichPartKind.MARKUP_CLOSE,
                RichPartKind.VALUE,
                RichPartKind.BIDI_ISOLATE,
            )
        }
    }

    private fun row(locale: String): Map<String, String> {
        val formatter = I18nFormatter(LocaleTag.parse(locale), ZoneId.of("Asia/Almaty"))
        val number = MessageValue.DecimalValue(BigDecimal("1234.50"))
        val dollars = MessageValue.MoneyValue(BigDecimal("12.30"), Currency.getInstance("USD"))
        val moreDollars = MessageValue.MoneyValue(BigDecimal("14.50"), Currency.getInstance("USD"))
        val instant = Instant.parse("2026-09-17T12:30:00Z")
        return linkedMapOf(
            "locale" to locale,
            "number" to formatter.number(number),
            "integer" to formatter.integer(number),
            "percent" to formatter.percent(MessageValue.DecimalValue(BigDecimal("0.125"))),
            "money" to formatter.money(dollars),
            "unit" to
                formatter.unit(
                    MessageValue.IntegerValue(3),
                    UnitIdentifier.parse("meter"),
                    NumberFormatOptions(unitDisplay = UnitDisplay.FULL_NAME),
                ),
            "offset" to formatter.offset(MessageValue.IntegerValue(3), BigDecimal("0.25")),
            "numberRange" to
                formatter.numberRange(MessageValue.DecimalValue(BigDecimal("12.3")), MessageValue.DecimalValue(BigDecimal("34.5"))).text,
            "percentRange" to
                formatter.percentRange(MessageValue.DecimalValue(BigDecimal("0.1")), MessageValue.DecimalValue(BigDecimal("0.25"))).text,
            "moneyRange" to formatter.moneyRange(dollars, moreDollars).text,
            "unitRange" to
                formatter.unitRange(MessageValue.IntegerValue(3), MessageValue.IntegerValue(5), UnitIdentifier.parse("meter")).text,
            "pluralCardinal" to formatter.plural(MessageValue.IntegerValue(2), PluralType.CARDINAL),
            "pluralOrdinal" to formatter.plural(MessageValue.IntegerValue(2), PluralType.ORDINAL),
            "pluralRange" to formatter.pluralRange(MessageValue.IntegerValue(1), MessageValue.IntegerValue(2)),
            "date" to formatter.date(LocalDate.of(2026, 9, 17), DateStyle.LONG),
            "time" to formatter.time(instant, DateStyle.SHORT),
            "dateTime" to formatter.dateTime(instant, DateStyle.MEDIUM, DateStyle.SHORT),
            "dateRange" to formatter.dateRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19), DateStyle.MEDIUM).text,
            "dateTimeRange" to formatter.dateTimeRange(instant, instant.plusSeconds(7_200), DateStyle.MEDIUM, DateStyle.SHORT).text,
            "timeRange" to formatter.timeRange(instant, instant.plusSeconds(7_200), DateStyle.SHORT).text,
            "list" to
                formatter.list(listOf(MessageValue.Text("alpha"), MessageValue.Text("beta"), MessageValue.Text("gamma")), ListType.AND),
            "relative" to formatter.relative(-2, RelativeTimeUnit.DAY),
            "duration" to formatter.duration(Duration.ofMillis(3_661_500)),
            "language" to formatter.displayName("en", DisplayNameKind.LANGUAGE),
            "region" to formatter.displayName("KZ", DisplayNameKind.REGION),
            "localeName" to formatter.displayName("en-US", DisplayNameKind.LOCALE),
            "currencyName" to formatter.displayName("USD", DisplayNameKind.CURRENCY),
            "unitName" to formatter.displayName("meter", DisplayNameKind.UNIT),
        )
    }

    private fun goldenRow(
        locale: String,
        vararg values: String,
    ): Map<String, String> {
        require(values.size == COLUMNS.size) { "a formatter golden row has ${COLUMNS.size} values" }
        return linkedMapOf("locale" to locale).also { row -> COLUMNS.zip(values).forEach { (name, value) -> row[name] = value } }
    }

    private fun multilingualSnapshot(): CatalogSnapshot {
        val en = LocaleTag.parse("en")
        val source = "{#strong}Hello{/strong}, {${'$'}name :string}! You have {${'$'}count :number} tasks."
        val description = "A rich task count greeting"
        val sourceDigest = CatalogDigests.source(Mf2Profile.ID, en, source, description)

        fun translation(
            locale: String,
            text: String,
        ): TranslationSpec {
            val tag = LocaleTag.parse(locale)
            return TranslationSpec(tag, text, TranslationReview.APPROVED, sourceDigest, CatalogDigests.review(sourceDigest, tag, text))
        }
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("golden", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en, LocaleTag.parse("ru"), LocaleTag.parse("kk")), en),
                    defaultZone = ZoneId.of("Asia/Almaty"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("fixture", "summary"),
                                1,
                                source,
                                description,
                                arguments = listOf(ArgumentSpec("name", ArgumentType.TEXT), ArgumentSpec("count", ArgumentType.DECIMAL)),
                                output = OutputKind.RICH,
                                markup = setOf("strong"),
                                translations =
                                    listOf(
                                        translation(
                                            "ru",
                                            "{#strong}Здравствуйте{/strong}, {${'$'}name :string}! У вас {${'$'}count :number} задач.",
                                        ),
                                        translation(
                                            "kk",
                                            "{#strong}Сәлем{/strong}, {${'$'}name :string}! Сізде {${'$'}count :number} тапсырма бар.",
                                        ),
                                    ),
                            ),
                        ),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
    }

    private companion object {
        val COLUMNS: List<String> =
            listOf(
                "number",
                "integer",
                "percent",
                "money",
                "unit",
                "offset",
                "numberRange",
                "percentRange",
                "moneyRange",
                "unitRange",
                "pluralCardinal",
                "pluralOrdinal",
                "pluralRange",
                "date",
                "time",
                "dateTime",
                "dateRange",
                "dateTimeRange",
                "timeRange",
                "list",
                "relative",
                "duration",
                "language",
                "region",
                "localeName",
                "currencyName",
                "unitName",
            )
    }
}
