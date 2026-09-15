package com.gd.rain.data.jdbc

import com.gd.rain.persistence.json.JsonbPayload
import com.gd.rain.persistence.json.WireEnum
import org.postgresql.util.PGobject
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.converter.ConverterFactory
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.OffsetDateTime

/** Converters an application adds to Spring Data JDBC's custom conversions, alongside rain's. */
public fun interface JdbcConversionContribution {
    public fun converters(): List<Any>
}

public object RainConverters {
    /**
     * The mapper stored documents are written with — not the application's HTTP mapper, whose inclusion
     * rules are about responses and would drop keys from a stored document.
     */
    internal val storage: ObjectMapper = jacksonObjectMapper()

    public val ALL: List<Any> =
        listOf(
            JsonbWritingConverter,
            JsonbReadingConverterFactory,
            WireEnumWritingConverter,
            WireEnumReadingConverterFactory,
            OffsetDateTimeToInstantConverter,
        )
}

/** A `jsonb` parameter is a typed `PGobject`; PostgreSQL refuses to cast an untyped string to `jsonb`. */
@WritingConverter
public object JsonbWritingConverter : Converter<JsonbPayload, PGobject> {
    override fun convert(source: JsonbPayload): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = RainConverters.storage.writeValueAsString(source)
        }
}

@ReadingConverter
public object JsonbReadingConverterFactory : ConverterFactory<PGobject, JsonbPayload> {
    override fun <T : JsonbPayload> getConverter(targetType: Class<T>): Converter<PGobject, T> =
        Converter { source ->
            RainConverters.storage.readValue(
                requireNotNull(source.value) {
                    "a jsonb column read as SQL NULL is mapped by the property's nullability"
                },
                targetType,
            )
        }
}

@WritingConverter
public object WireEnumWritingConverter : Converter<WireEnum, String> {
    override fun convert(source: WireEnum): String = source.wire
}

/** Reads by [WireEnum.wire]; a stored string no constant declares is an explicit failure, never a null. */
@ReadingConverter
public object WireEnumReadingConverterFactory : ConverterFactory<String, WireEnum> {
    override fun <T : WireEnum> getConverter(targetType: Class<T>): Converter<String, T> =
        Converter { source ->
            val constants = requireNotNull(targetType.enumConstants) { "${targetType.name} is not an enum class" }
            constants.firstOrNull { it.wire == source }
                ?: throw IllegalArgumentException("'$source' is not one of ${targetType.simpleName}: ${constants.joinToString { it.wire }}")
        }
}

/**
 * `timestamptz` read as [OffsetDateTime] carries the session's offset, not the writer's; normalising to
 * [Instant] keeps one moment one value. Only the reading direction is registered: an [Instant] already
 * writes as an absolute point in time.
 */
@ReadingConverter
public object OffsetDateTimeToInstantConverter : Converter<OffsetDateTime, Instant> {
    override fun convert(source: OffsetDateTime): Instant = source.toInstant()
}
