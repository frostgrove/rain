package com.gd.rain.access.internal.token

import com.gd.rain.access.internal.web.CanonicalIds
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * The rotating half of the pair: `<generation>.<session id>.<43 characters of base64url>`.
 *
 * The prefix authenticates nothing — the SHA-256 digest of the whole credential does — but it names which session and
 * which generation the credential came from, so a credential several rotations old is recognised as a replay of that
 * session rather than answered as an ordinary unknown credential.
 */
public object RefreshCredential {
    public const val RANDOM_BYTES: Int = 32

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val hex: HexFormat = HexFormat.of()
    private val GENERATION = Regex("^[1-9][0-9]{0,18}$")

    public fun mint(
        generation: Long,
        session: UUID,
    ): String {
        require(generation >= 1) { "a credential generation starts at 1, got $generation" }
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return "$generation.$session.${encoder.encodeToString(bytes)}"
    }

    /** Lower-case hexadecimal SHA-256 of the whole credential, prefix included. */
    public fun digest(credential: String): String =
        hex.formatHex(MessageDigest.getInstance("SHA-256").digest(credential.toByteArray(Charsets.UTF_8)))

    /** The prefix, or `null` when the credential has none this application writes. */
    public fun prefixOf(credential: String): Prefix? {
        val parts = credential.split('.')
        if (parts.size != 3 || parts[2].isEmpty()) return null
        if (!GENERATION.matches(parts[0])) return null
        val generation = parts[0].toLongOrNull() ?: return null
        val session = CanonicalIds.parse(parts[1]) ?: return null
        return Prefix(generation, session)
    }

    public data class Prefix(
        public val generation: Long,
        public val session: UUID,
    )
}

/** What a presented refresh credential is to the session it names. */
public enum class RotationOutcome(
    public val wire: String,
) {
    /** The session's current credential. */
    ROTATE("rotate"),

    /** The previous credential inside the grace window: two tabs refreshing at once. */
    ROTATE_AGAIN("rotate-again"),

    /** A credential this session really issued and somebody kept; the session is closed. */
    REPLAY("replay"),

    /** Revoked, expired, idle, or never this session's. */
    UNUSABLE("unusable"),
}

public data class RefreshWindow(
    public val grace: Duration,
    public val idle: Duration,
) {
    init {
        require(grace.isPositive && idle.isPositive) { "a refresh window has a positive grace and idle bound" }
    }
}

public data class PresentedCredential(
    public val digest: String,
    public val current: String,
    public val previous: String?,
    public val generation: Long,
    public val currentGeneration: Long,
    public val rotatedAt: Instant?,
    public val lastUsedAt: Instant,
    public val revoked: Boolean,
    public val expiresAt: Instant,
)

/**
 * The classification, branch by branch; the first branch that matches is the answer.
 *
 * 1. revoked, or expired → unusable;
 * 2. not rotated for longer than the idle bound → unusable;
 * 3. the current digest → rotate;
 * 4. a generation older than the previous one → replay;
 * 5. not the previous digest → unusable;
 * 6. no rotation recorded → unusable;
 * 7. the previous digest within the grace after the rotation (inclusive) → rotate again;
 * 8. otherwise → replay.
 */
public object RotationClassifier {
    public fun classify(
        presented: PresentedCredential,
        now: Instant,
        window: RefreshWindow,
    ): RotationOutcome {
        val rotatedAt = presented.rotatedAt
        return when {
            presented.revoked || !now.isBefore(presented.expiresAt) -> RotationOutcome.UNUSABLE
            Duration.between(presented.lastUsedAt, now) > window.idle -> RotationOutcome.UNUSABLE
            presented.digest == presented.current -> RotationOutcome.ROTATE
            presented.generation < presented.currentGeneration - 1 -> RotationOutcome.REPLAY
            presented.previous == null || presented.digest != presented.previous -> RotationOutcome.UNUSABLE
            rotatedAt == null -> RotationOutcome.UNUSABLE
            Duration.between(rotatedAt, now) <= window.grace -> RotationOutcome.ROTATE_AGAIN
            else -> RotationOutcome.REPLAY
        }
    }
}
