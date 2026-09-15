package com.gd.rain.access.it

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.Admission
import com.gd.rain.access.internal.attempt.Attempt
import com.gd.rain.access.internal.attempt.AttemptKeyKind
import com.gd.rain.access.internal.attempt.AttemptPolicy
import com.gd.rain.access.internal.attempt.AttemptStoreUnavailableException
import com.gd.rain.access.internal.attempt.RedisAttemptLimiter
import com.gd.rain.access.internal.revocation.RedisRevocationList
import com.gd.rain.access.internal.revocation.RevocationUnavailableException
import com.gd.rain.access.internal.revocation.RevocationVerdict
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.support.ACCESS_TTL
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.RedisFactories
import com.gd.rain.access.support.START
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainRedis
import com.gd.rain.test.RedisPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The revocation list on a real Redis: what it records, for how long, and that it fails closed. */
@Tag("integration")
class RevocationListIT {
    private val factory = RedisFactories.of(RainRedis.shared(RedisPolicy.RETAINING))
    private val redis = StringRedisTemplate(factory)
    private val clock = MutableClock(START)
    private val prefix = "revocations-${UUID.randomUUID()}:"
    private val list = RedisRevocationList(redis, prefix, ACCESS_TTL, clock)
    private val subject = SubjectRef(AGENT, UUID.randomUUID())

    @AfterEach
    fun close() {
        factory.destroy()
    }

    private fun ttlOf(key: String): Long = redis.getExpire(key, TimeUnit.MILLISECONDS)

    @Test
    fun `a closed session reads revoked for as long as a token can outlive it, and nothing else does`() {
        val closed = UUID.randomUUID()
        val open = UUID.randomUUID()

        list.announceSessions(listOf(RevokedSession(closed, START)))

        assertThat(list.verdict(closed, subject, START.minusSeconds(60))).isEqualTo(RevocationVerdict.REVOKED)
        assertThat(list.verdict(open, subject, START.minusSeconds(60))).isEqualTo(RevocationVerdict.LIVE)
        assertThat(ttlOf(list.sessionKey(closed))).isBetween(1L, ACCESS_TTL.toMillis())
    }

    @Test
    fun `a session closed longer ago than a token lives writes no key`() {
        val closed = UUID.randomUUID()

        list.announceSessions(listOf(RevokedSession(closed, START.minus(ACCESS_TTL).minusSeconds(1))))

        assertThat(redis.hasKey(list.sessionKey(closed))).isFalse()
    }

    @Test
    fun `a cutoff is one key that closes what was issued up to it except the kept session, and never moves back`() {
        val kept = UUID.randomUUID()
        val other = UUID.randomUUID()

        list.announceCutoff(SubjectCutoff(subject, START, kept))
        list.announceCutoff(SubjectCutoff(subject, START.minusSeconds(30), null))

        assertThat(list.verdict(other, subject, START.minusSeconds(1))).isEqualTo(RevocationVerdict.REVOKED)
        assertThat(list.verdict(kept, subject, START.minusSeconds(1))).isEqualTo(RevocationVerdict.LIVE)
        assertThat(list.verdict(UUID.randomUUID(), subject, START.plusMillis(1))).isEqualTo(RevocationVerdict.LIVE)
        assertThat(list.verdict(other, SubjectRef(AGENT, UUID.randomUUID()), START.minusSeconds(1))).isEqualTo(RevocationVerdict.LIVE)
        assertThat(redis.keys("$prefix*")).containsExactly(list.cutoffKey(subject))
        assertThat(ttlOf(list.cutoffKey(subject))).isBetween(1L, ACCESS_TTL.toMillis())
    }

    @Test
    fun `a list that cannot be reached fails closed on every call`() {
        val unreachable = RedisFactories.unreachable()
        try {
            val down = RedisRevocationList(StringRedisTemplate(unreachable), prefix, ACCESS_TTL, clock)

            assertThatThrownBy { down.verdict(UUID.randomUUID(), subject, START) }.isInstanceOf(RevocationUnavailableException::class.java)
            assertThatThrownBy { down.announceSessions(listOf(RevokedSession(UUID.randomUUID(), START))) }
                .isInstanceOf(RevocationUnavailableException::class.java)
            assertThatThrownBy { down.announceCutoff(SubjectCutoff(subject, START, null)) }
                .isInstanceOf(RevocationUnavailableException::class.java)
        } finally {
            unreachable.destroy()
        }
    }
}

/** Gap 18: attempt counters on a real Redis, shared by every replica, each key expiring on its own. */
@Tag("integration")
class RedisAttemptLimiterIT {
    private val factory = RedisFactories.of(RainRedis.shared(RedisPolicy.RETAINING))
    private val redis = StringRedisTemplate(factory)
    private val prefix = "attempts-${UUID.randomUUID()}:"
    private val policy = AttemptPolicy(perIdentifier = 3, perAddress = 5, window = Duration.ofMinutes(15), lockFor = Duration.ofMinutes(10))
    private val attempt = Attempt(AGENT, "ada@example.test", "203.0.113.7")

    private fun replica() = RedisAttemptLimiter(StringRedisTemplate(factory), prefix, policy)

    @AfterEach
    fun close() {
        factory.destroy()
    }

    @Test
    fun `failures counted on two replicas lock the identifier once, for the declared time`() {
        val first = replica()
        val second = replica()

        assertThat(first.recordFailure(attempt).opened).isEmpty()
        assertThat(second.recordFailure(attempt).opened).isEmpty()
        val locking = first.recordFailure(attempt)
        val whileLocked = second.recordFailure(attempt)

        val lock = locking.opened.single()
        assertThat(lock.kind).isEqualTo(AttemptKeyKind.IDENTIFIER)
        assertThat(whileLocked.opened).describedAs("a key already locked does not open again").isEmpty()
        val refusal = second.admit(attempt)
        assertThat(refusal).isInstanceOf(Admission.Refused::class.java)
        assertThat((refusal as Admission.Refused).retryAfter).isPositive().isLessThanOrEqualTo(policy.lockFor)
        assertThat(redis.getExpire(first.lockKey(lock), TimeUnit.MILLISECONDS)).isBetween(1L, policy.lockFor.toMillis())
        assertThat(redis.getExpire(first.counterKey(lock), TimeUnit.MILLISECONDS)).isBetween(1L, policy.window.toMillis())
    }

    @Test
    fun `a success resets the identifier's counter and leaves the address's, whose lock refuses every identifier from it`() {
        val limiter = replica()
        repeat(2) { limiter.recordFailure(attempt) }

        limiter.recordSuccess(attempt)
        repeat(2) { assertThat(limiter.recordFailure(attempt).opened).isEmpty() }
        assertThat(limiter.admit(attempt)).isEqualTo(Admission.Admitted)
        val fromSameAddress = limiter.recordFailure(Attempt(AGENT, "bob@example.test", attempt.address))

        assertThat(fromSameAddress.opened.map { it.kind }).containsExactly(AttemptKeyKind.ADDRESS)
        assertThat(limiter.admit(Attempt(AGENT, "carol@example.test", attempt.address))).isInstanceOf(Admission.Refused::class.java)
    }

    @Test
    fun `no key names the identifier itself`() {
        replica().recordFailure(attempt)

        assertThat(redis.keys("$prefix*")).isNotEmpty().noneMatch { it.contains(attempt.identifier) }
    }

    @Test
    fun `an unreachable store refuses instead of admitting`() {
        val unreachable = RedisFactories.unreachable()
        try {
            val down = RedisAttemptLimiter(StringRedisTemplate(unreachable), prefix, policy)

            assertThatThrownBy { down.admit(attempt) }.isInstanceOf(AttemptStoreUnavailableException::class.java)
            assertThatThrownBy { down.recordFailure(attempt) }.isInstanceOf(AttemptStoreUnavailableException::class.java)
        } finally {
            unreachable.destroy()
        }
    }
}
