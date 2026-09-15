package com.gd.rain.access.internal.attempt

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The attempt store could not be asked; an attempt is never admitted on a store that did not answer. */
public class AttemptStoreUnavailableException(
    cause: Throwable,
) : RuntimeException("the attempt store could not be asked: ${cause.javaClass.simpleName}", cause)

/**
 * Attempt counters on Redis, shared by every replica.
 *
 * Each key is a counter and a lock under one hash tag, so both live on one cluster slot and one script touches them
 * atomically: `INCR` the counter, `PEXPIRE` it to the window when the increment created it, and `SET lock NX PX lock-for`
 * when the count reached the ceiling. Every operation costs O(1) commands whatever the number of keys, and expiry is
 * the server's: nothing sweeps.
 */
public class RedisAttemptLimiter(
    private val redis: StringRedisTemplate,
    private val prefix: String,
    private val policy: AttemptPolicy,
) : AttemptLimiter {
    private val failure = DefaultRedisScript(FAILURE_SCRIPT, Long::class.javaObjectType)

    override fun admit(attempt: Attempt): Admission =
        guarded {
            val longest =
                AttemptKeys.of(attempt, policy).maxOf { key ->
                    // -2 when the key is absent; a lock is always written with an expiry.
                    redis.getExpire(lockKey(key), TimeUnit.MILLISECONDS)
                }
            if (longest > 0) Admission.Refused(Duration.ofMillis(longest)) else Admission.Admitted
        }

    override fun recordFailure(attempt: Attempt): FailureRecorded =
        guarded {
            val opened =
                AttemptKeys.of(attempt, policy).filter { key ->
                    val result =
                        redis.execute(
                            failure,
                            listOf(counterKey(key), lockKey(key)),
                            policy.window.toMillis().toString(),
                            key.ceiling.toString(),
                            policy.lockFor.toMillis().toString(),
                        )
                    result == OPENED
                }
            FailureRecorded(opened, emptyList())
        }

    override fun recordSuccess(attempt: Attempt) {
        guarded {
            redis.delete(counterKey(AttemptKeys.of(attempt, policy).single { it.kind == AttemptKeyKind.IDENTIFIER }))
        }
    }

    public fun counterKey(key: AttemptKey): String = "$prefix{${key.id}}:n"

    public fun lockKey(key: AttemptKey): String = "$prefix{${key.id}}:l"

    private fun <T> guarded(work: () -> T): T =
        try {
            work()
        } catch (failed: org.springframework.dao.DataAccessException) {
            throw AttemptStoreUnavailableException(failed)
        }

    private companion object {
        const val OPENED = 1L

        val FAILURE_SCRIPT =
            """
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if failures >= tonumber(ARGV[2]) then
              if redis.call('SET', KEYS[2], '1', 'PX', ARGV[3], 'NX') then
                return 1
              end
            end
            return 0
            """.trimIndent()
    }
}
