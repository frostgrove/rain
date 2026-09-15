package com.gd.rain.jobs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class JobProfileTest {
    private val ladder = BackoffLadder(Duration.ofSeconds(5), Duration.ofMinutes(5))

    @Test
    fun `the ceiling doubles from the initial rung and is capped at the maximum`() {
        assertThat((0..8).map { ladder.ceiling(it).toSeconds() }).containsExactly(5, 10, 20, 40, 80, 160, 300, 300, 300)
        assertThat(ladder.ceiling(10_000)).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `the jitter draws over the whole window from the initial rung to the ceiling`() {
        val bounds = mutableListOf<Long>()

        val lowest =
            ladder.delay(3) { bound ->
                bounds += bound
                0
            }
        val highest = ladder.delay(3) { bound -> bound - 1 }

        assertThat(lowest).isEqualTo(Duration.ofSeconds(5))
        assertThat(highest).isEqualTo(Duration.ofSeconds(40))
        assertThat(bounds).containsExactly(Duration.ofSeconds(40).toMillis() - Duration.ofSeconds(5).toMillis() + 1)
    }

    @Test
    fun `a jitter drawing outside its bound is a defect, not a delay`() {
        assertThatThrownBy { ladder.delay(1) { bound -> bound } }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a ladder and a profile refuse values that cannot be meant`() {
        assertThatThrownBy { BackoffLadder(Duration.ZERO, Duration.ofSeconds(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            BackoffLadder(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            JobProfile("x", Duration.ofSeconds(1), Duration.ofSeconds(2), ladder, 1, 1, Duration.ofDays(1))
        }.hasMessageContaining("exceeds the attempt timeout")
        assertThatThrownBy { JobProfile("Bad", Duration.ofSeconds(1), Duration.ofSeconds(1), ladder, 1, 1, Duration.ofDays(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JobProfile("x", Duration.ofSeconds(1), Duration.ofSeconds(1), ladder, -1, 1, Duration.ofDays(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `definition names, priorities, subjects and dedupe keys are bounded`() {
        assertThat(JobDefinition.of<String>("ticket.summarize-v2", "standard").name).isEqualTo("ticket.summarize-v2")
        assertThatThrownBy { JobDefinition.of<String>("Ticket", "standard") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JobDefinition.of<String>("a".repeat(129), "standard") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JobPriority(Short.MAX_VALUE + 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SubjectKey(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Dedupe.Unique("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JobDeferredException(Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
