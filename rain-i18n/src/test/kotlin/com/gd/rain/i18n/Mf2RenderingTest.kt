package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

class Mf2RenderingTest {
    @Test
    fun `renders locals and the first matching multiple selector variant`() {
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
        val snapshot =
            (
                CatalogCompiler.compile(
                    CatalogSpec(
                        CatalogIdentity("selectors", icuClDrTzdbIdentity = "icu4j-78.3"),
                        LocaleTag.parse("en"),
                        LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                        defaultZone = ZoneId.of("UTC"),
                        messages =
                            listOf(
                                MessageSpec(
                                    MessageKey("app", "result"),
                                    1,
                                    source,
                                    "selector regression",
                                    arguments =
                                        listOf(
                                            ArgumentSpec("kind", ArgumentType.ENUM, enumValues = setOf("female", "male")),
                                            ArgumentSpec("count", ArgumentType.INTEGER),
                                        ),
                                ),
                            ),
                    ),
                ) as CatalogCompilation.Compiled
            ).snapshot
        val contract = checkNotNull(snapshot.contract(MessageKey("app", "result")))
        val en = LocaleTag.parse("en")
        val resolution = snapshot.resolve(listOf(LocaleChoice(LocaleSource.APPLICATION, en))) as LocaleResolution.Resolved
        val view = snapshot.view(ViewSpec(resolution, ZoneId.of("UTC")))

        val female =
            view.render(
                snapshot.bind(
                    contract,
                    MessageArguments.build {
                        value("kind", MessageValue.EnumValue("female"))
                        integer("count", 0)
                    },
                ),
            )
        val other =
            view.render(
                snapshot.bind(
                    contract,
                    MessageArguments.build {
                        value("kind", MessageValue.EnumValue("male"))
                        integer("count", 1)
                    },
                ),
            )

        assertThat(female.text).isEqualTo("female-one")
        assertThat(other.text).isEqualTo("other-other")
    }
}
