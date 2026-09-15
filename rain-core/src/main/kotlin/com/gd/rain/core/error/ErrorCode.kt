package com.gd.rain.core.error

/**
 * A code a client branches on.
 *
 * A code is declared once, by the module or application that owns it, inside an [ErrorCodeCatalog];
 * [ErrorCodeRegistry] refuses a code two catalogs declare. Construction refuses a malformed value, so
 * a [Fault] can only ever carry a code somebody declared on purpose — there is no string overload a
 * handler could invent one through.
 *
 * Two codes are equal when their [value]s are: the registry is what decides whether two declarations
 * of one value are a conflict.
 */
public class ErrorCode private constructor(
    public val value: String,
    public val defaultMessage: String,
) {
    override fun equals(other: Any?): Boolean = other is ErrorCode && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    public companion object {
        private val FORMAT = Regex("^[a-z][a-z0-9_]{0,63}$")

        /** The textual rule every code obeys: lower-case snake case, at most 64 characters. */
        public val PATTERN: String = FORMAT.pattern

        public fun of(
            value: String,
            defaultMessage: String,
        ): ErrorCode {
            require(isWellFormed(value)) { "error code \"$value\" does not match $PATTERN" }
            require(defaultMessage.isNotBlank()) { "error code \"$value\" has no default message" }
            return ErrorCode(value, defaultMessage)
        }

        public fun isWellFormed(value: String): Boolean = FORMAT.matches(value)
    }
}
