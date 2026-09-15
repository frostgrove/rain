package com.gd.rain.resilience

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BreakerNameTest {
    @Test
    fun `a well-formed name is accepted as written`() {
        listOf("model", "legacy_converter", "payments.eu-west", "a" + "b".repeat(63)).forEach {
            assertThat(BreakerName(it).value).isEqualTo(it)
        }
    }

    @Test
    fun `a malformed name is refused where it is made`() {
        listOf("", "Model", "9lives", "has space", "a" + "b".repeat(64), "_leading").forEach { written ->
            assertThatThrownBy { BreakerName(written) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(BreakerName.PATTERN)
        }
    }
}
