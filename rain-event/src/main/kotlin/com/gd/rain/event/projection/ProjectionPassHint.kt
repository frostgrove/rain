package com.gd.rain.event.projection

import com.gd.rain.realtime.Channel
import com.gd.rain.realtime.RealtimePublisher

/** One parsed realtime wake-up hint for a durable projection lane. It is never a delivery or cursor acknowledgement. */
public data class ProjectionPassHint(
    public val lane: ProjectionLane,
)

/**
 * Stable, payload-free codec for the optional PostgreSQL `NOTIFY` accelerator.
 *
 * The message carries only a durable lane identity. A receiving worker re-reads generation/checkpoint state through
 * [ProjectionPassWorker], so duplicate, delayed, malformed, or lost hints cannot change correctness.
 */
public object ProjectionPassHintCodec {
    private const val VERSION: String = "rain.event.projection.pass.v1"

    public fun encode(lane: ProjectionLane): String =
        listOf(
            VERSION,
            lane.projection.text(),
            lane.generation.value.toString(),
            lane.partition.depth.toString(),
            lane.partition.prefix.toString(),
        ).joinToString(SEPARATOR)

    /** Malformed or foreign hint payloads are ignored rather than becoming a worker retry. */
    public fun decode(payload: String): ProjectionPassHint? {
        if (payload.length > MAX_HINT_CHARACTERS) return null
        val fields = payload.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields.first() != VERSION) return null
        return runCatching {
            ProjectionPassHint(
                ProjectionLane(
                    ProjectionName.of(fields[1]),
                    ProjectionGeneration(fields[2].toLong()),
                    ProjectionPartition(fields[3].toInt(), fields[4].toLong()),
                ),
            )
        }.getOrNull()
    }

    private const val SEPARATOR: String = "|"
    private const val FIELD_COUNT: Int = 5
    private const val MAX_HINT_CHARACTERS: Int = 8_000
}

/** Publishes a hint only in the transaction that made it relevant; PostgreSQL suppresses it on rollback. */
public class ProjectionPassHintPublisher(
    private val publisher: RealtimePublisher,
    public val channel: Channel,
) {
    public fun publish(lane: ProjectionLane): Unit = publisher.publish(channel, ProjectionPassHintCodec.encode(lane))
}

/** Turns one listener payload into a normal durable jobs kick. A lost payload is recovered by [ProjectionPassSweep]. */
public class ProjectionPassHintConsumer(
    private val worker: ProjectionPassWorker,
) {
    public fun consume(payload: String): ProjectionPassHintDelivery =
        ProjectionPassHintCodec.decode(payload)?.let { hint ->
            ProjectionPassHintDelivery.Kicked(hint.lane, worker.kick(hint.lane))
        } ?: ProjectionPassHintDelivery.Ignored
}

/** A listener can count malformed payloads separately from regular no-op generation outcomes. */
public sealed interface ProjectionPassHintDelivery {
    public data class Kicked(
        public val lane: ProjectionLane,
        public val result: ProjectionPassKick,
    ) : ProjectionPassHintDelivery

    public data object Ignored : ProjectionPassHintDelivery
}
