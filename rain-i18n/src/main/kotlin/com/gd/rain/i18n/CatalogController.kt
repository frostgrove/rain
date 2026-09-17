package com.gd.rain.i18n

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** An opaque compare-and-set token for the catalog head. It cannot be constructed by consumers. */
public data class CatalogHead internal constructor(
    public val reference: CatalogRef,
    internal val owner: UUID,
    internal val version: Long,
)

/** A finite lease that keeps one exact release available for a durable delivery. */
public data class CatalogPin internal constructor(
    public val id: Long,
    public val reference: CatalogRef,
    public val expiresAt: Instant,
    internal val owner: UUID,
)

/** Explicit finite bounds for the in-process release lifecycle. */
public data class CatalogControllerSpec(
    public val initial: CatalogSnapshot,
    public val maxRetained: Int = 8,
    public val maxPins: Int = 1_024,
    public val maxPinLifetime: Duration = Duration.ofDays(30),
    public val maxSnapshotBytes: Long = 576L * 1024 * 1024,
    public val runtimeIdentity: CatalogRuntimeIdentity? = null,
    public val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(maxRetained in 1..4_096) { "maxRetained is 1..4096" }
        require(maxPins in 1..1_048_576) { "maxPins is 1..1048576" }
        require(maxPinLifetime > Duration.ZERO && maxPinLifetime <= Duration.ofDays(3650)) {
            "maxPinLifetime is positive and at most 3650 days"
        }
        require(maxSnapshotBytes > 0 && maxSnapshotBytes <= 1L shl 40) {
            "maxSnapshotBytes is 1..${1L shl 40}"
        }
        require(initial.estimatedBytes <= maxSnapshotBytes) { "initial snapshot exceeds maxSnapshotBytes" }
        require(runtimeIdentity?.accepts(initial.identity) != false) {
            "initial snapshot identity differs from configured runtime"
        }
    }
}

/** Every mutation answers one closed outcome instead of relying on an incidental lock exception. */
public sealed interface CatalogTransition {
    public data class Updated(
        public val head: CatalogHead,
    ) : CatalogTransition

    public data class Conflict(
        public val current: CatalogHead,
    ) : CatalogTransition

    public data class Missing(
        public val reference: CatalogRef,
    ) : CatalogTransition

    public data class Limit(
        public val reason: CatalogLimitReason,
    ) : CatalogTransition
}

/** Why a finite lifecycle operation could not proceed. */
public enum class CatalogLimitReason {
    SNAPSHOT_BYTES,
    INCOMPATIBLE_RUNTIME,
    RETENTION_HELD_BY_PINS,
    PIN_COUNT,
    PIN_LIFETIME,
}

/**
 * Process-local catalog release controller.
 *
 * The database-backed controller mirrors these transitions with an optimistic `head_version` update;
 * this class has no background worker, mutable global head, or pointer-identity API.
 */
public class CatalogController(
    spec: CatalogControllerSpec,
) {
    private val owner: UUID = UUID.randomUUID()
    private val lock = ReentrantLock()
    private val clock = spec.clock
    private val maxRetained = spec.maxRetained
    private val maxPins = spec.maxPins
    private val maxPinLifetime = spec.maxPinLifetime
    private val maxSnapshotBytes = spec.maxSnapshotBytes
    private val runtimeIdentity = spec.runtimeIdentity
    private var current: State = State(spec.initial, 1)
    private var nextVersion: Long = 2
    private var nextPinId: Long = 1
    private var lastNow: Instant = Instant.MIN
    private val retained = linkedMapOf<CatalogRef, Retained>()
    private val pins = linkedMapOf<Long, PinRecord>()
    private val pinCounts = mutableMapOf<CatalogRef, Int>()

    /** Reads the current immutable snapshot and its CAS token together. */
    public fun current(): CatalogCurrent =
        lock.withLock {
            CatalogCurrent(head(current), current.snapshot)
        }

    /** Activates a fully compiled candidate only when [expected] still denotes this exact current head. */
    public fun activate(
        expected: CatalogHead,
        candidate: CatalogSnapshot,
    ): CatalogTransition =
        lock.withLock {
            expirePins(now())
            if (!isCurrent(expected)) return CatalogTransition.Conflict(head(current))
            if (candidate.reference == current.snapshot.reference) return CatalogTransition.Updated(head(current))
            if (revisionConflicts(candidate.reference)) return CatalogTransition.Conflict(head(current))
            if (runtimeIdentity?.accepts(candidate.identity) == false) {
                return CatalogTransition.Limit(CatalogLimitReason.INCOMPATIBLE_RUNTIME)
            }
            if (candidate.estimatedBytes > maxSnapshotBytes) return CatalogTransition.Limit(CatalogLimitReason.SNAPSHOT_BYTES)

            val nextRetained = LinkedHashMap(retained).also { it.remove(candidate.reference) }
            val activeBytes = Math.addExact(candidate.estimatedBytes, current.snapshot.estimatedBytes)
            while (nextRetained.size >= maxRetained || activeBytes + retainedBytes(nextRetained) > maxSnapshotBytes) {
                val evict =
                    nextRetained.entries.firstOrNull { (reference, _) -> (pinCounts[reference] ?: 0) == 0 }
                        ?: return CatalogTransition.Limit(CatalogLimitReason.RETENTION_HELD_BY_PINS)
                nextRetained.remove(evict.key)
            }
            nextRetained[current.snapshot.reference] = Retained(current.snapshot, nextVersion)
            retained.clear()
            retained.putAll(nextRetained)
            current = State(candidate, nextVersion++)
            CatalogTransition.Updated(head(current))
        }

    /** Rolls back to a retained exact reference under the same opaque CAS rule as activation. */
    public fun rollback(
        expected: CatalogHead,
        target: CatalogRef,
    ): CatalogTransition =
        lock.withLock {
            expirePins(now())
            if (!isCurrent(expected)) return CatalogTransition.Conflict(head(current))
            if (target == current.snapshot.reference) return CatalogTransition.Updated(head(current))
            val candidate = retained[target]?.snapshot ?: return CatalogTransition.Missing(target)
            activateLocked(candidate)
        }

    /** Pins the current or retained release for at most the declared finite lifetime. */
    public fun pin(
        reference: CatalogRef,
        lifetime: Duration,
    ): CatalogPinResult =
        lock.withLock {
            val now = now()
            expirePins(now)
            if (lifetime <= Duration.ZERO || lifetime > maxPinLifetime) {
                return CatalogPinResult.Limit(CatalogLimitReason.PIN_LIFETIME)
            }
            if (pins.size >= maxPins) return CatalogPinResult.Limit(CatalogLimitReason.PIN_COUNT)
            if (snapshotOf(reference) == null) return CatalogPinResult.Missing(reference)
            val pin = CatalogPin(nextPinId++, reference, now.plus(lifetime), owner)
            pins[pin.id] = PinRecord(pin.reference, pin.expiresAt)
            pinCounts.merge(reference, 1, Int::plus)
            CatalogPinResult.Pinned(pin)
        }

    /** Releases one pin; a foreign or already expired pin has no side effect and answers false. */
    public fun release(pin: CatalogPin): Boolean =
        lock.withLock {
            expirePins(now())
            if (pin.owner != owner) return false
            val record = pins.remove(pin.id) ?: return false
            decrementPin(record.reference)
            true
        }

    /** Finds an exact current or retained snapshot after expiring any elapsed pins. */
    public fun snapshot(reference: CatalogRef): CatalogSnapshot? =
        lock.withLock {
            expirePins(now())
            snapshotOf(reference)
        }

    /** Deletes every unpinned retained release, never the current release or a durable pin's target. */
    public fun prune(): Int =
        lock.withLock {
            expirePins(now())
            val removable = retained.keys.filter { reference -> (pinCounts[reference] ?: 0) == 0 }
            removable.forEach(retained::remove)
            removable.size
        }

    private fun activateLocked(candidate: CatalogSnapshot): CatalogTransition {
        if (candidate.reference == current.snapshot.reference) return CatalogTransition.Updated(head(current))
        if (revisionConflicts(candidate.reference)) return CatalogTransition.Conflict(head(current))
        if (runtimeIdentity?.accepts(candidate.identity) == false) {
            return CatalogTransition.Limit(CatalogLimitReason.INCOMPATIBLE_RUNTIME)
        }
        if (candidate.estimatedBytes > maxSnapshotBytes) return CatalogTransition.Limit(CatalogLimitReason.SNAPSHOT_BYTES)
        val nextRetained = LinkedHashMap(retained).also { it.remove(candidate.reference) }
        val activeBytes = Math.addExact(candidate.estimatedBytes, current.snapshot.estimatedBytes)
        while (nextRetained.size >= maxRetained || activeBytes + retainedBytes(nextRetained) > maxSnapshotBytes) {
            val evict =
                nextRetained.entries.firstOrNull { (reference, _) -> (pinCounts[reference] ?: 0) == 0 }
                    ?: return CatalogTransition.Limit(CatalogLimitReason.RETENTION_HELD_BY_PINS)
            nextRetained.remove(evict.key)
        }
        nextRetained[current.snapshot.reference] = Retained(current.snapshot, nextVersion)
        retained.clear()
        retained.putAll(nextRetained)
        current = State(candidate, nextVersion++)
        return CatalogTransition.Updated(head(current))
    }

    private fun isCurrent(expected: CatalogHead): Boolean =
        expected.owner == owner && expected.version == current.version && expected.reference == current.snapshot.reference

    private fun head(state: State): CatalogHead = CatalogHead(state.snapshot.reference, owner, state.version)

    private fun revisionConflicts(reference: CatalogRef): Boolean {
        if (current.snapshot.reference.revision == reference.revision && current.snapshot.reference.digest != reference.digest) return true
        return retained.keys.any { it.revision == reference.revision && it.digest != reference.digest }
    }

    private fun snapshotOf(reference: CatalogRef): CatalogSnapshot? =
        if (current.snapshot.reference == reference) current.snapshot else retained[reference]?.snapshot

    private fun retainedBytes(entries: Map<CatalogRef, Retained>): Long =
        entries.values.fold(0L) { total, retained -> Math.addExact(total, retained.snapshot.estimatedBytes) }

    private fun now(): Instant {
        val observed = clock.instant()
        if (observed > lastNow) lastNow = observed
        return lastNow
    }

    private fun expirePins(now: Instant) {
        val expired = pins.filterValues { it.expiresAt <= now }.keys
        expired.forEach { id -> decrementPin(requireNotNull(pins.remove(id)).reference) }
    }

    private fun decrementPin(reference: CatalogRef) {
        val count = requireNotNull(pinCounts[reference]) { "a pin count exists for every stored pin" }
        if (count == 1) pinCounts.remove(reference) else pinCounts[reference] = count - 1
    }

    private data class State(
        val snapshot: CatalogSnapshot,
        val version: Long,
    )

    private data class Retained(
        val snapshot: CatalogSnapshot,
        val order: Long,
    )

    private data class PinRecord(
        val reference: CatalogRef,
        val expiresAt: Instant,
    )
}

/** The snapshot and token read atomically from a [CatalogController]. */
public data class CatalogCurrent(
    public val head: CatalogHead,
    public val snapshot: CatalogSnapshot,
)

/** The finite result of a [CatalogController.pin] request. */
public sealed interface CatalogPinResult {
    public data class Pinned(
        public val pin: CatalogPin,
    ) : CatalogPinResult

    public data class Missing(
        public val reference: CatalogRef,
    ) : CatalogPinResult

    public data class Limit(
        public val reason: CatalogLimitReason,
    ) : CatalogPinResult
}
