package com.gd.rain.core.lock

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * [keyOf] against reference FNV-1a 64 values computed independently over the same NUL-separated
 * UTF-8 bytes. A derivation that disagreed by one byte would make two builds take two different
 * locks for one name, and neither would wait for the other.
 */
class LockKeyTest {
    @ParameterizedTest(name = "keyOf({0}) = {1}")
    @MethodSource("vectors")
    fun `the key matches the reference value`(
        parts: List<String>,
        expected: Long,
    ) {
        assertThat(keyOf(*parts.toTypedArray()).value).isEqualTo(expected)
    }

    @Test
    fun `the separator keeps differently split parts apart`() {
        assertThat(keyOf("a", "bc")).isNotEqualTo(keyOf("ab", "c"))
        assertThat(keyOf("scope", "1")).isEqualTo(keyOf("scope", "1"))
    }

    @Test
    fun `a guard carries the mode it is taken in`() {
        val key = keyOf("ticket", "42")

        assertThat(Exclusively(key).shared).isFalse()
        assertThat(Sharing(key).shared).isTrue()
        assertThat(Sharing(key).key).isEqualTo(key)
    }

    private companion object {
        const val ID = "0199a7f0-6b1c-7c3e-9a2d-4f5b6c7d8e9f"

        @JvmStatic
        fun vectors(): List<Arguments> =
            listOf(
                Arguments.of(listOf<String>(), -3750763034362895579L),
                Arguments.of(listOf(""), -3750763034362895579L),
                Arguments.of(listOf("a", "b"), -1886276961191958862L),
                Arguments.of(listOf("a", "bc"), -6106610056285866717L),
                Arguments.of(listOf("ab", "c"), -188658036487747481L),
                Arguments.of(listOf("scope", "x"), 5180960232363221067L),
                Arguments.of(listOf("contract", ID), -7947240937313967631L),
                Arguments.of(listOf("language", "offered"), 6671992922938116168L),
                Arguments.of(listOf("risk/publish", ID), 5342362775975564138L),
                Arguments.of(listOf("structure/publish", ID), -8127077410486173978L),
                Arguments.of(listOf("договор", "аренда"), -4992253391463068734L),
                Arguments.of(listOf("a", "", "b"), -6016829434302084662L),
            )
    }
}
