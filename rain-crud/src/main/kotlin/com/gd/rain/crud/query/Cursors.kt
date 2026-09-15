package com.gd.rain.crud.query

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

public enum class CursorDirection(
    public val wire: String,
) {
    /** The page after the row the cursor names. */
    NEXT("next"),

    /** The page before the row the cursor names. */
    PREV("prev"),
}

/** Where a cursor page starts: a direction and the effective-order values of the boundary row. */
public class CursorPosition(
    public val direction: CursorDirection,
    keys: List<Any>,
) {
    public val keys: List<Any> = keys.toList()
}

public sealed interface CursorDecoding {
    public class Decoded(
        public val position: CursorPosition,
    ) : CursorDecoding

    public class Refused(
        public val reason: String,
    ) : CursorDecoding
}

/**
 * Cursor format v1: base64url (no padding) of the JSON object `{"v":1,"sig":…,"dir":"next"|"prev","k":[…]}`.
 *
 * `sig` is the base64url SHA-256 of the effective order (`field:asc|desc` terms joined by `,`), so a
 * cursor presented with a different order is refused rather than compared against the wrong columns.
 * `k` holds the boundary row's values in that order, each written as its kind's canonical string
 * (an `Instant` or `LocalDate` in ISO form, a decimal in plain notation). Decoding is strict: exactly those
 * four members, no duplicates, and every value must re-encode to the text it was read from.
 */
public object CursorCodec {
    public const val VERSION: Int = 1

    private val MEMBERS = setOf("v", "sig", "dir", "k")
    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    private val JSON: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    public fun signature(order: List<Order>): String {
        val text = order.joinToString(",") { "${it.field.name}:${it.direction.name.lowercase()}" }
        return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }

    public fun encode(
        order: List<Order>,
        position: CursorPosition,
    ): String {
        require(position.keys.size == order.size) { "a cursor carries one key per order term" }
        val payload =
            linkedMapOf(
                "v" to VERSION,
                "sig" to signature(order),
                "dir" to position.direction.wire,
                "k" to order.indices.map { canonical(position.keys[it], order[it].field.kind) },
            )
        return ENCODER.encodeToString(JSON.writeValueAsBytes(payload))
    }

    public fun decode(
        token: String,
        order: List<Order>,
    ): CursorDecoding {
        val bytes =
            try {
                DECODER.decode(token)
            } catch (_: IllegalArgumentException) {
                return CursorDecoding.Refused("is not base64url")
            }
        if (ENCODER.encodeToString(bytes) != token) return CursorDecoding.Refused("is not unpadded base64url")
        val root =
            try {
                JSON.readTree(bytes)
            } catch (_: JacksonException) {
                return CursorDecoding.Refused("does not hold a JSON object")
            }
        if (!root.isObject || root.propertyNames().toSet() != MEMBERS) return CursorDecoding.Refused("is not a version 1 cursor")
        val version = root.required("v")
        if (!version.isInt || version.intValue() != VERSION) return CursorDecoding.Refused("is not a version 1 cursor")
        val signature = root.required("sig")
        if (!signature.isString || signature.stringValue() != signature(order)) {
            return CursorDecoding.Refused("was made for a different sort order")
        }
        val direction = root.required("dir").takeIf(JsonNode::isString)?.stringValue()
        val parsedDirection =
            CursorDirection.entries.firstOrNull { it.wire == direction } ?: return CursorDecoding.Refused("names no direction")
        val keys = root.required("k")
        if (!keys.isArray || keys.size() != order.size) return CursorDecoding.Refused("does not hold one key per sort term")
        val values =
            order.indices.map { index ->
                val node = keys.get(index)
                val text = if (node.isString) node.stringValue() else return CursorDecoding.Refused("holds a key that is not a string")
                parse(text, order[index].field.kind)
                    ?: return CursorDecoding.Refused("holds a key that does not fit ${order[index].field.name}")
            }
        return CursorDecoding.Decoded(CursorPosition(parsedDirection, values))
    }

    private fun canonical(
        value: Any,
        kind: FieldKind,
    ): String {
        require(kind.accepts(value)) { "a cursor key of kind $kind is a ${kind.valueType.simpleName}" }
        return when (value) {
            is BigDecimal -> value.toPlainString()
            else -> value.toString()
        }
    }

    /** The value [text] canonically spells, or `null` when it spells none or not canonically. */
    private fun parse(
        text: String,
        kind: FieldKind,
    ): Any? {
        val value: Any? =
            try {
                when (kind) {
                    FieldKind.TEXT -> text
                    FieldKind.BOOLEAN -> text.toBooleanStrictOrNull()
                    FieldKind.INT -> text.toIntOrNull()
                    FieldKind.LONG -> text.toLongOrNull()
                    FieldKind.DECIMAL -> BigDecimal(text)
                    FieldKind.UUID -> UUID.fromString(text)
                    FieldKind.TIMESTAMP -> Instant.parse(text)
                    FieldKind.DATE -> LocalDate.parse(text)
                }
            } catch (_: NumberFormatException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: DateTimeParseException) {
                null
            }
        return value?.takeIf { canonical(it, kind) == text }
    }
}
