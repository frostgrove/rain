package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

class CatalogCompilerTest {
    private val en = LocaleTag.parse("en")
    private val ru = LocaleTag.parse("ru")

    @Test
    fun `compiles only reviewed translations with current source identity`() {
        val sourceDigest = CatalogDigests.source("rain-mf2/v1", en, "{${'$'}count :number} tickets", "Shown on the dashboard")
        val translation =
            TranslationSpec(
                locale = ru,
                text = "{${'$'}count :number} заявок",
                review = TranslationReview.APPROVED,
                reviewedSource = sourceDigest,
                reviewDigest = CatalogDigests.review(sourceDigest, ru, "{${'$'}count :number} заявок"),
            )

        val result = CatalogCompiler.compile(catalog(message(translations = listOf(translation))))

        assertThat(result).isInstanceOf(CatalogCompilation.Compiled::class.java)
        val snapshot = (result as CatalogCompilation.Compiled).snapshot
        assertThat(snapshot.contract(MessageKey("tickets", "pending"))).isNotNull
        assertThat(snapshot.message(MessageKey("tickets", "pending"))?.translations).containsKey(ru)
    }

    @Test
    fun `refuses stale review, missing coverage and duplicate argument deterministically`() {
        val stale =
            TranslationSpec(
                locale = ru,
                text = "заявки",
                review = TranslationReview.APPROVED,
                reviewedSource = CatalogDigests.source("rain-mf2/v1", en, "old", "Shown on the dashboard"),
                reviewDigest =
                    CatalogDigests.review(
                        CatalogDigests.source("rain-mf2/v1", en, "old", "Shown on the dashboard"),
                        ru,
                        "заявки",
                    ),
            )
        val invalid =
            message(
                arguments = listOf(ArgumentSpec("count", ArgumentType.INTEGER), ArgumentSpec("count", ArgumentType.INTEGER)),
                translations = listOf(stale),
            )

        val result = CatalogCompiler.compile(catalog(invalid))

        assertThat(result).isInstanceOf(CatalogCompilation.Refused::class.java)
        assertThat((result as CatalogCompilation.Refused).problems.map(CatalogProblem::message))
            .contains("declares argument count more than once", "does not match current source wording")
    }

    @Test
    fun `catalog digest is independent of declaration ordering`() {
        val first = message(key = MessageKey("tickets", "first"))
        val second = message(key = MessageKey("tickets", "second"))

        val left =
            (CatalogCompiler.compile(catalog(first, second, requiredLocales = emptySet())) as CatalogCompilation.Compiled).snapshot.digest
        val right =
            (CatalogCompiler.compile(catalog(second, first, requiredLocales = emptySet())) as CatalogCompilation.Compiled).snapshot.digest

        assertThat(left).isEqualTo(right)
    }

    @Test
    fun `snapshot digest covers resolution policy and limits, not only message digests`() {
        val declared = message()
        val defaultEn =
            (CatalogCompiler.compile(catalog(declared, requiredLocales = emptySet())) as CatalogCompilation.Compiled).snapshot
        val defaultRu =
            (
                CatalogCompiler.compile(
                    CatalogSpec(
                        CatalogIdentity("2026-09-17", icuClDrTzdbIdentity = "icu4j-78.3"),
                        en,
                        LocalePolicy(setOf(en, ru), ru),
                        defaultZone = ZoneId.of("UTC"),
                        messages = listOf(declared),
                    ),
                ) as CatalogCompilation.Compiled
            ).snapshot

        assertThat(defaultEn.digest).isNotEqualTo(defaultRu.digest)
    }

    @Test
    fun `compiler applies total catalog byte ceiling to direct declarations too`() {
        val result =
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("small", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en), en),
                    defaultZone = ZoneId.of("UTC"),
                    limits = I18nLimits(maxCatalogBytes = 80, maxTemplateBytes = 100),
                    messages = listOf(MessageSpec(MessageKey("app", "text"), 1, "x".repeat(70), "description")),
                ),
            )

        assertThat(result).isInstanceOf(CatalogCompilation.Refused::class.java)
        assertThat((result as CatalogCompilation.Refused).problems.map(CatalogProblem::path)).contains("catalog")
    }

    private fun catalog(
        vararg messages: MessageSpec,
        requiredLocales: Set<LocaleTag> = setOf(ru),
    ): CatalogSpec =
        CatalogSpec(
            identity = CatalogIdentity("2026-09-17", icuClDrTzdbIdentity = "icu4j-78.3"),
            sourceLocale = en,
            localePolicy = LocalePolicy(setOf(en, ru), en),
            requiredLocales = requiredLocales,
            defaultZone = ZoneId.of("UTC"),
            messages = messages.toList(),
        )

    private fun message(
        key: MessageKey = MessageKey("tickets", "pending"),
        arguments: List<ArgumentSpec> = listOf(ArgumentSpec("count", ArgumentType.INTEGER)),
        translations: List<TranslationSpec> = emptyList(),
    ): MessageSpec =
        MessageSpec(
            key = key,
            contractRevision = 1,
            source = "{${'$'}count :number} tickets",
            description = "Shown on the dashboard",
            arguments = arguments,
            translations = translations,
        )
}
