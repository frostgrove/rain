package com.gd.rain.core.error

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ViolationTest {
    private val required = RainErrorCodes.REQUIRED
    private val check = RainErrorCodes.CHECK

    @Test
    fun `a path renders as an RFC 6901 pointer`() {
        assertThat(Violation.at(path("items", 0, "email"), required).pointer).isEqualTo("/items/0/email")
        assertThat(Violation.general(required).pointer).isEqualTo("")
    }

    @Test
    fun `tilde and slash are escaped in that order`() {
        assertThat(Violation.pointerOf(path("a/b", "m~n", "~1"))).isEqualTo("/a~1b/m~0n/~01")
    }

    @Test
    fun `a path step is a name or a non-negative index`() {
        assertThatThrownBy { path(1.5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { path(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a message is non-empty and bounded`() {
        assertThatThrownBy { Violation.general(required, "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Violation.general(required, "x".repeat(Violation.MAX_MESSAGE_BYTES + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Violation.general(required, "x".repeat(Violation.MAX_MESSAGE_BYTES)).message).hasSize(Violation.MAX_MESSAGE_BYTES)
    }

    @Test
    fun `the order does not depend on the order violations were raised in`() {
        val raised =
            listOf(
                Violation.at(path("items", 1), check),
                Violation.at(path("items", "name"), check),
                Violation.at(path("items"), required),
                Violation.general(check),
                Violation.at(path("items", 0, "email"), required),
                Violation(path("items"), check, origin = ViolationOrigin.STATE),
            )

        val ordered = raised.sortedWith(Violation.ORDER)

        assertThat(raised.shuffled(java.util.Random(7)).sortedWith(Violation.ORDER)).isEqualTo(ordered)
        assertThat(ordered.map { it.pointer to it.origin }).containsExactly(
            "" to ViolationOrigin.INPUT,
            "/items" to ViolationOrigin.INPUT,
            "/items" to ViolationOrigin.STATE,
            "/items/name" to ViolationOrigin.INPUT,
            "/items/0/email" to ViolationOrigin.INPUT,
            "/items/1" to ViolationOrigin.INPUT,
        )
    }

    @Test
    fun `at one location the code and then the message break the tie`() {
        val first = Violation.general(check, "b")
        val second = Violation.general(required, "a")
        val third = Violation.general(check, "a")

        assertThat(listOf(first, second, third).sortedWith(Violation.ORDER)).containsExactly(third, first, second)
    }
}
