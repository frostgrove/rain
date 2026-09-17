package com.gd.rain.i18n

import java.security.MessageDigest

/** A lower-case SHA-256 digest, represented exactly as 64 hexadecimal characters. */
@JvmInline
public value class Digest private constructor(
    public val hex: String,
) {
    public companion object {
        private val HEX = Regex("[0-9a-f]{64}")

        /** Hashes the exact supplied bytes. Canonicalization belongs to the caller's codec. */
        public fun sha256(bytes: ByteArray): Digest {
            val hashed = MessageDigest.getInstance("SHA-256").digest(bytes)
            val encoded = CharArray(hashed.size * 2)
            hashed.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xFF
                encoded[index * 2] = HEX_DIGITS[unsigned ushr 4]
                encoded[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0F]
            }
            return Digest(encoded.concatToString())
        }

        /** Reads a canonical digest without changing its spelling. */
        public fun parse(hex: String): Digest {
            require(HEX.matches(hex)) { "a digest is exactly 64 lower-case hexadecimal characters" }
            return Digest(hex)
        }

        private const val HEX_DIGITS: String = "0123456789abcdef"
    }
}

/** The digest of a message's stable binding contract. */
@JvmInline
public value class ContractDigest(
    public val value: Digest,
)

/** The digest of source wording, source locale and translator description. */
@JvmInline
public value class SourceDigest(
    public val value: Digest,
)

/** The digest of one reviewed translation under one source digest. */
@JvmInline
public value class ReviewDigest(
    public val value: Digest,
)
