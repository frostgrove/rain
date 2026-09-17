package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mf2CompilerTest {
    private val arguments = listOf(ArgumentSpec("count", ArgumentType.INTEGER), ArgumentSpec("name", ArgumentType.TEXT))

    @Test
    fun `compiles typed expressions and structural rich markup`() {
        val result =
            Mf2Compiler.compilePattern(
                "{#strong u:id=person}{${'$'}name :string u:dir=ltr}{/strong} has {${'$'}count :number}",
                arguments,
                OutputKind.RICH,
                setOf("strong"),
                I18nLimits(),
            )

        assertThat(result).isInstanceOf(TemplateCompilation.Compiled::class.java)
        val nodes = (result as TemplateCompilation.Compiled).template.nodes
        assertThat(nodes.filterIsInstance<Mf2Node.Expression>()).hasSize(2)
        assertThat(
            nodes
                .filterIsInstance<Mf2Node.MarkupOpen>()
                .single()
                .metadata.id,
        ).isEqualTo("person")
    }

    @Test
    fun `refuses unknown argument incompatible function and unsafe markup`() {
        val unknown = Mf2Compiler.compilePattern("{${'$'}other}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
        val incompatible = Mf2Compiler.compilePattern("{${'$'}name :number}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
        val markup = Mf2Compiler.compilePattern("{#script}x{/script}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())

        assertThat(unknown).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(incompatible).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(markup).isInstanceOf(TemplateCompilation.Refused::class.java)
    }

    @Test
    fun `refuses unmatched braces markup and universal metadata errors`() {
        val braces = Mf2Compiler.compilePattern("hello {", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
        val tags = Mf2Compiler.compilePattern("{#strong}x", arguments, OutputKind.RICH, setOf("strong"), I18nLimits())
        val direction = Mf2Compiler.compilePattern("{${'$'}name u:dir=sideways}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())

        assertThat(braces).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(tags).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(direction).isInstanceOf(TemplateCompilation.Refused::class.java)
    }

    @Test
    fun `validates formatter options during compilation rather than rendering`() {
        val unknown = Mf2Compiler.compilePattern("{${'$'}count :number nope=value}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
        val missingUnit = Mf2Compiler.compilePattern("{${'$'}count :unit}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
        val offset = Mf2Compiler.compilePattern("{${'$'}count :offset offset=1.5}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())

        assertThat(unknown).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(missingUnit).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(offset).isInstanceOf(TemplateCompilation.Compiled::class.java)
    }

    @Test
    fun `compiles input local and multiple selector declarations`() {
        val source =
            """
            .input {${'$'}kind :string}
            .input {${'$'}count :number select=plural}
            .local ${'$'}next = {${'$'}count :offset add=1}
            .match ${'$'}kind ${'$'}next
            female one {{female-one}}
            female * {{female-other}}
            * one {{other-one}}
            * * {{other-other}}
            """.trimIndent()

        val result =
            Mf2Compiler.compile(
                source,
                arguments + ArgumentSpec("kind", ArgumentType.TEXT),
                OutputKind.PLAIN,
                emptySet(),
                I18nLimits(),
            )

        assertThat(result).isInstanceOf(TemplateCompilation.Compiled::class.java)
        val template = (result as TemplateCompilation.Compiled).template
        assertThat(template.declarations).hasSize(3)
        assertThat(template.select?.selectors).containsExactly("kind", "next")
        assertThat(template.select?.variants).hasSize(4)
    }

    @Test
    fun `refuses a select without a catch all variant or a noncanonical category key`() {
        val noFallback = Mf2Compiler.compile(".match ${'$'}count\none {{one}}", arguments, OutputKind.PLAIN, emptySet(), I18nLimits())
        val badPlural =
            Mf2Compiler.compile(
                ".input {${'$'}count :number select=plural}\n.match ${'$'}count\noen {{bad}}\n* {{other}}",
                arguments,
                OutputKind.PLAIN,
                emptySet(),
                I18nLimits(),
            )

        assertThat(noFallback).isInstanceOf(TemplateCompilation.Refused::class.java)
        assertThat(badPlural).isInstanceOf(TemplateCompilation.Refused::class.java)
    }
}
