package com.gd.rain.access.internal.token

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.internal.web.CanonicalIds
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtTypeValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** The access token's claims, spelled once. */
public object AccessClaims {
    public const val SUBJECT: String = "sub"
    public const val SUBJECT_TYPE: String = "sty"
    public const val SESSION: String = "sid"

    /** When the session the token belongs to was issued, in epoch milliseconds; a subject cutoff is compared against it. */
    public const val SESSION_ISSUED_AT: String = "sit"
    public const val ISSUER: String = "iss"
    public const val AUDIENCE: String = "aud"
    public const val ISSUED_AT: String = "iat"
    public const val EXPIRY: String = "exp"

    public val ALL: Set<String> = setOf(SUBJECT, SUBJECT_TYPE, SESSION, SESSION_ISSUED_AT, ISSUER, AUDIENCE, ISSUED_AT, EXPIRY)
}

public data class MintedAccessToken(
    public val token: String,
    public val expiresAt: Instant,
)

public data class VerifiedAccessToken(
    public val subject: SubjectRef,
    public val session: UUID,
    public val sessionIssuedAt: Instant,
    public val expiresAt: Instant,
)

/**
 * The short-lived half of the pair: HS256, eight claims, and an expiry clamped to the session's own, so a token minted
 * near the end of a session does not outlive it. The token carries no grant: what the subject may do is read on every
 * decision.
 */
public class AccessTokenIssuer(
    key: ByteArray,
    private val issuer: String,
    private val audience: String,
    private val accessTtl: Duration,
) {
    private val encoder: NimbusJwtEncoder =
        NimbusJwtEncoder
            .withSecretKey(SecretKeySpec(key, MAC))
            .algorithm(MacAlgorithm.HS256)
            .build()

    private val header: JwsHeader = JwsHeader.with(MacAlgorithm.HS256).type(TYPE).build()

    public fun mint(
        subject: SubjectRef,
        session: UUID,
        sessionIssuedAt: Instant,
        sessionExpiresAt: Instant,
        now: Instant,
    ): MintedAccessToken {
        // A NumericDate is whole seconds; the expiry answered is the one the token carries.
        val expiry = minOf(now.plus(accessTtl), sessionExpiresAt).truncatedTo(ChronoUnit.SECONDS)
        val claims =
            JwtClaimsSet
                .builder()
                .subject(subject.id.toString())
                .claim(AccessClaims.SUBJECT_TYPE, subject.type.name)
                .claim(AccessClaims.SESSION, session.toString())
                .claim(AccessClaims.SESSION_ISSUED_AT, sessionIssuedAt.toEpochMilli())
                .issuer(issuer)
                // One audience, as a string; Spring's audience() writes the multi-valued array form.
                .claim(AccessClaims.AUDIENCE, audience)
                .issuedAt(now.truncatedTo(ChronoUnit.SECONDS))
                .expiresAt(expiry)
                .build()
        return MintedAccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, expiry)
    }

    private companion object {
        const val MAC = "HmacSHA256"
        const val TYPE = "JWT"
    }
}

/**
 * The read half: HS256 and nothing else, `typ` JWT, an expiry required and checked with no clock skew against the
 * injected clock, issuer and audience equal to the configured ones, and every rain claim present and well formed. A
 * token that fails any of it is `null`, never an exception.
 */
public class AccessTokenVerifier(
    key: ByteArray,
    issuer: String,
    audience: String,
    clock: Clock,
) {
    private val decoder: NimbusJwtDecoder =
        NimbusJwtDecoder
            .withSecretKey(SecretKeySpec(key, MAC))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply {
                setJwtValidator(
                    DelegatingOAuth2TokenValidator(
                        JwtTypeValidator.jwt(),
                        JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
                        ExpiryRequired,
                        JwtIssuerValidator(issuer),
                        SingleAudience(audience),
                    ),
                )
            }

    public fun verify(token: String): VerifiedAccessToken? {
        if (token.isEmpty()) return null
        val jwt =
            try {
                decoder.decode(token)
            } catch (refused: JwtException) {
                return null
            }
        val type = jwt.getClaimAsString(AccessClaims.SUBJECT_TYPE)?.takeIf(SubjectType::isWellFormed) ?: return null
        val subject = jwt.subject?.let(CanonicalIds::parse) ?: return null
        val session = jwt.getClaimAsString(AccessClaims.SESSION)?.let(CanonicalIds::parse) ?: return null
        val issuedAt = (jwt.claims[AccessClaims.SESSION_ISSUED_AT] as? Number)?.toLong() ?: return null
        val expiresAt = jwt.expiresAt ?: return null
        return VerifiedAccessToken(SubjectRef(SubjectType(type), subject), session, Instant.ofEpochMilli(issuedAt), expiresAt)
    }

    /** The timestamp validator passes a token without `exp`; a token that never expires is one no revocation can outlast. */
    private object ExpiryRequired : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.expiresAt != null) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error(INVALID_TOKEN, "the token carries no exp", null))
            }
    }

    /** Exactly the configured audience: present, and alone. */
    private class SingleAudience(
        private val audience: String,
    ) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.audience.orEmpty() == listOf(audience)) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error(INVALID_TOKEN, "the token was not issued for this audience", null))
            }
    }

    private companion object {
        const val MAC = "HmacSHA256"
        const val INVALID_TOKEN = "invalid_token"
    }
}
