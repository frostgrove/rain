package com.gd.rain.access.internal.revocation

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.web.CanonicalIds
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The revocation list on Redis.
 *
 * - a closed session is a key `<prefix>s:<session id>` living until `revoked_at + access-ttl`, the last instant a token
 *   of that session can verify;
 * - a subject cutoff is one key `<prefix>c:<type>:<id>` holding `<cutoff epoch millis>:<kept session id or empty>`,
 *   living until `cutoff_at + access-ttl`; a token whose session was issued at or before the cutoff, and is not the kept
 *   one, is revoked — so closing every session of a subject writes one key whatever the number of sessions.
 *
 * A revocation whose lifetime has already passed is not written: no token can name it. A cutoff never replaces a later
 * one. A check is one `MGET` of both keys.
 */
public class RedisRevocationList(
    private val redis: StringRedisTemplate,
    private val prefix: String,
    private val accessTtl: Duration,
    private val clock: Clock,
) : RevocationList {
    private val cutoffScript = DefaultRedisScript(CUTOFF_SCRIPT, Long::class.javaObjectType)

    override fun verdict(
        session: UUID,
        subject: SubjectRef,
        sessionIssuedAt: Instant,
    ): RevocationVerdict {
        val values =
            try {
                redis.opsForValue().multiGet(listOf(sessionKey(session), cutoffKey(subject)))
            } catch (failed: DataAccessException) {
                throw RevocationUnavailableException("read", failed)
            } ?: throw RevocationUnavailableException("read", IllegalStateException("MGET answered nothing"))
        check(values.size == 2) { "MGET of two keys answered ${values.size} values" }
        if (values[0] != null) return RevocationVerdict.REVOKED
        val cutoff = values[1]?.let { parseCutoff(subject, it) } ?: return RevocationVerdict.LIVE
        return if (cutoff.closes(session, sessionIssuedAt)) RevocationVerdict.REVOKED else RevocationVerdict.LIVE
    }

    override fun announceSessions(sessions: List<RevokedSession>) {
        val now = clock.instant()
        val alive = sessions.mapNotNull { session -> lifetime(session.revokedAt, now)?.let { session to it } }
        if (alive.isEmpty()) return
        try {
            redis.executePipelined(
                object : SessionCallback<Any?> {
                    override fun <K : Any, V : Any> execute(operations: RedisOperations<K, V>): Any? {
                        @Suppress("UNCHECKED_CAST")
                        val values = (operations as RedisOperations<String, String>).opsForValue()
                        alive.forEach { (session, ttl) -> values.set(sessionKey(session.id), MARKER, ttl) }
                        return null
                    }
                },
            )
        } catch (failed: DataAccessException) {
            throw RevocationUnavailableException("written", failed)
        }
    }

    override fun announceCutoff(cutoff: SubjectCutoff) {
        val ttl = lifetime(cutoff.cutoffAt, clock.instant()) ?: return
        val millis = cutoff.cutoffAt.toEpochMilli()
        try {
            redis.execute(
                cutoffScript,
                listOf(cutoffKey(cutoff.subject)),
                "$millis:${cutoff.keptSession?.toString().orEmpty()}",
                millis.toString(),
                ttl.toMillis().toString(),
            )
        } catch (failed: DataAccessException) {
            throw RevocationUnavailableException("written", failed)
        }
    }

    public fun sessionKey(session: UUID): String = "${prefix}s:$session"

    public fun cutoffKey(subject: SubjectRef): String = "${prefix}c:${subject.type.name}:${subject.id}"

    /** How long a revocation made at [at] still matters, or `null` when every token it could stop has expired. */
    private fun lifetime(
        at: Instant,
        now: Instant,
    ): Duration? = Duration.between(now, at.plus(accessTtl)).takeIf { it.toMillis() >= 1 }

    private fun parseCutoff(
        subject: SubjectRef,
        value: String,
    ): SubjectCutoff {
        val separator = value.indexOf(':')
        val millis = if (separator > 0) value.substring(0, separator).toLongOrNull() else null
        val keptText = if (separator > 0) value.substring(separator + 1) else null
        val kept = keptText?.takeIf(String::isNotEmpty)?.let(CanonicalIds::parse)
        check(millis != null && keptText != null && (keptText.isEmpty() || kept != null)) {
            "the revocation cutoff of ${subject.resourceId} holds a value this version does not write"
        }
        return SubjectCutoff(subject, Instant.ofEpochMilli(millis), kept)
    }

    private companion object {
        const val MARKER = "1"

        /** Writes the cutoff unless the key already holds a later one. */
        val CUTOFF_SCRIPT =
            """
            local current = redis.call('GET', KEYS[1])
            if current then
              local separator = string.find(current, ':', 1, true)
              if separator and tonumber(string.sub(current, 1, separator - 1)) > tonumber(ARGV[2]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            return 1
            """.trimIndent()
    }
}
