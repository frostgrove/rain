package com.gd.rain.access.internal.token

/** The resolved signing key. Never printed: a key in a log line is a key in the log store. */
public class SigningKeyMaterial(
    material: ByteArray,
) {
    private val bytes: ByteArray = material.copyOf()

    init {
        require(bytes.size >= SigningKey.MIN_BYTES) { "a signing key holds at least ${SigningKey.MIN_BYTES} bytes" }
    }

    /** A copy of the key bytes, for a MAC to be keyed with. */
    public fun bytes(): ByteArray = bytes.copyOf()

    override fun toString(): String = "SigningKeyMaterial(<redacted>)"
}
