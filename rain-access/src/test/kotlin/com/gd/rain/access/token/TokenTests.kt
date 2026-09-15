package com.gd.rain.access.token

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.token.AccessClaims
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.token.AccessTokenVerifier
import com.gd.rain.access.internal.token.PresentedCredential
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.access.internal.token.RefreshWindow
import com.gd.rain.access.internal.token.RotationClassifier
import com.gd.rain.access.internal.token.RotationOutcome
import com.gd.rain.access.support.ACCESS_TTL
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AUDIENCE
import com.gd.rain.access.support.GRACE
import com.gd.rain.access.support.IDLE_TTL
import com.gd.rain.access.support.ISSUER
import com.gd.rain.access.support.KEY
import com.gd.rain.access.support.START
import com.gd.rain.test.MutableClock
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.Date
import java.util.UUID

private val SUBJECT = SubjectRef(AGENT, UUID.fromString("018f5b3a-1c2d-7e4f-8a9b-0c1d2e3f4a5b"))
private val SESSION: UUID = UUID.fromString("018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c")
private val ISSUED_AT = START.minusSeconds(30)

class AccessTokenTest {
    private val clock = MutableClock(START)
    private val issuer = AccessTokenIssuer(KEY, ISSUER, AUDIENCE, ACCESS_TTL)
    private val verifier = AccessTokenVerifier(KEY, ISSUER, AUDIENCE, clock)

    @Test
    fun `a token carries exactly the declared claims, the audience as one string and the session's issue instant in milliseconds`() {
        val minted = issuer.mint(SUBJECT, SESSION, ISSUED_AT, START.plus(Duration.ofDays(30)), START)

        val claims = SignedJWT.parse(minted.token).payload.toJSONObject()
        assertThat(claims.keys).isEqualTo(AccessClaims.ALL)
        assertThat(claims["sub"]).isEqualTo(SUBJECT.id.toString())
        assertThat(claims["sty"]).isEqualTo("agent")
        assertThat(claims["sid"]).isEqualTo(SESSION.toString())
        assertThat(claims["sit"]).isEqualTo(ISSUED_AT.toEpochMilli())
        assertThat(claims["aud"]).isEqualTo(AUDIENCE)
        assertThat(claims["exp"]).isEqualTo(START.plus(ACCESS_TTL).epochSecond)
    }

    @Test
    fun `the header names HS256 and JWT`() {
        val header = SignedJWT.parse(issuer.mint(SUBJECT, SESSION, ISSUED_AT, START.plusSeconds(3600), START).token).header

        assertThat(header.algorithm.name).isEqualTo("HS256")
        assertThat(header.type.toString()).isEqualTo("JWT")
    }

    @Test
    fun `the expiry is clamped to the session's own`() {
        assertThat(issuer.mint(SUBJECT, SESSION, ISSUED_AT, START.plusSeconds(30), START).expiresAt).isEqualTo(START.plusSeconds(30))
    }

    @Test
    fun `a token this issuer minted verifies with every claim it carried`() {
        val minted = issuer.mint(SUBJECT, SESSION, ISSUED_AT, START.plus(Duration.ofDays(30)), START)

        val verified = requireNotNull(verifier.verify(minted.token))

        assertThat(verified.subject).isEqualTo(SUBJECT)
        assertThat(verified.session).isEqualTo(SESSION)
        assertThat(verified.sessionIssuedAt).isEqualTo(ISSUED_AT)
        assertThat(verified.expiresAt).isEqualTo(minted.expiresAt)
    }

    @Test
    fun `another key, another issuer or another audience is refused`() {
        val otherKey = AccessTokenIssuer(ByteArray(32) { 9 }, ISSUER, AUDIENCE, ACCESS_TTL)
        val otherIssuer = AccessTokenIssuer(KEY, "somebody-else", AUDIENCE, ACCESS_TTL)
        val otherAudience = AccessTokenIssuer(KEY, ISSUER, "another-service", ACCESS_TTL)

        listOf(otherKey, otherIssuer, otherAudience).forEach {
            assertThat(verifier.verify(it.mint(SUBJECT, SESSION, ISSUED_AT, START.plusSeconds(3600), START).token)).isNull()
        }
    }

    @Test
    fun `the expiry is exact - valid up to it and refused a second past, with no skew`() {
        val minted = issuer.mint(SUBJECT, SESSION, ISSUED_AT, START.plus(Duration.ofDays(30)), START)

        clock.set(minted.expiresAt.minusSeconds(1))
        assertThat(verifier.verify(minted.token)).isNotNull()
        clock.set(minted.expiresAt.plusSeconds(1))
        assertThat(verifier.verify(minted.token)).isNull()
    }

    @Test
    fun `a token with no expiry, a malformed subject type, a non-canonical session or no sit is refused`() {
        fun signed(build: JWTClaimsSet.Builder.() -> Unit): String {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .subject(SUBJECT.id.toString())
                    .claim("sty", "agent")
                    .claim("sid", SESSION.toString())
                    .claim("sit", ISSUED_AT.toEpochMilli())
                    .issuer(ISSUER)
                    .claim("aud", AUDIENCE)
                    .expirationTime(Date.from(START.plusSeconds(60)))
                    .apply(build)
                    .build()
            return SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims,
            ).apply { sign(MACSigner(KEY)) }.serialize()
        }

        assertThat(verifier.verify(signed {})).isNotNull()
        assertThat(verifier.verify(signed { expirationTime(null) })).isNull()
        assertThat(verifier.verify(signed { claim("sty", "Agent!") })).isNull()
        assertThat(verifier.verify(signed { claim("sid", "1-1-1-1-1") })).isNull()
        assertThat(verifier.verify(signed { claim("sit", null) })).isNull()
    }

    @Test
    fun `an unsigned token, garbage and an empty string are refused rather than thrown`() {
        val unsigned = PlainJWT(JWTClaimsSet.Builder().subject(SUBJECT.id.toString()).build()).serialize()

        assertThat(verifier.verify(unsigned)).isNull()
        assertThat(verifier.verify("")).isNull()
        assertThat(verifier.verify("not.a.token")).isNull()
        assertThat(verifier.verify(issuer.mint(SUBJECT, SESSION, ISSUED_AT, START.plusSeconds(3600), START).token.dropLast(3))).isNull()
    }
}

class RefreshCredentialTest {
    @Test
    fun `a credential names its generation and session ahead of 43 characters of base64url`() {
        val credential = RefreshCredential.mint(7, SESSION)

        assertThat(credential).startsWith("7.$SESSION.")
        assertThat(credential.substringAfterLast('.')).hasSize(43).matches("[A-Za-z0-9_-]+")
        assertThat(RefreshCredential.prefixOf(credential)).isEqualTo(RefreshCredential.Prefix(7, SESSION))
        assertThat(List(32) { RefreshCredential.mint(1, SESSION) }.toSet()).hasSize(32)
    }

    @Test
    fun `a generation starts at one`() {
        assertThatThrownBy { RefreshCredential.mint(0, SESSION) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "no-dots-at-all",
            "1.only-one-dot",
            "0.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.random",
            "-1.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.random",
            "01.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.random",
            "1.not-a-uuid.random",
            "1.1-1-1-1-1.random",
            "1.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.",
            "1.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.a.b",
        ],
    )
    fun `a value without a prefix this application writes has none`(credential: String) {
        assertThat(RefreshCredential.prefixOf(credential)).isNull()
    }

    @Test
    fun `the digest is lower-case hexadecimal SHA-256 of the whole credential`() {
        assertThat(RefreshCredential.digest("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(RefreshCredential.digest("1.$SESSION.x")).isNotEqualTo(RefreshCredential.digest("2.$SESSION.x"))
    }
}

class RotationClassifierTest {
    private val window = RefreshWindow(GRACE, IDLE_TTL)

    private fun presented(
        digest: String = "current",
        previous: String? = "previous",
        generation: Long = 4,
        currentGeneration: Long = 4,
        rotatedAt: java.time.Instant? = START.minusSeconds(1),
        lastUsedAt: java.time.Instant = START.minusSeconds(1),
        revoked: Boolean = false,
        expiresAt: java.time.Instant = START.plus(Duration.ofDays(30)),
    ) = PresentedCredential(digest, "current", previous, generation, currentGeneration, rotatedAt, lastUsedAt, revoked, expiresAt)

    private fun classify(credential: PresentedCredential): RotationOutcome = RotationClassifier.classify(credential, START, window)

    @Test
    fun `each branch answers in order`() {
        assertThat(classify(presented(revoked = true))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThat(classify(presented(expiresAt = START))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThat(classify(presented(lastUsedAt = START.minus(IDLE_TTL).minusSeconds(1)))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThat(classify(presented())).isEqualTo(RotationOutcome.ROTATE)
        assertThat(classify(presented(digest = "stale", generation = 2, currentGeneration = 4))).isEqualTo(RotationOutcome.REPLAY)
        assertThat(classify(presented(digest = "stale", previous = null))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThat(classify(presented(digest = "stale"))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThat(classify(presented(digest = "previous", rotatedAt = null))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThat(classify(presented(digest = "previous", rotatedAt = START.minusSeconds(9)))).isEqualTo(RotationOutcome.ROTATE_AGAIN)
        assertThat(classify(presented(digest = "previous", rotatedAt = START.minus(GRACE)))).isEqualTo(RotationOutcome.ROTATE_AGAIN)
        assertThat(
            classify(presented(digest = "previous", rotatedAt = START.minus(GRACE).minusMillis(1))),
        ).isEqualTo(RotationOutcome.REPLAY)
    }

    @Test
    fun `revoked outranks a current digest, and a window states both bounds`() {
        assertThat(classify(presented(digest = "current", revoked = true))).isEqualTo(RotationOutcome.UNUSABLE)
        assertThatThrownBy { RefreshWindow(GRACE, Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(RotationOutcome.entries.map { it.wire }).containsExactly("rotate", "rotate-again", "replay", "unusable")
    }
}
