package com.gd.rain.access

import com.gd.rain.access.internal.token.SigningKey
import com.gd.rain.access.internal.token.SigningKeyResolution
import com.gd.rain.boot.config.BoundSections
import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.CrossSectionRule
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.RequiredFromEnvironment
import com.gd.rain.boot.config.RuleOutcome
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.ProblemCollector
import com.gd.rain.core.config.problems
import com.gd.rain.web.config.RainWebProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

/** Where the credentials a sign-in answers with are delivered. */
public enum class CredentialDelivery(
    public val wire: String,
) {
    /** Both halves in `HttpOnly` cookies; the body carries only the principal. Requests authenticate by cookie. */
    COOKIES("cookies"),

    /** Both halves in the body. Requests authenticate with `Authorization: Bearer`. */
    BODY("body"),

    /** Each sign-in states `Rain-Auth-Delivery: cookies|body`; requests authenticate by either, never both at once. */
    BOTH("both"),
    ;

    public companion object {
        public val wireNames: List<String> = entries.map(CredentialDelivery::wire)

        public fun fromWire(value: String): CredentialDelivery? = entries.firstOrNull { it.wire == value }
    }
}

/** Where failed sign-in attempts are counted. */
public enum class AttemptStore(
    public val wire: String,
) {
    /** On Redis, shared by every replica; one atomic script per key. */
    REDIS("redis"),

    /** In this process, bounded by `maximum-keys`; refused in the `prod` stage. */
    MEMORY("memory"),
}

/** Where closed sessions are announced so their access tokens stop answering before they expire. */
public enum class RevocationStore(
    public val wire: String,
) {
    REDIS("redis"),

    /** No revocation list: a closed session's access token answers until it expires, at most `token.access-ttl`. */
    NONE("none"),
}

/** The only eviction policy that keeps a key for as long as it was written for. */
public enum class EvictionPolicyAttestation(
    public val wire: String,
) {
    NOEVICTION("noeviction"),
}

/**
 * `rain.access`. Every choice that decides behaviour — the base path, the delivery, the token issuer, audience, key and
 * lifetimes, the stores, the gate numbers, the role ceiling — is required; tuning numbers carry declared defaults,
 * stated once here. Every count is at least one and every duration positive.
 */
@ConfigurationProperties(AccessProperties.PREFIX)
public data class AccessProperties(
    public val web: Web,
    public val token: Token,
    public val session: Session,
    public val password: Password,
    public val hashing: Hashing,
    public val attempts: Attempts,
    public val revocation: Revocation,
    public val gate: Gate,
    public val grants: Grants,
    public val provisioning: Provisioning = Provisioning(),
    public val catalogue: Catalogue = Catalogue(),
) : ConfigurationSection {
    public data class Web(
        /** The path every rain-access route is mounted under, e.g. `/api`; the refresh cookie's path derives from it. */
        public val basePath: String,
        public val delivery: CredentialDelivery,
        public val page: Page = Page(),
    ) : ConfigurationSection {
        public val authPath: String get() = "$basePath/auth"
        public val refreshPath: String get() = "$authPath/refresh"
    }

    /** Keyset pages every list route answers with. */
    public data class Page(
        public val defaultSize: Int = 50,
        public val maxSize: Int = 200,
    ) : ConfigurationSection

    public data class Token(
        public val issuer: String,
        public val audience: String,
        @param:RequiredFromEnvironment(DeploymentStage.PROD)
        public val signingKey: String,
        public val accessTtl: Duration,
    ) : ConfigurationSection {
        override fun toString(): String = "Token(issuer=$issuer, audience=$audience, signingKey=<redacted>, accessTtl=$accessTtl)"
    }

    public data class Session(
        /** Absolute: no rotation moves a session's expiry. */
        public val ttl: Duration,
        /** A session not rotated for this long is unusable. */
        public val idleTtl: Duration,
        public val retention: Retention,
        /** How long after a rotation the superseded refresh credential still rotates (two tabs refreshing at once). */
        public val refreshGrace: Duration = Duration.ofSeconds(10),
        /** How many times a rotation that lost its compare-and-set re-reads and tries again. */
        public val rotationAttempts: Int = 3,
        /** Sessions closed per transaction when every session of a subject is closed. */
        public val revokeBatch: Int = 500,
    ) : ConfigurationSection

    /** Expired and revoked sessions are deleted once they are older than [keepFor], in batches, by the worker. */
    public data class Retention(
        public val keepFor: Duration,
        public val interval: Duration,
        public val batch: Int = 500,
        public val batchesPerRun: Int = 20,
    ) : ConfigurationSection

    public data class Password(
        /** Whether a password change closes the subject's other sessions. */
        public val revokeOtherSessionsOnChange: Boolean,
        /** In Unicode code points. */
        public val minLength: Int = 10,
        /** In UTF-8 bytes: what the hasher reads. */
        public val maxBytes: Int = 256,
        public val maxIdentifierBytes: Int = 320,
    ) : ConfigurationSection

    /**
     * Argon2id behind the Resilience4j bulkhead instance [HASHING_BULKHEAD], whose `max-concurrent-calls` and
     * `max-wait-duration` are stated explicitly under `resilience4j.bulkhead.instances`. [queue] is how many callers may
     * wait for a permit at once; one more is refused at once.
     */
    public data class Hashing(
        public val queue: Int,
        public val argon2: Argon2 = Argon2(),
    ) : ConfigurationSection

    public data class Argon2(
        public val saltBytes: Int = 16,
        public val hashBytes: Int = 32,
        public val parallelism: Int = 4,
        public val memoryKib: Int = 65_536,
        public val iterations: Int = 3,
    ) : ConfigurationSection

    public data class Attempts(
        public val store: AttemptStore,
        public val perIdentifier: Int = 10,
        public val perAddress: Int = 50,
        public val window: Duration = Duration.ofMinutes(15),
        public val lockFor: Duration = Duration.ofMinutes(15),
        public val memory: MemoryAttempts? = null,
        public val redis: RedisAttempts? = null,
    ) : ConfigurationSection

    public data class MemoryAttempts(
        /** The most attempt keys one process remembers; a full table refuses callers it does not hold. */
        public val maximumKeys: Int,
    ) : ConfigurationSection

    public data class RedisAttempts(
        public val keyPrefix: String,
        public val evictionPolicyAttested: EvictionPolicyAttestation? = null,
    ) : ConfigurationSection

    public data class Revocation(
        public val store: RevocationStore,
        public val redis: RedisRevocation? = null,
    ) : ConfigurationSection

    public data class RedisRevocation(
        public val keyPrefix: String,
        public val replay: Replay,
        /** Stated only after verifying out of band that the server does not evict, when it will not answer `CONFIG`. */
        public val evictionPolicyAttested: EvictionPolicyAttestation? = null,
    ) : ConfigurationSection

    /** The worker's replay of recent revocations onto the list; every interval covers at most one access-token lifetime. */
    public data class Replay(
        public val interval: Duration,
        public val pageSize: Int = 500,
        public val pagesPerRun: Int = 20,
    ) : ConfigurationSection

    /** What stands in front of the credential surface besides the hashing bulkhead. */
    public data class Gate(
        public val throttle: Throttle,
    ) : ConfigurationSection

    /** A token bucket per client address on unsafe credential requests. */
    public data class Throttle(
        public val perMinute: Int,
        public val burst: Int,
        public val callers: Int,
    ) : ConfigurationSection

    public data class Grants(
        /** The most roles one subject may hold; it bounds what a permission check reads per role. */
        public val maxRolesPerSubject: Int,
    ) : ConfigurationSection

    public data class Provisioning(
        public val holderPageSize: Int = 100,
        public val holderPageBudget: Int = 10,
    ) : ConfigurationSection

    public data class Catalogue(
        /** Declared permissions written per statement by the start-up synchronisation. */
        public val chunkSize: Int = 500,
    ) : ConfigurationSection

    public fun problems(stage: DeploymentStage): List<ConfigurationProblem> =
        problems {
            webProblems()
            tokenProblems(stage)
            sessionProblems()
            passwordProblems()
            hashingProblems()
            attemptProblems(stage)
            revocationProblems()
            count(gate.throttle.perMinute, "$PREFIX.gate.throttle.per-minute")
            count(gate.throttle.burst, "$PREFIX.gate.throttle.burst")
            count(gate.throttle.callers, "$PREFIX.gate.throttle.callers")
            count(grants.maxRolesPerSubject, "$PREFIX.grants.max-roles-per-subject")
            count(provisioning.holderPageSize, "$PREFIX.provisioning.holder-page-size")
            count(provisioning.holderPageBudget, "$PREFIX.provisioning.holder-page-budget")
            count(catalogue.chunkSize, "$PREFIX.catalogue.chunk-size")
        }

    private fun ProblemCollector.webProblems() {
        expect(BASE_PATH.matches(web.basePath), "$PREFIX.web.base-path") {
            "is \"${web.basePath}\"; it is one or more path segments with no trailing slash, matching ${BASE_PATH.pattern}"
        }
        count(web.page.defaultSize, "$PREFIX.web.page.default-size")
        count(web.page.maxSize, "$PREFIX.web.page.max-size")
        expect(web.page.maxSize >= web.page.defaultSize, "$PREFIX.web.page.max-size", ProblemCode.CONTRADICTS) {
            "is ${web.page.maxSize}, below web.page.default-size ${web.page.defaultSize}"
        }
    }

    private fun ProblemCollector.tokenProblems(stage: DeploymentStage) {
        expect(token.issuer.isNotBlank(), "$PREFIX.token.issuer") { "is blank; a token names who issued it" }
        expect(token.audience.isNotBlank(), "$PREFIX.token.audience") { "is blank; a token names who it is for" }
        val key = SigningKey.resolve(token.signingKey, stage)
        if (key is SigningKeyResolution.Refused) add(ConfigurationProblem("$PREFIX.token.signing-key", ProblemCode.INVALID, key.problem))
        positive(token.accessTtl, "$PREFIX.token.access-ttl")
    }

    private fun ProblemCollector.sessionProblems() {
        positive(session.ttl, "$PREFIX.session.ttl")
        positive(session.idleTtl, "$PREFIX.session.idle-ttl")
        positive(session.refreshGrace, "$PREFIX.session.refresh-grace")
        count(session.rotationAttempts, "$PREFIX.session.rotation-attempts")
        count(session.revokeBatch, "$PREFIX.session.revoke-batch")
        positive(session.retention.keepFor, "$PREFIX.session.retention.keep-for")
        positive(session.retention.interval, "$PREFIX.session.retention.interval")
        count(session.retention.batch, "$PREFIX.session.retention.batch")
        count(session.retention.batchesPerRun, "$PREFIX.session.retention.batches-per-run")
        expect(session.idleTtl <= session.ttl, "$PREFIX.session.idle-ttl", ProblemCode.CONTRADICTS) {
            "is ${session.idleTtl.written()}, longer than session.ttl ${session.ttl.written()}; a session cannot idle longer than it lives"
        }
        expect(token.accessTtl <= session.idleTtl, "$PREFIX.token.access-ttl", ProblemCode.CONTRADICTS) {
            "is ${token.accessTtl.written()}, longer than session.idle-ttl ${session.idleTtl.written()}; " +
                "an idle session would keep answering until its token expired"
        }
        expect(session.refreshGrace < session.idleTtl, "$PREFIX.session.refresh-grace", ProblemCode.CONTRADICTS) {
            "is ${session.refreshGrace.written()}, not shorter than session.idle-ttl ${session.idleTtl.written()}; " +
                "a superseded credential would stay usable for as long as an unused session"
        }
    }

    private fun ProblemCollector.passwordProblems() {
        count(password.minLength, "$PREFIX.password.min-length")
        count(password.maxBytes, "$PREFIX.password.max-bytes")
        count(password.maxIdentifierBytes, "$PREFIX.password.max-identifier-bytes")
        expect(password.minLength <= password.maxBytes, "$PREFIX.password.max-bytes", ProblemCode.CONTRADICTS) {
            "is ${password.maxBytes}, below password.min-length ${password.minLength}; no password could satisfy both"
        }
    }

    private fun ProblemCollector.hashingProblems() {
        count(hashing.queue, "$PREFIX.hashing.queue")
        count(hashing.argon2.saltBytes, "$PREFIX.hashing.argon2.salt-bytes")
        count(hashing.argon2.hashBytes, "$PREFIX.hashing.argon2.hash-bytes")
        count(hashing.argon2.parallelism, "$PREFIX.hashing.argon2.parallelism")
        count(hashing.argon2.memoryKib, "$PREFIX.hashing.argon2.memory-kib")
        count(hashing.argon2.iterations, "$PREFIX.hashing.argon2.iterations")
    }

    private fun ProblemCollector.attemptProblems(stage: DeploymentStage) {
        val path = "$PREFIX.attempts"
        count(attempts.perIdentifier, "$path.per-identifier")
        count(attempts.perAddress, "$path.per-address")
        positive(attempts.window, "$path.window")
        positive(attempts.lockFor, "$path.lock-for")
        when (attempts.store) {
            AttemptStore.MEMORY -> {
                expect(stage != DeploymentStage.PROD, "$path.store") {
                    "is memory in the ${stage.wire} stage; counters in one process are not shared by its replicas, so every replica " +
                        "admits the whole ceiling again; state redis"
                }
                val memory = attempts.memory
                if (memory == null) {
                    add(required("$path.memory.maximum-keys", "the store is memory"))
                } else {
                    count(memory.maximumKeys, "$path.memory.maximum-keys")
                }
                expect(attempts.redis == null, "$path.redis", ProblemCode.CONTRADICTS) { "is stated while the store is memory" }
            }

            AttemptStore.REDIS -> {
                val redis = attempts.redis
                if (redis == null) {
                    add(required("$path.redis.key-prefix", "the store is redis"))
                } else {
                    keyPrefix(redis.keyPrefix, "$path.redis.key-prefix")
                }
                expect(attempts.memory == null, "$path.memory", ProblemCode.CONTRADICTS) { "is stated while the store is redis" }
            }
        }
    }

    private fun ProblemCollector.revocationProblems() {
        val path = "$PREFIX.revocation"
        when (revocation.store) {
            RevocationStore.NONE -> {
                expect(revocation.redis == null, "$path.redis", ProblemCode.CONTRADICTS) { "is stated while the store is none" }
            }

            RevocationStore.REDIS -> {
                val redis = revocation.redis
                if (redis == null) {
                    add(required("$path.redis.key-prefix", "the store is redis"))
                    add(required("$path.redis.replay.interval", "the store is redis"))
                    return
                }
                keyPrefix(redis.keyPrefix, "$path.redis.key-prefix")
                positive(redis.replay.interval, "$path.redis.replay.interval")
                count(redis.replay.pageSize, "$path.redis.replay.page-size")
                count(redis.replay.pagesPerRun, "$path.redis.replay.pages-per-run")
                expect(redis.replay.interval < token.accessTtl, "$path.redis.replay.interval", ProblemCode.CONTRADICTS) {
                    "is ${redis.replay.interval.written()}, not shorter than token.access-ttl ${token.accessTtl.written()}; " +
                        "a revocation the list missed would stay unannounced for the whole life of the token it should stop"
                }
                expect(
                    attempts.store != AttemptStore.REDIS || attempts.redis?.keyPrefix != redis.keyPrefix,
                    "$path.redis.key-prefix",
                    ProblemCode.CONTRADICTS,
                ) { "is the same prefix as attempts.redis.key-prefix; the two keyspaces are kept apart" }
            }
        }
    }

    private fun required(
        path: String,
        because: String,
    ): ConfigurationProblem = ConfigurationProblem(path, ProblemCode.REQUIRED, "no value is provided; $because")

    private fun ProblemCollector.keyPrefix(
        prefix: String,
        path: String,
    ) {
        expect(KEY_PREFIX.matches(prefix), path) { "is \"$prefix\"; a key prefix matches ${KEY_PREFIX.pattern}" }
    }

    private fun ProblemCollector.count(
        value: Int,
        path: String,
    ) {
        expect(value >= 1, path) { "is $value; it is at least 1" }
    }

    private fun ProblemCollector.positive(
        value: Duration,
        path: String,
    ) {
        expect(value.isPositive, path) { "is ${value.written()}; it has to be positive" }
    }

    public companion object {
        public const val PREFIX: String = "rain.access"

        /** The Resilience4j bulkhead instance password hashing runs in. */
        public const val HASHING_BULKHEAD: String = "rain-access-hashing"

        public val BASE_PATH: Regex = Regex("^(/[A-Za-z0-9._~-]+)+$")
        public val KEY_PREFIX: Regex = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    }
}

/** Declares `rain.access` and its cross-section rules to rain's configuration validation. */
public class AccessConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(AccessProperties.PREFIX, AccessProperties::class, Presence.REQUIRED) { section, stage -> section.problems(stage) },
        )
}
