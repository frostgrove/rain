package com.gd.rain.realtime

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Why a subscription's stream ended. Every end is one of these; none is an empty queue or a null. */
public enum class SubscriptionEnd {
    /** The listening connection dropped, so events may be missing. Re-read the current state and subscribe again. */
    GAP,

    /** The holder stopped reading and `subscriber-buffer` events were waiting; the next one ended the stream. */
    OVERFLOW,

    /** The listener stopped with the process. */
    CLOSED,

    /** Listening on the channel could not be confirmed within the subscribe timeout. */
    UNAVAILABLE,

    /** The holder closed the subscription. */
    UNSUBSCRIBED,
}

/** What one [Subscription.poll] found. */
public sealed interface Next {
    public data class Event(
        public val event: RealtimeEvent,
    ) : Next

    /** The timeout passed with nothing delivered; the stream is still open. */
    public data object Idle : Next

    /** The stream ended. Every later poll answers the same at once. */
    public data class Ended(
        public val end: SubscriptionEnd,
    ) : Next
}

internal enum class Delivery {
    QUEUED,

    /** `subscriber-buffer` events are waiting; the listener ends the subscription with [SubscriptionEnd.OVERFLOW]. */
    FULL,

    ENDED,
}

internal fun interface SubscriptionOwner {
    fun unsubscribe(subscription: Subscription)
}

/**
 * One reader of one channel, behind a bounded buffer.
 *
 * `subscriber-buffer` events is how far a holder may fall behind; the next one ends the stream with
 * [SubscriptionEnd.OVERFLOW] instead of growing a queue or making the listener wait, because the
 * listener is one thread serving every subscriber.
 *
 * The end travels through the same queue as the events, as a sentinel behind them: the queue holds
 * `subscriber-buffer + 1` slots, delivery stops at `subscriber-buffer`, so the sentinel always fits,
 * a poller parked on an empty queue is woken by it, and events delivered before the end are read
 * before it. A subscription has one reader; [poll] and [drain] are not meant to race each other.
 */
public class Subscription internal constructor(
    public val channel: Channel,
    private val buffer: Int,
    private val owner: SubscriptionOwner,
) : AutoCloseable {
    init {
        require(buffer in 1 until Int.MAX_VALUE) { "realtime: a subscription buffer is between 1 and ${Int.MAX_VALUE - 1}, got $buffer" }
    }

    private val slots = ArrayBlockingQueue<Slot>(buffer + 1)
    private val lock = Any()

    @Volatile
    private var ended: SubscriptionEnd? = null

    /** Completed `true` once the listener has confirmed `LISTEN` for the channel, `false` if the subscription ended first. */
    internal val confirmation: CompletableFuture<Boolean> = CompletableFuture()

    /** How the stream ended; `null` while it is open. */
    public val end: SubscriptionEnd? get() = ended

    public val isOpen: Boolean get() = ended == null

    /** The next event, [Next.Idle] when [timeout] passed without one, or [Next.Ended] at once when the stream has ended. */
    public fun poll(timeout: Duration): Next {
        val slot = slots.poll(timeout.toNanos(), TimeUnit.NANOSECONDS) ?: return Next.Idle
        return when (slot) {
            is Slot.Delivered -> {
                Next.Event(slot.event)
            }

            Slot.End -> {
                // Nothing follows the sentinel, so the queue is empty and the put-back always fits.
                slots.offer(Slot.End)
                Next.Ended(checkNotNull(ended) { "realtime: the end sentinel is queued only after the end is recorded" })
            }
        }
    }

    /** What is already buffered, without waiting; at most `subscriber-buffer` events. */
    public fun drain(): List<RealtimeEvent> =
        buildList {
            while (true) {
                when (val slot = slots.poll() ?: break) {
                    is Slot.Delivered -> {
                        add(slot.event)
                    }

                    Slot.End -> {
                        slots.offer(Slot.End)
                        break
                    }
                }
            }
        }

    /** Ends the stream with [SubscriptionEnd.UNSUBSCRIBED] unless it has already ended; idempotent. */
    override fun close() {
        owner.unsubscribe(this)
    }

    internal fun deliver(event: RealtimeEvent): Delivery =
        synchronized(lock) {
            when {
                ended != null -> {
                    Delivery.ENDED
                }

                slots.size >= buffer -> {
                    Delivery.FULL
                }

                else -> {
                    slots.add(Slot.Delivered(event))
                    Delivery.QUEUED
                }
            }
        }

    /** Records [reason] and queues the sentinel; `false` when the stream had already ended. */
    internal fun finish(reason: SubscriptionEnd): Boolean {
        synchronized(lock) {
            if (ended != null) return false
            ended = reason
            slots.add(Slot.End)
        }
        confirmation.complete(false)
        return true
    }

    private sealed interface Slot {
        data class Delivered(
            val event: RealtimeEvent,
        ) : Slot

        data object End : Slot
    }
}
