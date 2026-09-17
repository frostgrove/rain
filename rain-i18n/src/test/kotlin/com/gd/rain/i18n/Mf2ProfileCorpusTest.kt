package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Executable conformance corpus for the public `rain-mf2/v1` profile documented in
 * `docs/concepts/rain-mf2-v1-corpus.md`.
 *
 * The cases deliberately assert profile acceptance/refusal rather than locale-sensitive formatted
 * words. Formatter locale goldens live at the view boundary; this corpus freezes syntax, contracts
 * and validation before a template can reach it.
 */
class Mf2ProfileCorpusTest {
    private val arguments =
        listOf(
            ArgumentSpec("text", ArgumentType.TEXT),
            ArgumentSpec("kind", ArgumentType.ENUM, enumValues = setOf("female", "male")),
            ArgumentSpec("count", ArgumentType.INTEGER),
            ArgumentSpec("decimal", ArgumentType.DECIMAL),
            ArgumentSpec("money", ArgumentType.MONEY),
            ArgumentSpec("date", ArgumentType.DATE),
            ArgumentSpec("instant", ArgumentType.INSTANT),
        )

    @Test
    fun `every v1 formatter function has one canonical accepted fixture`() {
        val cases =
            mapOf(
                Mf2Function.STRING to "{${'$'}text :string}",
                Mf2Function.NUMBER to "{${'$'}decimal :number}",
                Mf2Function.INTEGER to "{${'$'}count :integer}",
                Mf2Function.CURRENCY to "{${'$'}money :currency}",
                Mf2Function.PERCENT to "{${'$'}decimal :percent}",
                Mf2Function.OFFSET to "{${'$'}count :offset offset=1}",
                Mf2Function.DATE to "{${'$'}date :date}",
                Mf2Function.TIME to "{${'$'}instant :time}",
                Mf2Function.DATETIME to "{${'$'}instant :datetime}",
                Mf2Function.UNIT to "{${'$'}count :unit unit=meter}",
            )

        assertThat(cases.keys).containsExactlyInAnyOrderElementsOf(Mf2Function.entries)
        cases.forEach { (function, source) ->
            assertThat(compile(source)).describedAs(function.name).isInstanceOf(TemplateCompilation.Compiled::class.java)
        }
    }

    @Test
    fun `the corpus accepts declarations selectors nested variants and structural rich metadata`() {
        val selectors =
            """
            .input {${'$'}kind :string select=exact}
            .input {${'$'}count :number select=ordinal}
            .local ${'$'}next = {${'$'}count :offset add=1}
            .match ${'$'}kind ${'$'}next
            female one {{female-first}}
            female * {{female-other}}
            * one {{other-first}}
            * * {{other}}
            """.trimIndent()

        assertThat(compile(selectors)).isInstanceOf(TemplateCompilation.Compiled::class.java)
        assertThat(
            Mf2Compiler.compilePattern(
                "{#strong u:id=person u:dir=ltr}{${'$'}text :string}{/strong}",
                arguments,
                OutputKind.RICH,
                setOf("strong"),
                I18nLimits(),
            ),
        ).isInstanceOf(TemplateCompilation.Compiled::class.java)
    }

    @Test
    fun `the corpus refuses unsupported syntax unsafe markup and underspecified selection`() {
        val refused =
            listOf(
                "{${'$'}text :unknown}",
                "{${'$'}text :number}",
                "{${'$'}count :unit}",
                "{${'$'}count :offset offset=not-a-number}",
                "{${'$'}text u:dir=sideways}",
                ".input {${'$'}count :number select=plural}\n.match ${'$'}count\none {{one}}",
                ".input {${'$'}count :number select=plural}\n.match ${'$'}count\noen {{bad}}\n* {{other}}",
                "{#strong href=https://unsafe.example}x{/strong}",
                "{#script}x{/script}",
            )

        refused.forEach { source ->
            assertThat(compile(source)).describedAs(source).isInstanceOf(TemplateCompilation.Refused::class.java)
        }
    }

    private fun compile(source: String): TemplateCompilation =
        Mf2Compiler.compile(source, arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
}
