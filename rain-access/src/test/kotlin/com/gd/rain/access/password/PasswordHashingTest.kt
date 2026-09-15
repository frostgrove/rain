package com.gd.rain.access.password

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.internal.password.Argon2PasswordHasher
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val FAST = AccessProperties.Argon2(saltBytes = 16, hashBytes = 32, parallelism = 1, memoryKib = 1024, iterations = 1)
private val PHC = Regex("^\\\$argon2id\\\$v=19\\\$m=\\d+,t=\\d+,p=\\d+\\\$[A-Za-z0-9+/]+\\\$[A-Za-z0-9+/]+$")

class PasswordHashingTest {
    private val hasher = Argon2PasswordHasher(FAST)

    @Test
    fun `a hash is an Argon2id PHC string at the declared parameters`() {
        val hash = hasher.hash("correct horse battery")

        assertThat(hash).matches(PHC.toPattern())
        assertThat(hash.split('$')[3]).isEqualTo("m=1024,t=1,p=1")
        assertThat(hasher.verify("correct horse battery", hash)).isTrue()
        assertThat(hasher.verify("incorrect", hash)).isFalse()
    }

    @Test
    fun `a hash derived with weaker parameters asks to be derived again, and one at the declared parameters does not`() {
        val weaker = requireNotNull(Argon2PasswordEncoder(16, 32, 1, 512, 1).encode("weak"))

        assertThat(hasher.verify("weak", weaker)).isTrue()
        assertThat(hasher.needsUpgrade(weaker)).isTrue()
        assertThat(hasher.needsUpgrade(hasher.hash("current"))).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["", "not-a-hash", "\$argon2id\$v=19\$m=1024,t=1,p=1\$only-four", "\$argon2id\$v=19\$m=1024,t=1,p=1\$!!!\$!!!"])
    fun `an unreadable stored hash is no match, never an exception`(stored: String) {
        assertThat(hasher.verify("anything", stored)).isFalse()
    }

    @Test
    fun `the dummy hash is a real hash no presented password matches, and Bouncy Castle is present`() {
        assertThat(hasher.dummyHash).matches(PHC.toPattern())
        assertThat(hasher.verify("", hasher.dummyHash)).isFalse()
        assertThat(Class.forName("org.bouncycastle.crypto.params.Argon2Parameters\$Builder")).isNotNull()
    }
}

class HashingBulkheadTest {
    private fun instance(
        permits: Int,
        wait: Duration,
    ): Bulkhead =
        Bulkhead.of(
            "rain-access-hashing",
            BulkheadConfig
                .custom()
                .maxConcurrentCalls(permits)
                .maxWaitDuration(wait)
                .build(),
        )

    @Test
    fun `a caller past the queue is refused at once with the wait as Retry-After`() {
        val bulkhead = instance(1, Duration.ofSeconds(30))
        val hashing = HashingBulkhead({ bulkhead }, queue = 1)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            Thread.ofVirtual().start {
                hashing.run {
                    holding.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue()
        val waiter = Thread.ofVirtual().start { runCatching { hashing.run { } } }
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (hashing.waitingCallers < 1) {
                check(System.nanoTime() < deadline) { "the second caller never started waiting" }
                Thread.onSpinWait()
            }

            val refusal = runCatching { hashing.run { error("never runs") } }.exceptionOrNull() as Fault

            assertThat(refusal.kind).isEqualTo(FaultKind.RETRYABLE)
            assertThat(refusal.code).isEqualTo(AccessErrorCodes.OVERLOADED)
            assertThat(refusal.retryAfter).isEqualTo(Duration.ofSeconds(30))
        } finally {
            release.countDown()
            holder.join(10_000)
            waiter.join(10_000)
        }
    }

    @Test
    fun `an interrupted caller stops waiting and is refused`() {
        val bulkhead = instance(1, Duration.ofMinutes(1))
        val hashing = HashingBulkhead({ bulkhead }, queue = 4)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            Thread.ofVirtual().start {
                hashing.run {
                    holding.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue()
        try {
            var refusal: Throwable? = null
            val interrupted =
                Thread.ofVirtual().start {
                    Thread.currentThread().interrupt()
                    refusal = runCatching { hashing.run { } }.exceptionOrNull()
                }
            assertThat(interrupted.join(Duration.ofSeconds(10))).isTrue()

            assertThat((refusal as Fault).code).isEqualTo(AccessErrorCodes.OVERLOADED)
        } finally {
            release.countDown()
            holder.join(10_000)
        }
    }

    @Test
    fun `a permit is given back when the work fails`() {
        val bulkhead = instance(1, Duration.ZERO)
        val hashing = HashingBulkhead({ bulkhead }, queue = 1)

        assertThatThrownBy { hashing.run { error("the hasher failed") } }.hasMessage("the hasher failed")

        assertThat(hashing.run { "admitted" }).isEqualTo("admitted")
        assertThat(bulkhead.metrics.availableConcurrentCalls).isEqualTo(1)
    }
}
