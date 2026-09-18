package com.gd.rain.core.error

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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
    fun `violation parameters are closed typed bounded values`() {
        val parameters =
            ViolationParameters.build {
                text("field", "email")
                integer("max", 64)
                decimal("minimum", BigDecimal("1.25"))
                boolean("inclusive", true)
            }

        assertThat(parameters.text("field")).isEqualTo("email")
        assertThat(parameters.integer("max")).isEqualTo(64)
        assertThat(parameters.decimal("minimum")).isEqualByComparingTo("1.25")
        assertThat(parameters.boolean("inclusive")).isTrue()
        assertThat(parameters.text("max")).isNull()
        assertThatThrownBy {
            ViolationParameters.of(
                ViolationParameter("max", ViolationParameterValue.IntegerValue(1)),
                ViolationParameter("max", ViolationParameterValue.IntegerValue(2)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameter("Max", ViolationParameterValue.IntegerValue(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameterValue.Text("\uD800") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `text and decimal parameter bounds refuse every malformed edge`() {
        assertThat(ViolationParameterValue.Text("a😀b").value).isEqualTo("a😀b")
        assertThatThrownBy { ViolationParameterValue.Text("x".repeat((4 shl 10) + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameterValue.Text("\uD800x") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameterValue.Text("\uDC00") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameterValue.DecimalValue(BigDecimal("9".repeat(129))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameterValue.DecimalValue(BigDecimal.ONE.setScale(129)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ViolationParameterValue.DecimalValue(BigDecimal("1E+129")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a violation parameter collection is capped and every typed getter declines another type`() {
        val values =
            ViolationParameters.build {
                value("text", ViolationParameterValue.Text("x"))
                boolean("flag", false)
                integer("count", 2)
                decimal("amount", BigDecimal.TEN)
            }

        assertThat(values.boolean("text")).isNull()
        assertThat(values.integer("flag")).isNull()
        assertThat(values.decimal("count")).isNull()
        assertThat(values.text("amount")).isNull()
        assertThat(values["absent"]).isNull()
        assertThatThrownBy {
            ViolationParameters.of(
                *(0..ViolationParameters.MAX_ENTRIES)
                    .map { ViolationParameter("value$it", ViolationParameterValue.IntegerValue(it.toLong())) }
                    .toTypedArray(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `parameter ordering is total across names lengths types and values`() {
        fun parameters(
            name: String,
            value: ViolationParameterValue,
        ) = ViolationParameters.of(ViolationParameter(name, value))

        val empty = ViolationParameters.EMPTY
        val textA = parameters("a", ViolationParameterValue.Text("a"))
        val textB = parameters("a", ViolationParameterValue.Text("b"))
        val laterName = parameters("b", ViolationParameterValue.Text("a"))
        val flag = parameters("a", ViolationParameterValue.BooleanValue(false))
        val trueFlag = parameters("a", ViolationParameterValue.BooleanValue(true))
        val count = parameters("a", ViolationParameterValue.IntegerValue(1))
        val ten = parameters("a", ViolationParameterValue.IntegerValue(10))
        val amount = parameters("a", ViolationParameterValue.DecimalValue(BigDecimal.ONE))
        val scaledAmount = parameters("a", ViolationParameterValue.DecimalValue(BigDecimal("1.0")))
        val two = ViolationParameters.of(*textA.entries.toTypedArray(), ViolationParameter("b", ViolationParameterValue.Text("b")))
        val reversedTwo = ViolationParameters.of(*two.entries.reversed().toTypedArray())

        assertThat(empty).isLessThan(textA)
        assertThat(textA).isGreaterThan(empty).isLessThan(textB).isLessThan(laterName)
        assertThat(textA).isLessThan(flag)
        assertThat(flag).isLessThan(trueFlag).isLessThan(count)
        assertThat(count).isLessThan(ten).isLessThan(amount)
        assertThat(amount).isLessThan(scaledAmount)
        assertThat(two).isGreaterThan(textA)
        assertThat(reversedTwo).isEqualTo(two)
        assertThat(reversedTwo.compareTo(two)).isZero()
        assertThat(textA.compareTo(ViolationParameters.of(*textA.entries.toTypedArray()))).isZero()
    }

    @Test
    fun `typed parameters complete the violation total order without entering its public pointer`() {
        val lower = Violation.at(path("age"), check, parameters = ViolationParameters.build { integer("min", 18) })
        val upper = Violation.at(path("age"), check, parameters = ViolationParameters.build { integer("min", 21) })

        assertThat(Violation.ORDER.compare(lower, upper)).isNegative()
        assertThat(lower.pointer).isEqualTo("/age")
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
