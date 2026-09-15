package com.gd.rain.access.internal.token

import java.nio.ByteBuffer
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a log line names a session by: `sid_fp`, the first eight bytes of an HMAC-SHA256 of the session id, in hex.
 *
 * A session id in a log store is half of what it takes to close somebody else's session; a keyed fingerprint groups
 * every line about one session and reads back as nothing. The key is derived from the signing key under a fixed label,
 * so replicas agree without a second secret and the signing key itself is never used as an HMAC key for anything else.
 */
public class SessionFingerprints(
    signingKey: ByteArray,
) {
    private val key: SecretKeySpec = SecretKeySpec(hmac(SecretKeySpec(signingKey, MAC), DERIVATION_LABEL), MAC)

    public fun of(session: UUID): String {
        val bytes =
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(session.mostSignificantBits)
                .putLong(session.leastSignificantBits)
                .array()
        return HEX.formatHex(hmac(key, bytes), 0, FINGERPRINT_BYTES)
    }

    public companion object {
        public const val LOG_KEY: String = "sid_fp"
        public const val FINGERPRINT_BYTES: Int = 8

        private const val MAC = "HmacSHA256"
        private const val UUID_BYTES = 16
        private val DERIVATION_LABEL = "rain-access session fingerprint v1".toByteArray(Charsets.US_ASCII)
        private val HEX = HexFormat.of()

        private fun hmac(
            key: SecretKeySpec,
            data: ByteArray,
        ): ByteArray = Mac.getInstance(MAC).apply { init(key) }.doFinal(data)
    }
}
