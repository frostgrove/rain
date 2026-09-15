package com.gd.rain.realtime

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * `pg_notify` on the caller's transaction.
 *
 * `NOTIFY` inside a transaction is delivered at commit and discarded on rollback, by the server, so an
 * event about a write that rolled back cannot reach a subscriber. Published outside a transaction it
 * would be delivered at once and unconditionally, and nothing would show that the guarantee was lost,
 * because the event still arrives; so that is refused here.
 *
 * The [DSLContext] has to take its connection from the transaction (Boot's jOOQ auto-configuration
 * does). The channel is a bound value in `pg_notify`, not an identifier.
 */
public class RealtimePublisher(
    private val dsl: DSLContext,
) {
    /**
     * Refusals are checked in this order, and none of them issues a statement: a payload that is not
     * text or is larger than [NotifyRules.MAX_PAYLOAD_BYTES], then the absence of a transaction.
     */
    public fun publish(
        channel: Channel,
        payload: String,
    ) {
        refuseUnsendable(channel, payload)
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw PublishRefusal.NotInTransaction(channel)

        dsl.select(DSL.function("pg_notify", Any::class.java, DSL.value(channel.name), DSL.value(payload))).fetch()
    }

    /**
     * One pass over at most [NotifyRules.MAX_PAYLOAD_BYTES] + 1 bytes' worth of characters: the UTF-8
     * size is counted as it would be encoded, a NUL or an unpaired surrogate is not text, and counting
     * stops as soon as the limit is passed, so the cost does not grow with an oversized payload.
     */
    private fun refuseUnsendable(
        channel: Channel,
        payload: String,
    ) {
        var bytes = 0
        var index = 0
        while (index < payload.length) {
            val character = payload[index]
            when {
                character == NUL -> {
                    throw PublishRefusal.PayloadNotText(channel)
                }

                character.code < ONE_BYTE -> {
                    bytes += 1
                }

                character.code < TWO_BYTES -> {
                    bytes += 2
                }

                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= payload.length || !Character.isLowSurrogate(payload[index + 1])) {
                        throw PublishRefusal.PayloadNotText(channel)
                    }
                    bytes += 4
                    index += 1
                }

                Character.isLowSurrogate(character) -> {
                    throw PublishRefusal.PayloadNotText(channel)
                }

                else -> {
                    bytes += 3
                }
            }
            if (bytes > NotifyRules.MAX_PAYLOAD_BYTES) throw PublishRefusal.PayloadTooLarge(channel, bytes)
            index += 1
        }
    }

    private companion object {
        const val NUL = '\u0000'
        const val ONE_BYTE = 0x80
        const val TWO_BYTES = 0x800
    }
}

/** Why a publish was refused. Every one is a defect in the publishing code, raised before any statement. */
public sealed class PublishRefusal(
    message: String,
) : RuntimeException(message) {
    public abstract val channel: Channel

    public class NotInTransaction(
        override val channel: Channel,
    ) : PublishRefusal(
            "realtime: an event has to be published in the transaction that wrote what it is about; none is active for $channel",
        )

    /** [atLeastBytes] is where counting stopped: the payload is at least that large. */
    public class PayloadTooLarge(
        override val channel: Channel,
        public val atLeastBytes: Int,
    ) : PublishRefusal(
            "realtime: the payload on $channel is at least $atLeastBytes bytes; NOTIFY carries at most ${NotifyRules.MAX_PAYLOAD_BYTES}",
        )

    public class PayloadNotText(
        override val channel: Channel,
    ) : PublishRefusal(
            "realtime: a NOTIFY payload is text with no NUL and no unpaired surrogate, and the one on $channel is not",
        )
}
