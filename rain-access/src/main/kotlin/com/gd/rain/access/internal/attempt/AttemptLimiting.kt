package com.gd.rain.access.internal.attempt

import com.gd.rain.access.AccessProperties
import com.gd.rain.access.SubjectType
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One try at proving a password: the subject type and identifier it named, and the client address it came from. */
public data class Attempt(
    public val subjectType: SubjectType,
    public val identifier: String,
    public val address: String,
) {
    init {
        require(address.isNotEmpty()) { "an attempt comes from an address" }
    }

    override fun toString(): String =
        "Attempt(subjectType=$subjectType, identifier=${AttemptKeys.fingerprint(identifier)}, address=$address)"
}

public enum class AttemptKeyKind(
    public val wire: String,
) {
    IDENTIFIER("identifier"),
    ADDRESS("address"),
}

/**
 * One counter an attempt is charged to. [id] never contains the identifier itself: an identifier key is the subject
 * type and the SHA-256 of the identifier, an address key is the address.
 */
public data class AttemptKey(
    public val kind: AttemptKeyKind,
    public val id: String,
    public val ceiling: Int,
)

public sealed interface Admission {
    public data object Admitted : Admission

    /** Refused until [retryAfter] has passed. */
    public data class Refused(
        public val retryAfter: Duration,
    ) : Admission
}

/** What recording a failure did: the keys it locked, and the keys a full memory table could not count. */
public data class FailureRecorded(
    public val opened: List<AttemptKey>,
    public val uncounted: List<AttemptKey>,
)

public data class AttemptPolicy(
    public val perIdentifier: Int,
    public val perAddress: Int,
    public val window: Duration,
    public val lockFor: Duration,
) {
    init {
        require(perIdentifier >= 1 && perAddress >= 1) { "an attempt ceiling is at least 1" }
        require(window.isPositive && lockFor.isPositive) { "an attempt window and lock are positive" }
    }

    public companion object {
        public fun of(properties: AccessProperties.Attempts): AttemptPolicy =
            AttemptPolicy(properties.perIdentifier, properties.perAddress, properties.window, properties.lockFor)
    }
}

/**
 * The ceiling on failed password attempts, per identifier and per address.
 *
 * Failures are counted in a window that opens with the first failure; the failure that reaches a key's ceiling locks
 * the key for `lock-for`. A locked key refuses admission; a refused attempt is never counted. A success clears the
 * identifier's counter and leaves the address's.
 */
public interface AttemptLimiter {
    public fun admit(attempt: Attempt): Admission

    public fun recordFailure(attempt: Attempt): FailureRecorded

    public fun recordSuccess(attempt: Attempt)
}

public object AttemptKeys {
    private val HEX = HexFormat.of()

    public fun of(
        attempt: Attempt,
        policy: AttemptPolicy,
    ): List<AttemptKey> =
        listOf(
            AttemptKey(AttemptKeyKind.IDENTIFIER, "i:${attempt.subjectType.name}:${sha256(attempt.identifier)}", policy.perIdentifier),
            AttemptKey(AttemptKeyKind.ADDRESS, "a:${attempt.address}", policy.perAddress),
        )

    /** The first six bytes of SHA-256 of an identifier, in hex: enough to group attempts on one account, not to read it back. */
    public fun fingerprint(identifier: String): String = sha256(identifier).substring(0, FINGERPRINT_HEX)

    private fun sha256(text: String): String = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private const val FINGERPRINT_HEX = 12
}

/**
 * Attempt counters in this process, bounded by [maximumKeys].
 *
 * The bound is the security property and so is the rule for making room: only a counter that has expired — its window
 * over and its lock run out — may be forgotten, because forgetting a live counter would hand its caller a free reset.
 * Counters sit in a heap ordered by expiry, so making room costs a look at the root and O(log n) per counter removed.
 * A full table of live counters refuses admission to any attempt it would have to seat a new counter for, until the
 * earliest counter expires; the counters it holds keep working.
 */
public class MemoryAttemptLimiter(
    private val policy: AttemptPolicy,
    private val maximumKeys: Int,
    private val clock: Clock,
) : AttemptLimiter {
    init {
        require(maximumKeys >= 1) { "a memory attempt table holds at least one key, got $maximumKeys" }
    }

    private class Counter(
        val key: String,
        var failures: Int,
        var windowEndsAt: Instant,
        var lockedUntil: Instant,
    ) {
        var position: Int = -1
        val expiresAt: Instant get() = maxOf(windowEndsAt, lockedUntil)
    }

    private val lock = ReentrantLock()
    private val counters = HashMap<String, Counter>()
    private val heap = ArrayList<Counter>()

    /** Heap roots examined to make room; one per removal attempt, whatever the table's size. */
    public var examinedForRoom: Long = 0
        private set

    public val tracked: Int get() = lock.withLock { counters.size }

    override fun admit(attempt: Attempt): Admission {
        val now = clock.instant()
        return lock.withLock {
            var unseated = 0
            var longestLock = Duration.ZERO
            AttemptKeys.of(attempt, policy).forEach { key ->
                val counter = live(key.id, now)
                when {
                    counter == null -> unseated++
                    counter.lockedUntil.isAfter(now) -> longestLock = maxOf(longestLock, Duration.between(now, counter.lockedUntil))
                }
            }
            when {
                longestLock.isPositive -> Admission.Refused(longestLock)
                !makeRoom(unseated, now) -> Admission.Refused(Duration.between(now, heap.first().expiresAt))
                else -> Admission.Admitted
            }
        }
    }

    override fun recordFailure(attempt: Attempt): FailureRecorded {
        val now = clock.instant()
        return lock.withLock {
            val opened = mutableListOf<AttemptKey>()
            val uncounted = mutableListOf<AttemptKey>()
            AttemptKeys.of(attempt, policy).forEach { key ->
                val counter =
                    live(key.id, now) ?: if (makeRoom(1, now)) {
                        Counter(key.id, 0, now.plus(policy.window), Instant.EPOCH).also { seat(it) }
                    } else {
                        null
                    }
                if (counter == null) {
                    uncounted += key
                    return@forEach
                }
                counter.failures++
                if (counter.failures >= key.ceiling && !counter.lockedUntil.isAfter(now)) {
                    counter.lockedUntil = now.plus(policy.lockFor)
                    opened += key
                }
                sift(counter)
            }
            FailureRecorded(opened, uncounted)
        }
    }

    override fun recordSuccess(attempt: Attempt) {
        lock.withLock {
            val identifier = AttemptKeys.of(attempt, policy).single { it.kind == AttemptKeyKind.IDENTIFIER }
            counters[identifier.id]?.let(::forget)
        }
    }

    /** The counter for [key] unless it has expired, in which case it is forgotten. */
    private fun live(
        key: String,
        now: Instant,
    ): Counter? {
        val counter = counters[key] ?: return null
        if (counter.expiresAt.isAfter(now)) return counter
        forget(counter)
        return null
    }

    /** Frees expired counters from the root until [seats] more fit; false when the table is full of live counters. */
    private fun makeRoom(
        seats: Int,
        now: Instant,
    ): Boolean {
        while (counters.size + seats > maximumKeys) {
            examinedForRoom++
            val root = heap.firstOrNull() ?: return false
            if (root.expiresAt.isAfter(now)) return false
            forget(root)
        }
        return true
    }

    private fun seat(counter: Counter) {
        counters[counter.key] = counter
        heap += counter
        counter.position = heap.size - 1
        siftUp(counter.position)
    }

    private fun forget(counter: Counter) {
        counters.remove(counter.key)
        val position = counter.position
        val last = heap.size - 1
        swap(position, last)
        heap.removeAt(last).position = -1
        if (position < heap.size && !siftUp(position)) siftDown(position)
    }

    private fun sift(counter: Counter) {
        if (!siftUp(counter.position)) siftDown(counter.position)
    }

    private fun siftUp(from: Int): Boolean {
        var index = from
        while (index > 0) {
            val parent = (index - 1) / 2
            if (!heap[index].expiresAt.isBefore(heap[parent].expiresAt)) break
            swap(parent, index)
            index = parent
        }
        return index != from
    }

    private fun siftDown(from: Int) {
        var index = from
        while (true) {
            val left = index * 2 + 1
            if (left >= heap.size) return
            val right = left + 1
            val earlier = if (right < heap.size && heap[right].expiresAt.isBefore(heap[left].expiresAt)) right else left
            if (!heap[earlier].expiresAt.isBefore(heap[index].expiresAt)) return
            swap(index, earlier)
            index = earlier
        }
    }

    private fun swap(
        first: Int,
        second: Int,
    ) {
        val held = heap[first]
        heap[first] = heap[second]
        heap[second] = held
        heap[first].position = first
        heap[second].position = second
    }
}
