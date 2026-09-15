package com.gd.rain.access.internal.password

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.web.filter.RequestDeadline
import com.gd.rain.web.filter.TransportRefusal
import io.github.resilience4j.bulkhead.Bulkhead
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.concurrent.atomic.AtomicInteger

/** Derives and checks password hashes. Every call is CPU and memory heavy and runs outside any transaction. */
public interface PasswordHasher {
    public fun hash(password: String): String

    public fun verify(
        password: String,
        hash: String,
    ): Boolean

    /** Whether [hash] was derived with weaker parameters than this hasher's and should be derived again. */
    public fun needsUpgrade(hash: String): Boolean

    /** A real hash of a constant, verified against when no credential was found, so a miss costs what a hit costs. */
    public val dummyHash: String
}

/**
 * Argon2id through Spring Security's PHC encoder at the declared parameters. Construction derives and verifies the dummy
 * hash, which also proves at start-up that Bouncy Castle is on the classpath.
 */
public class Argon2PasswordHasher(
    parameters: AccessProperties.Argon2,
) : PasswordHasher {
    private val encoder =
        Argon2PasswordEncoder(
            parameters.saltBytes,
            parameters.hashBytes,
            parameters.parallelism,
            parameters.memoryKib,
            parameters.iterations,
        )

    override val dummyHash: String = requireNotNull(encoder.encode(DUMMY_PASSWORD)) { "the Argon2 encoder produced no hash" }

    init {
        check(encoder.matches(DUMMY_PASSWORD, dummyHash)) { "the Argon2 encoder does not verify the hash it just derived" }
    }

    override fun hash(password: String): String = requireNotNull(encoder.encode(password)) { "the Argon2 encoder produced no hash" }

    override fun verify(
        password: String,
        hash: String,
    ): Boolean = encoder.matches(password, hash)

    override fun needsUpgrade(hash: String): Boolean = encoder.upgradeEncoding(hash)

    private companion object {
        const val DUMMY_PASSWORD = "rain-access: no credential"
    }
}

/**
 * The hashing bulkhead: at most the instance's `max-concurrent-calls` derivations at once, each caller waiting at most
 * its `max-wait-duration` — and never past the request's deadline, whose expiry interrupts the wait — and at most
 * `rain.access.hashing.queue` callers waiting at once; one more is refused at once rather than queued for a permit it
 * would only reach after its request had ended.
 */
public class HashingBulkhead(
    private val bulkhead: () -> Bulkhead,
    private val queue: Int,
) {
    init {
        require(queue >= 1) { "a hashing queue holds at least one caller, got $queue" }
    }

    private val waiting = AtomicInteger()

    /** Callers currently waiting for a permit. */
    public val waitingCallers: Int get() = waiting.get()

    public fun <T> run(work: () -> T): T {
        RequestDeadline.current()?.let { deadline -> if (deadline.remaining().isZero) throw TransportRefusal.deadlineExceeded() }
        val instance = bulkhead()
        if (waiting.incrementAndGet() > queue) {
            waiting.decrementAndGet()
            throw overloaded(instance)
        }
        val admitted =
            try {
                instance.tryAcquirePermission()
            } finally {
                waiting.decrementAndGet()
            }
        if (!admitted) throw overloaded(instance)
        try {
            return work()
        } finally {
            instance.onComplete()
        }
    }

    private fun overloaded(instance: Bulkhead): Fault {
        val wait = instance.bulkheadConfig.maxWaitDuration
        return Fault(FaultKind.RETRYABLE, AccessErrorCodes.OVERLOADED, retryAfter = wait.takeIf { it.isPositive })
    }
}
