package com.gd.rain.crud.web

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.crud.WriteInput
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.WireValue
import com.gd.rain.crud.query.WireValues
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper

/**
 * The request bodies of rain-crud's HTTP writes, read strictly. A body that is not exactly the documented JSON — not
 * JSON, not one object, a member named twice, anything after the object — is `400 malformed_body`.
 *
 * - A write body ([write]) is one object whose members are field names. A member naming no field is an
 *   `unknown_field` violation. A value is JSON `null`, or the JSON type of its field's kind with the spelling
 *   [WireValues] accepts: `TEXT` a string; `BOOLEAN` `true` or `false`; `INT`, `LONG` and `DECIMAL` a number, whose text
 *   is read as the query dialect reads it (no exponent; `1.0` is no `INT`); `UUID`, `TIMESTAMP` and `DATE` a string in
 *   the dialect's spelling. Anything else is an `invalid_format` violation. The violations travel with the values, so
 *   the resource refuses them together with its own.
 * - A bulk body ([ids]) is exactly `{"ids":[…]}` with string ids.
 */
public object CrudBodies {
    private val JSON: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    public fun write(
        schema: ResourceSchema,
        body: String?,
    ): WriteInput {
        val text = body ?: throw malformed(WRITE_SHAPE)
        try {
            JSON.createParser(text).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) throw malformed(WRITE_SHAPE)
                val values = LinkedHashMap<String, Any?>()
                val violations = mutableListOf<Violation>()
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    val name = checkNotNull(parser.currentName())
                    val token = parser.nextToken()
                    val field = schema.field(name)
                    when {
                        field == null -> {
                            violations += Violation.at(path(name), RainErrorCodes.UNKNOWN_FIELD, "is not a field of this resource")
                        }

                        token == JsonToken.VALUE_NULL -> {
                            values[name] = null
                        }

                        else -> {
                            when (val read = value(parser, token, field.kind)) {
                                is WireValue.Read -> {
                                    values[name] = read.value
                                }

                                is WireValue.Refused -> {
                                    violations +=
                                        Violation.at(path(name), RainErrorCodes.INVALID_FORMAT, "the value ${read.reason}")
                                }
                            }
                        }
                    }
                    // An object or an array stands where a value was expected: step over it to the next member.
                    parser.skipChildren()
                }
                if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) throw malformed(WRITE_SHAPE)
                return WriteInput(values, violations)
            }
        } catch (_: JacksonException) {
            throw malformed(WRITE_SHAPE)
        }
    }

    /** `{"ids":["…",…]}` and nothing else. */
    public fun ids(body: String?): List<String> {
        val root =
            try {
                JSON.readTree(body ?: throw malformed(IDS_SHAPE))
            } catch (_: JacksonException) {
                throw malformed(IDS_SHAPE)
            }
        if (!root.isObject || root.propertyNames().toSet() != setOf(IDS)) throw malformed(IDS_SHAPE)
        val ids = root.required(IDS)
        if (!ids.isArray || !ids.values().all { it.isString }) throw malformed(IDS_SHAPE)
        return ids.values().map { it.stringValue() }
    }

    private fun value(
        parser: JsonParser,
        token: JsonToken?,
        kind: FieldKind,
    ): WireValue =
        when (kind) {
            FieldKind.TEXT -> {
                if (token == JsonToken.VALUE_STRING) WireValue.Read(parser.string) else WireValue.Refused("is not a JSON string")
            }

            FieldKind.BOOLEAN -> {
                when (token) {
                    JsonToken.VALUE_TRUE -> WireValue.Read(true)
                    JsonToken.VALUE_FALSE -> WireValue.Read(false)
                    else -> WireValue.Refused("is not true or false")
                }
            }

            FieldKind.INT, FieldKind.LONG, FieldKind.DECIMAL -> {
                if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    WireValues.read(parser.string, kind)
                } else {
                    WireValue.Refused("is not a JSON number")
                }
            }

            FieldKind.UUID, FieldKind.TIMESTAMP, FieldKind.DATE -> {
                if (token == JsonToken.VALUE_STRING) WireValues.read(parser.string, kind) else WireValue.Refused("is not a JSON string")
            }
        }

    private fun malformed(shape: String): Fault = Fault(FaultKind.BAD_REQUEST, RainErrorCodes.MALFORMED_BODY, "the body is $shape")

    private const val IDS = "ids"
    private const val IDS_SHAPE = "{\"ids\":[…]} with string ids"
    private const val WRITE_SHAPE = "one JSON object of field values"
}
