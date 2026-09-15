package com.gd.rain.access.internal.revocation

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SubjectCutoff
import java.time.Instant
import java.util.UUID

public enum class RevocationVerdict {
    LIVE,
    REVOKED,
}

/**
 * The list an access token is checked against on every request, so a closed session stops answering before its token
 * expires. The database is the record; the list is told what the record says, and a replay tells it again.
 *
 * [verdict] fails closed: a list that could not be asked throws [RevocationUnavailableException], never answers live.
 */
public interface RevocationList {
    public fun verdict(
        session: UUID,
        subject: SubjectRef,
        sessionIssuedAt: Instant,
    ): RevocationVerdict

    public fun announceSessions(sessions: List<RevokedSession>)

    public fun announceCutoff(cutoff: SubjectCutoff)
}

/** `rain.access.revocation.store: none`: nothing is announced, and a token answers until it expires. */
public object NoRevocationList : RevocationList {
    override fun verdict(
        session: UUID,
        subject: SubjectRef,
        sessionIssuedAt: Instant,
    ): RevocationVerdict = RevocationVerdict.LIVE

    override fun announceSessions(sessions: List<RevokedSession>) {
        // The deployment stated that closed sessions are not announced.
    }

    override fun announceCutoff(cutoff: SubjectCutoff) {
        // The deployment stated that closed sessions are not announced.
    }
}

/** The list could not be read or written. It is never an answer of "live". */
public class RevocationUnavailableException(
    what: String,
    cause: Throwable,
) : RuntimeException("the revocation list could not be $what: ${cause.javaClass.simpleName}", cause)
