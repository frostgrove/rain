package com.gd.rain.persistence.sql

import java.sql.SQLException

/**
 * The SQLState a failure carries, read from the chain rather than from message text.
 *
 * Spring wraps the driver's exception in a `DataAccessException`, and the PostgreSQL driver chains a
 * batch failure through [SQLException.getNextException]; both links are followed, and each exception
 * is visited once.
 */
public object SqlStates {
    public const val UNIQUE_VIOLATION: String = "23505"
    public const val FOREIGN_KEY_VIOLATION: String = "23503"
    public const val RESTRICT_VIOLATION: String = "23001"
    public const val NOT_NULL_VIOLATION: String = "23502"
    public const val CHECK_VIOLATION: String = "23514"
    public const val EXCLUSION_VIOLATION: String = "23P01"
    public const val STRING_DATA_RIGHT_TRUNCATION: String = "22001"
    public const val NUMERIC_VALUE_OUT_OF_RANGE: String = "22003"
    public const val INVALID_TEXT_REPRESENTATION: String = "22P02"
    public const val DEADLOCK_DETECTED: String = "40P01"
    public const val SERIALIZATION_FAILURE: String = "40001"
    public const val IN_FAILED_SQL_TRANSACTION: String = "25P02"
    public const val LOCK_NOT_AVAILABLE: String = "55P03"
    public const val QUERY_CANCELED: String = "57014"

    /** The failures that go away when the whole transaction is simply run again. */
    public val RETRYABLE: Set<String> = setOf(DEADLOCK_DETECTED, LOCK_NOT_AVAILABLE, SERIALIZATION_FAILURE)

    public fun of(failure: Throwable): String? {
        val pending = ArrayDeque<Throwable>().apply { add(failure) }
        val seen = HashSet<Throwable>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is SQLException) {
                current.sqlState?.takeIf(String::isNotBlank)?.let { return it }
                current.nextException?.let(pending::add)
            }
            current.cause?.let(pending::add)
        }
        return null
    }

    public fun retryable(failure: Throwable): Boolean = of(failure) in RETRYABLE
}
