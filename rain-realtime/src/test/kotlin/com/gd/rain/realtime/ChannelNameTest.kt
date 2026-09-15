package com.gd.rain.realtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The channel rules, and the 63-byte refusal the type exists for: PostgreSQL truncates an identifier at
 * `NAMEDATALEN - 1` bytes silently, so two names that agree in their first 63 bytes would be one channel.
 */
class ChannelNameTest {
    @Test
    fun `a channel carrying a UUID fits, with room to spare`() {
        val channel = Channel.of("topic:${UUID.randomUUID()}")

        assertThat(channel.name).hasSize(42)
        assertThat(channel.name.toByteArray(Charsets.UTF_8).size).isLessThan(NotifyRules.MAX_CHANNEL_NAME_BYTES)
    }

    @Test
    fun `sixty-three bytes is allowed and sixty-four is refused`() {
        assertThat(Channel.of("a".repeat(63)).name).hasSize(63)

        assertThatThrownBy { Channel.of("a".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("64 bytes")
            .hasMessageContaining("truncates")
    }

    /**
     * The ceiling is measured in bytes, because PostgreSQL's is. The allowed set is ASCII, so a name
     * outside it is refused for its characters; a name long enough in bytes is refused for that first.
     */
    @Test
    fun `a multi-byte name is refused for its characters, and a long one for its bytes first`() {
        assertThatThrownBy { Channel.of("д".repeat(31)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not allowed")

        assertThatThrownBy { Channel.of("д".repeat(40)) }
            .describedAs("80 bytes, so the byte ceiling answers first")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("80 bytes")
    }

    @ParameterizedTest
    @ValueSource(strings = ["topic:1", "a_b-c.d", "ABC123", "x"])
    fun `the allowed set round-trips`(name: String) {
        assertThat(Channel.of(name).name).isEqualTo(name)
    }

    /** No quote and no backslash can be in a name, so the quoted identifier `LISTEN` takes cannot be ended early. */
    @ParameterizedTest
    @ValueSource(strings = ["topic 1", "a\"b", "a;b", "a'b", "a\\b", "приказ", "a b"])
    fun `anything outside it is refused, naming the character`(name: String) {
        assertThatThrownBy { Channel.of(name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not allowed")
    }

    @Test
    fun `an empty name is refused`() {
        assertThatThrownBy { Channel.of("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot be empty")
    }

    /** The old `ofOrNull` answered null, and the listener dropped what it could not parse without a trace. */
    @Test
    fun `parse names the rule that refuses a name instead of answering null`() {
        assertThat(Channel.parse("topic:1")).isEqualTo(ChannelParse.Valid(Channel.of("topic:1")))
        assertThat(Channel.parse("")).matches({ it is ChannelParse.Invalid && it.rule == ChannelRule.EMPTY }, "refused as empty")
        assertThat(
            Channel.parse("a".repeat(64)),
        ).matches({ it is ChannelParse.Invalid && it.rule == ChannelRule.TOO_LONG }, "refused as too long")
        assertThat(
            Channel.parse("a b"),
        ).matches({ it is ChannelParse.Invalid && it.rule == ChannelRule.CHARACTER }, "refused for a character")
    }

    @Test
    fun `the name is written into LISTEN as a quoted identifier`() {
        assertThat(Channel.of("topic:1").quoted()).isEqualTo("\"topic:1\"")
    }

    @Test
    fun `the declared payload limit is the one PostgreSQL derives from its block and name sizes`() {
        assertThat(NotifyRules.PAYLOAD_LIMIT_BYTES).isEqualTo(8000)
        assertThat(NotifyRules.MAX_PAYLOAD_BYTES).isEqualTo(7999)
    }
}
