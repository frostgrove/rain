package com.gd.rain.persistence.tx

import com.gd.rain.persistence.sql.SqlStates
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import java.time.Duration

/**
 * Runs a whole transaction again when it failed with a SQLState that repetition fixes
 * ([SqlStates.RETRYABLE]); every other failure is rethrown unchanged on the first attempt.
 *
 * It wraps a transaction boundary, never a statement inside one: PostgreSQL has already rolled back a
 * transaction that saw a deadlock. The waits double from [initialDelay] and are capped at [maxDelay];
 * [attempts] counts the first run.
 */
public class TransactionRetry(
    public val attempts: Int,
    public val initialDelay: Duration,
    public val maxDelay: Duration,
) {
    init {
        require(attempts >= 1) { "a transaction runs at least once, got $attempts attempts" }
        require(!initialDelay.isNegative && !initialDelay.isZero) { "the initial delay is positive, got $initialDelay" }
        require(maxDelay >= initialDelay) { "the maximum delay $maxDelay is not below the initial delay $initialDelay" }
    }

    /** The policy the template runs with; exposed so its back-off ladder can be asserted without waiting. */
    public val policy: RetryPolicy =
        RetryPolicy
            .builder()
            .maxRetries((attempts - 1).toLong())
            .delay(initialDelay)
            .multiplier(2.0)
            .maxDelay(maxDelay)
            .predicate(SqlStates::retryable)
            .build()

    private val template = RetryTemplate(policy)

    public fun <T> run(block: () -> T): T =
        try {
            template.execute { block() }
        } catch (exhausted: RetryException) {
            throw exhausted.lastException
        }
}
