package com.gd.rain.audit

/**
 * Structured, bounded detail: scalar values under declared keys, serialised to one JSON object with keys
 * in lexical order, so the same detail is always the same bytes.
 */
public class AuditDetail private constructor(
    private val values: Map<String, Any>,
) {
    public val keys: Set<String> get() = values.keys

    public operator fun get(key: String): Any? = values[key]

    public fun json(): String =
        values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            quote(key) + ":" +
                when (value) {
                    is String -> quote(value)
                    is Long, is Int, is Boolean -> value.toString()
                    else -> error("unreachable: values are validated at construction")
                }
        }

    override fun equals(other: Any?): Boolean = other is AuditDetail && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = json()

    public companion object {
        public val KEY: Regex = Regex("^[a-z][a-z0-9_]{0,63}$")
        public const val MAX_ENTRIES: Int = 32
        public const val MAX_STRING_LENGTH: Int = 1024

        public val EMPTY: AuditDetail = AuditDetail(emptyMap())

        /** Values are strings of at most [MAX_STRING_LENGTH] characters, integers or booleans. */
        public fun of(vararg entries: Pair<String, Any>): AuditDetail {
            require(entries.size <= MAX_ENTRIES) { "audit detail holds at most $MAX_ENTRIES entries, got ${entries.size}" }
            val values = linkedMapOf<String, Any>()
            entries.forEach { (key, value) ->
                require(KEY.matches(key)) { "detail key \"$key\" does not match ${KEY.pattern}" }
                require(values.put(key, checked(key, value)) == null) { "detail key \"$key\" is given twice" }
            }
            return AuditDetail(values)
        }

        private fun checked(
            key: String,
            value: Any,
        ): Any =
            when (value) {
                is String -> {
                    value.also {
                        require(
                            it.length <= MAX_STRING_LENGTH,
                        ) { "detail \"$key\" is longer than $MAX_STRING_LENGTH characters" }
                    }
                }

                is Int -> {
                    value.toLong()
                }

                is Long, is Boolean -> {
                    value
                }

                else -> {
                    throw IllegalArgumentException(
                        "detail \"$key\" is a ${value::class.simpleName}; only strings, integers and booleans are recorded",
                    )
                }
            }

        private fun quote(text: String): String =
            buildString(text.length + 2) {
                append('"')
                text.forEach { character ->
                    when {
                        character == '"' -> append("\\\"")
                        character == '\\' -> append("\\\\")
                        character < ' ' -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                        else -> append(character)
                    }
                }
                append('"')
            }
    }
}
