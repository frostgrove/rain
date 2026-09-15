package com.gd.rain.persistence.tx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.util.backoff.BackOffExecution
import java.sql.SQLException
import java.time.Duration

class TransactionRetryTest {
    private val immediate = TransactionRetry(attempts = 3, initialDelay = Duration.ofMillis(1), maxDelay = Duration.ofMillis(1))

    @Test
    fun `a deadlock is retried and the second attempt's answer is returned`() {
        var attempts = 0

        val answer =
            immediate.run {
                attempts++
                if (attempts == 1) throw failure("40P01")
                "saved"
            }

        assertThat(answer).isEqualTo("saved")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `a failure repetition cannot fix is rethrown unchanged after one attempt`() {
        var attempts = 0
        val unique = failure("23505")

        assertThatThrownBy {
            immediate.run {
                attempts++
                throw unique
            }
        }.isSameAs(unique)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `an exhausted retry rethrows the last failure, not a wrapper`() {
        var attempts = 0
        val failures = mutableListOf<RuntimeException>()

        assertThatThrownBy {
            immediate.run {
                attempts++
                throw failure("55P03").also(failures::add)
            }
        }.isSameAs(failures.last())
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `one attempt means no retry at all`() {
        var attempts = 0
        val once = TransactionRetry(attempts = 1, initialDelay = Duration.ofMillis(1), maxDelay = Duration.ofMillis(1))

        assertThatThrownBy {
            once.run {
                attempts++.also {
                    throw failure(
                        "40001",
                    )
                }
            }
        }.isInstanceOf(DataAccessResourceFailureException::class.java)
        assertThat(attempts).isEqualTo(1)
    }

    /** The ladder the port replaced: 50 ms doubling, capped, one wait per retry. */
    @Test
    fun `the back-off doubles from the initial delay and is capped`() {
        val retry = TransactionRetry(attempts = 5, initialDelay = Duration.ofMillis(50), maxDelay = Duration.ofMillis(150))
        val execution = retry.policy.backOff.start()

        val waits = generateSequence { execution.nextBackOff() }.takeWhile { it != BackOffExecution.STOP }.toList()

        assertThat(waits).containsExactly(50L, 100L, 150L, 150L)
    }

    @Test
    fun `a nonsensical retry is refused at construction`() {
        assertThatThrownBy {
            TransactionRetry(
                0,
                Duration.ofMillis(1),
                Duration.ofMillis(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TransactionRetry(1, Duration.ZERO, Duration.ofMillis(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TransactionRetry(
                1,
                Duration.ofMillis(2),
                Duration.ofMillis(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun failure(state: String): RuntimeException = DataAccessResourceFailureException("wrapped", SQLException("driver", state))
}
