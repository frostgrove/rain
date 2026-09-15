package com.gd.rain.core.error

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ErrorCodeTest {
    @ParameterizedTest
    @ValueSource(strings = ["unique", "a", "ticket_closed", "a1_b2"])
    fun `a lower-case snake case code is accepted`(value: String) {
        assertThat(ErrorCode.of(value, "message").value).isEqualTo(value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Unique", "not-found", "1code", "_leading", "with space", "ключ"])
    fun `a malformed code is refused at declaration`(value: String) {
        assertThatThrownBy { ErrorCode.of(value, "message") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(ErrorCode.PATTERN)
    }

    @Test
    fun `a code longer than sixty-four characters is refused`() {
        assertThat(ErrorCode.isWellFormed("a".repeat(64))).isTrue()
        assertThat(ErrorCode.isWellFormed("a".repeat(65))).isFalse()
    }

    @Test
    fun `a code without a default message is refused`() {
        assertThatThrownBy { ErrorCode.of("unique", " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no default message")
    }

    @Test
    fun `two codes with one value are equal whatever their messages`() {
        assertThat(ErrorCode.of("unique", "one")).isEqualTo(ErrorCode.of("unique", "two"))
        assertThat(ErrorCode.of("unique", "one")).isNotEqualTo(ErrorCode.of("restrict", "one"))
    }
}
