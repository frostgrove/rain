package com.gd.rain.i18n.tool

import com.gd.rain.i18n.ArgumentSpec
import com.gd.rain.i18n.ArgumentType
import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogArtifactDecoding
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSourceCodec
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

class CatalogToolTest {
    @Test
    fun `check and compile preserve a strict canonical source through an artifact`() {
        val en = LocaleTag.parse("en")
        val source =
            CatalogSourceCodec().encode(
                CatalogSpec(
                    CatalogIdentity("tool", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages = listOf(MessageSpec(MessageKey("tool", "message"), 1, "hello", "fixture")),
                ),
            )
        val tool = CatalogTool()

        val compiled = tool.compile(source)

        assertThat(tool.check(source)).isEqualTo(CatalogToolCheck.Valid)
        assertThat(compiled).isInstanceOf(CatalogToolCompilation.Compiled::class.java)
        val artifact = (compiled as CatalogToolCompilation.Compiled).artifactBytes()
        assertThat(CatalogArtifactCodec().decode(artifact)).isInstanceOf(CatalogArtifactDecoding.Decoded::class.java)
    }

    @Test
    fun `generators create typed Kotlin binders and public-only TypeScript contracts`() {
        val en = LocaleTag.parse("en")
        val source =
            CatalogSourceCodec().encode(
                CatalogSpec(
                    CatalogIdentity("tool", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("tool", "public_title"),
                                1,
                                "{${'$'}count :number} {${'$'}when :string}",
                                "Visible title",
                                arguments =
                                    listOf(
                                        ArgumentSpec("count", ArgumentType.INTEGER),
                                        ArgumentSpec("when", ArgumentType.TEXT, required = false),
                                    ),
                                public = true,
                            ),
                            MessageSpec(MessageKey("tool", "private_note"), 1, "Private wording", "Not exported"),
                        ),
                ),
            )
        val tool = CatalogTool()

        val kotlin = tool.generateKotlin(source, "com.example.catalog")
        val typescript = tool.exportTypeScript(source)

        assertThat(kotlin).isInstanceOf(CatalogToolKotlinGeneration.Generated::class.java)
        val kotlinSource = (kotlin as CatalogToolKotlinGeneration.Generated).source
        assertThat(kotlinSource.relativePath).isEqualTo("com/example/catalog/RainI18nContracts.kt")
        assertThat(kotlinSource.content)
            .contains(
                "public data class Message2Args",
                "public val when_: OptionalValue<String>",
                "value(\"when\"",
                "@RainI18nGeneratedBinding(",
                "key = \"tool.public_title\"",
            )
        assertThat(typescript).isInstanceOf(CatalogToolTypeScriptExport.Exported::class.java)
        val export = (typescript as CatalogToolTypeScriptExport.Exported).export
        assertThat(export.declarationsBytes().toString(Charsets.UTF_8)).contains("tool.public_title").doesNotContain("tool.private_note")
        assertThat(export.manifestBytes().toString(Charsets.UTF_8))
            .contains("tool.public_title", "\"formattingParity\":false")
            .doesNotContain("private_note", "Private wording", "Visible title")
    }

    @Test
    fun `Kotlin generator represents a message without arguments as a singleton argument value`() {
        val en = LocaleTag.parse("en")
        val source =
            CatalogSourceCodec().encode(
                CatalogSpec(
                    CatalogIdentity("tool", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages = listOf(MessageSpec(MessageKey("tool", "ready"), 1, "Ready", "No arguments")),
                ),
            )

        val generated = (CatalogTool().generateKotlin(source, "com.example.catalog") as CatalogToolKotlinGeneration.Generated).source

        assertThat(generated.content)
            .contains("public object Message1Args")
            .doesNotContain("data class Message1Args(")
    }

    @Test
    fun `pseudo locales preserve expressions and restamp checked approved translations`() {
        val en = LocaleTag.parse("en")
        val source =
            CatalogSourceCodec().encode(
                CatalogSpec(
                    CatalogIdentity("tool", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("tool", "greeting"),
                                1,
                                "Hello {${'$'}name :string}",
                                "Greeting",
                                arguments = listOf(ArgumentSpec("name", ArgumentType.TEXT)),
                            ),
                        ),
                ),
            )
        val tool = CatalogTool()

        val accent = tool.pseudo(source, PseudoLocaleProfile.ACCENT)

        assertThat(accent).isInstanceOf(CatalogToolPseudo.Pseudoed::class.java)
        val accentSource = (accent as CatalogToolPseudo.Pseudoed).sourceBytes()
        assertThat(tool.check(accentSource)).isEqualTo(CatalogToolCheck.Valid)
        val accentDecoded = CatalogSourceCodec().decode(accentSource) as com.gd.rain.i18n.CatalogSourceDecoding.Decoded
        assertThat(accentDecoded.source.localePolicy.supported).contains(LocaleTag.parse("en-XA"))
        assertThat(
            accentDecoded.source.messages
                .single()
                .translations
                .single()
                .text,
        ).isEqualTo("［Ħēľľō ］{${'$'}name :string}")

        val rtl = tool.pseudo(source, PseudoLocaleProfile.RTL)

        assertThat(rtl).isInstanceOf(CatalogToolPseudo.Pseudoed::class.java)
        val rtlSource = (rtl as CatalogToolPseudo.Pseudoed).sourceBytes()
        assertThat(tool.check(rtlSource)).isEqualTo(CatalogToolCheck.Valid)
        val rtlDecoded = CatalogSourceCodec().decode(rtlSource) as com.gd.rain.i18n.CatalogSourceDecoding.Decoded
        assertThat(rtlDecoded.source.localePolicy.supported).contains(LocaleTag.parse("ar-XB"))
        assertThat(
            rtlDecoded.source.messages
                .single()
                .translations
                .single()
                .text,
        ).isEqualTo("［ ōľľēĦ］{${'$'}name :string}")
    }

    @Test
    fun `review stamps exact source identity and merge carries only compatible work without pruning`() {
        val en = LocaleTag.parse("en")
        val ru = LocaleTag.parse("ru")
        val base =
            CatalogSpec(
                CatalogIdentity("merge", icuClDrTzdbIdentity = "icu4j-78.3"),
                en,
                LocalePolicy(setOf(en, ru), en),
                defaultZone = ZoneId.of("UTC"),
                messages =
                    listOf(
                        MessageSpec(
                            MessageKey("tool", "ready"),
                            1,
                            "Ready",
                            "Ready state",
                            translations = listOf(TranslationSpec(ru, "Готово", TranslationReview.REQUIRED)),
                        ),
                        MessageSpec(MessageKey("tool", "obsolete"), 1, "Old", "Retained until explicit prune"),
                    ),
            )
        val incoming = base.copy(messages = listOf(base.messages.first().copy(translations = emptyList())))
        val codec = CatalogSourceCodec()
        val tool = CatalogTool()

        val reviewed = tool.review(codec.encode(base), MessageKey("tool", "ready"), ru, TranslationReviewAction.APPROVE)
        val reviewedSource = (reviewed as CatalogToolSourceMutation.Mutated).sourceBytes()
        val merged = tool.merge(reviewedSource, codec.encode(incoming))

        assertThat(tool.check(reviewedSource)).isEqualTo(CatalogToolCheck.Valid)
        assertThat(merged).isInstanceOf(CatalogToolMerge.Merged::class.java)
        val result = merged as CatalogToolMerge.Merged
        assertThat(result.carriedTranslations).isEqualTo(1)
        assertThat(result.retainedObsoleteKeys).containsExactly(MessageKey("tool", "obsolete"))
        val decoded = codec.decode(result.sourceBytes()) as com.gd.rain.i18n.CatalogSourceDecoding.Decoded
        assertThat(decoded.source.messages.map { it.key }).containsExactly(MessageKey("tool", "obsolete"), MessageKey("tool", "ready"))
        assertThat(
            decoded.source.messages
                .single { it.key == MessageKey("tool", "ready") }
                .translations
                .single()
                .review,
        ).isEqualTo(TranslationReview.APPROVED)
    }
}
