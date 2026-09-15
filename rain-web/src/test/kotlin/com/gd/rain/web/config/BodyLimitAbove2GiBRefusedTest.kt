package com.gd.rain.web.config

import com.gd.rain.core.config.ProblemCode
import com.gd.rain.web.filter.BodyLimitFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize

/** Gap 32: a body limit above what a servlet container counts in an `int` is refused, never truncated. */
class BodyLimitAbove2GiBRefusedTest {
    @Test
    fun `a limit of exactly two gibibytes is one byte too many and refused`() {
        assertThat(webProblems("rain.web.body-limit" to "2GB").map { it.path to it.code })
            .containsExactly("rain.web.body-limit" to ProblemCode.INVALID)
        assertThat(webProblems("rain.web.body-limit" to "3GB").single().message).contains("2147483647 bytes")
    }

    @Test
    fun `the largest int is the largest accepted limit, and zero is refused`() {
        assertThat(webProblems("rain.web.body-limit" to "${Int.MAX_VALUE}B")).isEmpty()
        assertThat(webProblems("rain.web.body-limit" to "0B").map { it.path to it.code })
            .containsExactly("rain.web.body-limit" to ProblemCode.INVALID)
    }

    @Test
    fun `the filter refuses a limit it cannot count instead of wrapping it`() {
        assertThat(BodyLimitFilter.limitBytesOf(DataSize.ofBytes(Int.MAX_VALUE.toLong()))).isEqualTo(Int.MAX_VALUE)
        assertThatThrownBy { BodyLimitFilter.limitBytesOf(DataSize.ofGigabytes(3)) }.isInstanceOf(ArithmeticException::class.java)
        assertThatThrownBy { BodyLimitFilter.limitBytesOf(DataSize.ofBytes(0)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
