package com.gd.rain.jobs

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * How a payload is stored in `job_invocation.payload` (JSON) and read back as its definition's payload type.
 * An application replaces the bean to control the mapping; the default is a Jackson 3 mapper with the Kotlin
 * module and Jackson's own defaults.
 */
public interface JobPayloadCodec {
    public fun encode(payload: Any): String

    public fun <P : Any> decode(
        json: String,
        type: Class<P>,
    ): P
}

public class JacksonJobPayloadCodec(
    private val mapper: JsonMapper = jacksonMapperBuilder().build(),
) : JobPayloadCodec {
    override fun encode(payload: Any): String = mapper.writeValueAsString(payload)

    override fun <P : Any> decode(
        json: String,
        type: Class<P>,
    ): P = mapper.readValue(json, type)
}
