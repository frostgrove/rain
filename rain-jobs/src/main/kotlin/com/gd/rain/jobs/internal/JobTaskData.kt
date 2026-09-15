package com.gd.rain.jobs.internal

import com.github.kagkarlsson.scheduler.serializer.Serializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.util.UUID

/**
 * What travels in `scheduled_tasks.task_data`: which invocation, and which generation of it this execution may
 * deliver. The payload stays in `job_invocation` and is read by the claim.
 */
internal data class JobTaskData(
    val invocation: UUID,
    val generation: Int,
) {
    /** The db-scheduler instance id: one per (invocation, generation), so a redrive never collides with a stale execution. */
    val instanceId: String get() = "$invocation/$generation"
}

/** db-scheduler's serializer for [JobTaskData], on Jackson 3 (db-scheduler's own `JacksonSerializer` is Jackson 2). */
internal class Jackson3TaskSerializer(
    private val mapper: JsonMapper = jacksonMapperBuilder().build(),
) : Serializer {
    override fun serialize(data: Any?): ByteArray? = data?.let(mapper::writeValueAsBytes)

    override fun <T : Any?> deserialize(
        clazz: Class<T>,
        serializedData: ByteArray?,
    ): T? = serializedData?.let { mapper.readValue(it, clazz) }
}
