package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.ZoneId

class MessageBindingTest {
    private val snapshot: CatalogSnapshot =
        compiledSnapshot(
            MessageSpec(
                key = MessageKey("tickets", "pending"),
                contractRevision = 1,
                source = "{${'$'}count :number} tickets for {${'$'}owner :string}",
                description = "Dashboard count",
                arguments =
                    listOf(
                        ArgumentSpec("count", ArgumentType.INTEGER),
                        ArgumentSpec("owner", ArgumentType.TEXT, required = false, nullable = true),
                    ),
            ),
        )

    @Test
    fun `manual and generated bindings use the same exact contract`() {
        val contract = checkNotNull(snapshot.contract(MessageKey("tickets", "pending")))
        val generated = snapshot.definition(contract) { count: Long -> MessageArguments.build { integer("count", count) } }
        val manual = snapshot.definition(contract)

        assertThat(generated.bind(2)).isEqualTo(manual.bind(MessageArguments.build { integer("count", 2) }))
    }

    @Test
    fun `binding refuses missing unknown null and stale arguments`() {
        val contract = checkNotNull(snapshot.contract(MessageKey("tickets", "pending")))

        assertThatThrownBy { snapshot.bind(contract, MessageArguments.of()) }
            .isInstanceOf(MessageBindingException::class.java)
            .hasMessageContaining("count is required")
        assertThatThrownBy {
            snapshot.bind(
                contract,
                MessageArguments.build {
                    integer("count", 1)
                    text("other", "x")
                },
            )
        }.hasMessageContaining("other is not declared")
        assertThatThrownBy {
            snapshot.bind(contract, MessageArguments.build { nullValue("count") })
        }.hasMessageContaining("count is not nullable")
        assertThatThrownBy {
            snapshot.bind(contract.copy(revision = 2), MessageArguments.build { integer("count", 1) })
        }.hasMessageContaining("does not match")
    }

    private fun compiledSnapshot(message: MessageSpec): CatalogSnapshot =
        (
            CatalogCompiler.compile(
                CatalogSpec(
                    identity = CatalogIdentity("binding", icuClDrTzdbIdentity = "icu4j-78.3"),
                    sourceLocale = LocaleTag.parse("en"),
                    localePolicy = LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                    defaultZone = ZoneId.of("UTC"),
                    messages = listOf(message),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
}
