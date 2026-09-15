package com.gd.rain.llm

import com.gd.rain.core.id.IdGenerator
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What one attempt to take a slot found. */
public sealed interface SlotGrant {
    public data object Granted : SlotGrant

    /** Every slot the ceiling allows is held. */
    public data object Full : SlotGrant

    /** The pool has no budget row. */
    public data object PoolMissing : SlotGrant
}

/** The statements a cluster-wide slot is made of. */
public interface LlmSlotStore {
    /** Takes one slot of [pool] if fewer than [ceiling] are held, with a lease of [lease] on the store's clock. */
    public fun tryAcquire(
        pool: String,
        ceiling: Int,
        id: UUID,
        holder: String,
        lease: Duration,
    ): SlotGrant

    /** Gives a slot back; a slot that is not held changes nothing. */
    public fun release(id: UUID)

    /** Creates a budget row for every pool that has none. */
    public fun ensurePools(pools: Collection<String>)
}

/** Waits between two attempts to take a slot. Production sleeps; a test decides. */
public fun interface SlotPause {
    public fun pause(duration: Duration)

    public companion object {
        public val SLEEP: SlotPause = SlotPause { Thread.sleep(it) }
    }
}

/** A held slot. Closing gives it back once; a slot nobody gives back expires with its lease. */
public class LlmSlot internal constructor(
    public val id: UUID,
    private val slots: LlmSlots,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) slots.release(id)
    }
}

/**
 * Cluster-wide admission to the model server: at most a pool's ceiling for the request's class of slots
 * are held at once, across every process sharing the database.
 *
 * A waiting ask tries again every `slot-poll-interval`, and never waits past its deadline: the wait is
 * always bounded by the call budget the gateway derived the deadline from.
 */
public class LlmSlots(
    private val store: LlmSlotStore,
    private val lease: Duration,
    private val pollInterval: Duration,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val holder: String,
    private val pause: SlotPause,
) {
    public fun acquire(
        pool: LlmPool,
        klass: LlmClass,
        deadline: Instant,
    ): LlmSlot {
        val ceiling = pool.ceiling(klass)
        while (true) {
            val id = ids.next()
            when (store.tryAcquire(pool.name, ceiling, id, holder, lease)) {
                SlotGrant.Granted -> return LlmSlot(id, this)
                SlotGrant.PoolMissing -> throw LlmPoolMissing(pool.name)
                SlotGrant.Full -> Unit
            }
            val now = clock.instant()
            if (!now.isBefore(deadline)) throw LlmBudgetExhausted(pool.name, klass)
            try {
                pause.pause(minOf(pollInterval, Duration.between(now, deadline)))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LlmInterrupted(interrupted)
            }
        }
    }

    /**
     * Gives a slot back. A failure is logged and not thrown: the call it guarded has already produced its
     * answer, and the lease returns the slot when it expires.
     */
    internal fun release(id: UUID) {
        try {
            store.release(id)
        } catch (failure: RuntimeException) {
            log.error("giving slot {} back failed; its lease returns it when it expires", id, failure)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LlmSlots::class.java)
    }
}
