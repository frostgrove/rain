package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

class SourceCodecTest {
    private val en = LocaleTag.parse("en")
    private val ru = LocaleTag.parse("ru")

    @Test
    fun `canonical source codec round trips deterministically without data binding`() {
        val sourceText = "Hello {${'$'}name}"
        val sourceDigest = CatalogDigests.source(Mf2Profile.ID, en, sourceText, "Greeting")
        val source =
            CatalogSpec(
                CatalogIdentity("codec", icuClDrTzdbIdentity = "icu4j-78.3"),
                en,
                LocalePolicy(setOf(en, ru), en),
                defaultZone = ZoneId.of("UTC"),
                messages =
                    listOf(
                        MessageSpec(
                            MessageKey("app", "hello"),
                            1,
                            sourceText,
                            "Greeting",
                            listOf(ArgumentSpec("name", ArgumentType.TEXT)),
                            translations =
                                listOf(
                                    TranslationSpec(
                                        ru,
                                        "Привет, {${'$'}name}",
                                        TranslationReview.APPROVED,
                                        sourceDigest,
                                        CatalogDigests.review(sourceDigest, ru, "Привет, {${'$'}name}"),
                                    ),
                                ),
                        ),
                    ),
            )
        val codec = CatalogSourceCodec()

        val canonical = codec.encode(source)
        val decoded = codec.decode(canonical) as CatalogSourceDecoding.Decoded

        assertThat(codec.encode(decoded.source)).isEqualTo(canonical)
        assertThat(CatalogCompiler.compile(decoded.source)).isInstanceOf(CatalogCompilation.Compiled::class.java)
    }

    @Test
    fun `strict decoder refuses an unknown or duplicate root member`() {
        val codec = CatalogSourceCodec()
        val unknown = codec.decode("""{"unknown":true}""".toByteArray())
        val duplicate = codec.decode("""{"schema":"rain.i18n.source/v1","schema":"rain.i18n.source/v1"}""".toByteArray())

        assertThat(unknown).isInstanceOf(CatalogSourceDecoding.Refused::class.java)
        assertThat(duplicate).isInstanceOf(CatalogSourceDecoding.Refused::class.java)
    }

    @Test
    fun `strict decoder refuses a semantically valid document whose bytes are not canonical`() {
        val codec = CatalogSourceCodec()
        val source =
            CatalogSpec(
                CatalogIdentity("canonical", icuClDrTzdbIdentity = "icu4j-78.3"),
                en,
                LocalePolicy(setOf(en), en),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("app", "title"), 1, "Title", "title")),
            )
        val canonical = codec.encode(source)
        val withLeadingWhitespace = byteArrayOf(' '.code.toByte()) + canonical

        val refused = codec.decode(withLeadingWhitespace)

        assertThat(refused).isEqualTo(
            CatalogSourceDecoding.Refused(
                listOf(SourceCodecProblem("$", "source bytes are not in canonical rain.i18n.source/v1 form")),
            ),
        )
    }
}
