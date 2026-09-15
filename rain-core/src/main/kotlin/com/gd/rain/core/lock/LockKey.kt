package com.gd.rain.core.lock

/**
 * The 64-bit name of a database advisory lock.
 *
 * Two processes agree on a lock only if they derive the same number from the same name, so the
 * derivation is a fixed, documented algorithm ([keyOf], FNV-1a 64) rather than a platform hash.
 */
@JvmInline
public value class LockKey(
    public val value: Long,
)

/**
 * FNV-1a 64 over the UTF-8 bytes of [parts], separated by a NUL byte.
 *
 * The separator keeps `("a", "bc")` and `("ab", "c")` apart. The derivation is part of rain's
 * contract: changing it would make two builds take different locks for the same name.
 */
public fun keyOf(vararg parts: String): LockKey {
    var hash = OFFSET_BASIS
    parts.forEachIndexed { index, part ->
        if (index > 0) hash = hash.mix(SEPARATOR)
        part.toByteArray(Charsets.UTF_8).forEach { byte -> hash = hash.mix(byte) }
    }
    return LockKey(hash)
}

/** One advisory lock and the mode it is taken in. Shared holders admit each other and wait only for an exclusive one. */
public sealed interface Guard {
    public val key: LockKey
    public val shared: Boolean
}

public data class Exclusively(
    override val key: LockKey,
) : Guard {
    override val shared: Boolean get() = false
}

public data class Sharing(
    override val key: LockKey,
) : Guard {
    override val shared: Boolean get() = true
}

/** `14695981039346656037` as a two's-complement `Long`; multiplication wraps exactly as unsigned 64-bit arithmetic does. */
private const val OFFSET_BASIS: Long = -3750763034362895579L
private const val PRIME: Long = 1099511628211L
private const val SEPARATOR: Byte = 0

private fun Long.mix(byte: Byte): Long = (this xor (byte.toLong() and 0xFF)) * PRIME
