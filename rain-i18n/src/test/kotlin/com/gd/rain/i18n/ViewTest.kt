package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.ZoneId

class ViewTest {
    private val en = LocaleTag.parse("en")
    private val ru = LocaleTag.parse("ru")

    @Test
    fun `renders a reviewed translation as structural safe parts`() {
        val source = "{#strong}{${'$'}name :string}{/strong}: {${'$'}count :number}"
        val translated = "{#strong}{${'$'}name :string}{/strong}: {${'$'}count :number} заявок"
        val snapshot = snapshot(source, translated)
        val contract = checkNotNull(snapshot.contract(MessageKey("tickets", "pending")))
        val message =
            snapshot.bind(
                contract,
                MessageArguments.build {
                    text("name", "Алиса")
                    integer("count", 2)
                },
            )
        val view = snapshot.view(ViewSpec(snapshot.resolved(ru), ZoneId.of("UTC")))

        val rendered = view.render(message)

        assertThat(rendered.text).contains("Алиса", "2", "заявок")
        assertThat(rendered.templateLocale).isEqualTo(ru)
        assertThat(rendered.parts.map(RichPart::kind)).contains(RichPartKind.MARKUP_OPEN, RichPartKind.MARKUP_CLOSE, RichPartKind.VALUE)
        assertThat(view.explain(message).templateLocale).isEqualTo(ru)
    }

    @Test
    fun `falls back to source and refuses untrusted bidi controls`() {
        val snapshot = snapshot("hello {${'$'}name}", null)
        val contract = checkNotNull(snapshot.contract(MessageKey("tickets", "pending")))
        val view = snapshot.view(ViewSpec(snapshot.resolved(ru), ZoneId.of("UTC")))

        assertThat(view.render(snapshot.bind(contract, MessageArguments.build { text("name", "Ada") })).templateLocale).isEqualTo(en)
        assertThatThrownBy {
            view.render(snapshot.bind(contract, MessageArguments.build { text("name", "\u202Eunsafe") }))
        }.isInstanceOf(MessageRenderException::class.java)
    }

    @Test
    fun `refuses a resolution minted by another snapshot even with the same locale policy`() {
        val first = snapshot("hello", null)
        val second = snapshot("hello", null)
        val foreign = first.resolve(listOf(LocaleChoice(LocaleSource.USER, ru))) as LocaleResolution.Resolved

        assertThatThrownBy { second.view(ViewSpec(foreign, ZoneId.of("UTC"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun snapshot(
        source: String,
        translated: String?,
    ): CatalogSnapshot {
        val sourceDigest = CatalogDigests.source(Mf2Profile.ID, en, source, "Dashboard")
        val translations =
            translated
                ?.let { text ->
                    listOf(
                        TranslationSpec(
                            ru,
                            text,
                            TranslationReview.APPROVED,
                            sourceDigest,
                            CatalogDigests.review(sourceDigest, ru, text),
                        ),
                    )
                }.orEmpty()
        val result =
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("view", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en, ru), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("tickets", "pending"),
                                1,
                                source,
                                "Dashboard",
                                arguments =
                                    listOf(
                                        ArgumentSpec("name", ArgumentType.TEXT),
                                        ArgumentSpec("count", ArgumentType.INTEGER, required = false),
                                    ),
                                output = OutputKind.RICH,
                                markup = setOf("strong"),
                                translations = translations,
                            ),
                        ),
                ),
            )
        return (result as CatalogCompilation.Compiled).snapshot
    }

    private fun CatalogSnapshot.resolved(locale: LocaleTag): LocaleResolution.Resolved =
        resolve(listOf(LocaleChoice(LocaleSource.USER, locale))) as LocaleResolution.Resolved
}
