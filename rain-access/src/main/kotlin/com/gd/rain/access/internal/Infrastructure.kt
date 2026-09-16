package com.gd.rain.access.internal

import com.gd.rain.access.AccessProperties
import com.gd.rain.access.AttemptStore
import com.gd.rain.access.RevocationStore
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.Admission
import com.gd.rain.access.internal.attempt.Attempt
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.FailureRecorded
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.revocation.RevocationVerdict
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer
import io.github.resilience4j.common.bulkhead.configuration.CommonBulkheadConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.time.Instant
import java.util.UUID

/**
 * The hashing bulkhead is configured explicitly: `resilience4j.bulkhead.instances.rain-access-hashing` states its own
 * `max-concurrent-calls` (at least one) and `max-wait-duration` (not negative), and no `BulkheadConfigCustomizer` names
 * it — never the library's defaults, never a value changed where this check cannot read it.
 */
public class HashingBulkheadCheck(
    private val properties: CommonBulkheadConfigurationProperties?,
    private val customizers: List<BulkheadConfigCustomizer>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val path = "$INSTANCES.${AccessProperties.HASHING_BULKHEAD}"
        if (properties == null) {
            return listOf(
                ConfigurationProblem(
                    path,
                    ProblemCode.REQUIRED,
                    "the Resilience4j bulkhead auto-configuration is not active; password hashing runs in its registry",
                ),
            )
        }
        val instance =
            properties.instances[AccessProperties.HASHING_BULKHEAD]
                ?: return listOf(
                    ConfigurationProblem(
                        path,
                        ProblemCode.REQUIRED,
                        "no value is provided; password hashing runs in this bulkhead instance",
                    ),
                )
        return problems {
            val calls = instance.maxConcurrentCalls
            if (calls == null) {
                add(ConfigurationProblem("$path.max-concurrent-calls", ProblemCode.REQUIRED, "no value is provided on the instance"))
            } else {
                expect(calls >= 1, "$path.max-concurrent-calls") { "is $calls; at least one hash is derived at a time" }
            }
            val wait = instance.maxWaitDuration
            if (wait == null) {
                add(ConfigurationProblem("$path.max-wait-duration", ProblemCode.REQUIRED, "no value is provided on the instance"))
            } else {
                expect(!wait.isNegative, "$path.max-wait-duration") { "is $wait; a wait is not negative" }
            }
            expect(customizers.none { it.name() == AccessProperties.HASHING_BULKHEAD }, path, ProblemCode.CONTRADICTS) {
                "a BulkheadConfigCustomizer names it; the hashing bulkhead is configured by its properties alone"
            }
        }
    }

    public companion object {
        public const val INSTANCES: String = "resilience4j.bulkhead.instances"
    }
}

/**
 * Keeps Spring Boot's in-memory user store out of every application with rain-access: it would create an account named
 * `user` with a password printed to the log, which nothing revokes and nobody chose. Nothing else is filtered.
 */
public class UserDetailsServiceExclusionFilter : AutoConfigurationImportFilter {
    override fun match(
        autoConfigurationClasses: Array<out String?>,
        autoConfigurationMetadata: AutoConfigurationMetadata,
    ): BooleanArray = BooleanArray(autoConfigurationClasses.size) { autoConfigurationClasses[it] != EXCLUDED }

    public companion object {
        public const val EXCLUDED: String = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
    }
}

/**
 * What stands in for the revocation list when it is stated as `redis` and no Redis client is on the classpath.
 * [RedisClientCheck] refuses that start before anything is served, so it is never called. Each store has a stand-in of
 * its own: one object standing in for both would also be a second candidate for the store that is not on Redis, and the
 * start would fail on wiring instead of naming what is wrong.
 */
public object UnavailableRevocationList : RevocationList {
    override fun verdict(
        session: UUID,
        subject: SubjectRef,
        sessionIssuedAt: Instant,
    ): RevocationVerdict = unavailable()

    override fun announceSessions(sessions: List<RevokedSession>): Unit = unavailable()

    override fun announceCutoff(cutoff: SubjectCutoff): Unit = unavailable()
}

/** What stands in for the attempt counters when they are stated as `redis` and no Redis client is on the classpath. */
public object UnavailableAttemptLimiter : AttemptLimiter {
    override fun admit(attempt: Attempt): Admission = unavailable()

    override fun recordFailure(attempt: Attempt): FailureRecorded = unavailable()

    override fun recordSuccess(attempt: Attempt): Unit = unavailable()
}

private fun unavailable(): Nothing = error("a store is stated as redis and no Redis client is on the classpath; start-up refuses that")

/** A rain-access store stated as `redis`: what it is, where its section is, and the qualifier of a factory of its own. */
public class RedisStoreClaim(
    public val purpose: String,
    public val path: String,
    public val qualifier: String,
    /** The `RedisConnectionFactory` beans named or qualified [qualifier]. */
    public val qualified: ObjectProvider<RedisConnectionFactory>,
)

/**
 * Which `RedisConnectionFactory` each Redis store of rain-access uses, decided once for every store stated as `redis`:
 *
 * 1. the one bean named or qualified with the store's qualifier (`rainRevocation`, `rainAttempts`);
 * 2. otherwise the application's one factory: the only `RedisConnectionFactory` bean, or the one marked primary.
 *
 * Anything else — two beans with the store's qualifier, no factory at all, several factories with none primary and
 * none qualified — is a configuration problem naming the qualifiers, and every store's problems refuse the start
 * together. Nothing is picked by the order beans were found in.
 */
public class RedisConnectionChoice(
    private val claims: List<RedisStoreClaim>,
    private val application: ObjectProvider<RedisConnectionFactory>,
    /** The names of every `RedisConnectionFactory` bean, for the problems. */
    private val names: List<String>,
) {
    /** The factory of the store claimed with [qualifier]; a refusal of every store's problem when any store has one. */
    public fun factoryOf(qualifier: String): RedisConnectionFactory {
        val chosen = claims.associate { it.qualifier to choose(it) }
        val problems = chosen.values.mapNotNull { it as? ConfigurationProblem }
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
        return checkNotNull(chosen[qualifier] as? RedisConnectionFactory) { "no store is claimed with the qualifier $qualifier" }
    }

    public companion object {
        /** The choice for the stores [properties] state as `redis`, each with the factories named or qualified for it. */
        public fun forStores(
            properties: AccessProperties,
            revocation: ObjectProvider<RedisConnectionFactory>,
            attempts: ObjectProvider<RedisConnectionFactory>,
            application: ObjectProvider<RedisConnectionFactory>,
            names: List<String>,
        ): RedisConnectionChoice =
            RedisConnectionChoice(
                listOfNotNull(
                    RedisStoreClaim(
                        "the revocation list",
                        "${AccessProperties.PREFIX}.revocation.redis",
                        RedisQualifiers.REVOCATION,
                        revocation,
                    ).takeIf { properties.revocation.store == RevocationStore.REDIS },
                    RedisStoreClaim("the attempt counters", "${AccessProperties.PREFIX}.attempts.redis", RedisQualifiers.ATTEMPTS, attempts)
                        .takeIf { properties.attempts.store == AttemptStore.REDIS },
                ),
                application,
                names,
            )
    }

    private fun choose(claim: RedisStoreClaim): Any {
        claim.qualified.getIfUnique()?.let { return it }
        val qualified = claim.qualified.stream().count()
        if (qualified > 0) {
            return ConfigurationProblem(
                claim.path,
                ProblemCode.CONTRADICTS,
                "$qualified RedisConnectionFactory beans are named or qualified ${claim.qualifier} and none is primary; " +
                    "${claim.purpose} uses exactly one",
            )
        }
        application.getIfUnique()?.let { return it }
        return if (names.isEmpty()) {
            ConfigurationProblem(
                claim.path,
                ProblemCode.REQUIRED,
                "no RedisConnectionFactory bean exists for ${claim.purpose}; state spring.data.redis.* for Spring Boot's, " +
                    "or declare one named or qualified ${claim.qualifier}",
            )
        } else {
            ConfigurationProblem(
                claim.path,
                ProblemCode.CONTRADICTS,
                "${claim.purpose} has no RedisConnectionFactory named or qualified ${claim.qualifier}, and the application's " +
                    "${names.size} (${names.joinToString(", ")}) name none primary; name or qualify the revocation list's " +
                    "${RedisQualifiers.REVOCATION} and the attempt counters' ${RedisQualifiers.ATTEMPTS}, or mark one primary",
            )
        }
    }
}

/** The qualifiers of a `RedisConnectionFactory` dedicated to one rain-access store. */
public object RedisQualifiers {
    /** The bean name or qualifier of a connection factory dedicated to the revocation list. */
    public const val REVOCATION: String = "rainRevocation"

    /** The bean name or qualifier of a connection factory dedicated to the attempt counters. */
    public const val ATTEMPTS: String = "rainAttempts"
}

/** A store stated as `redis` needs Spring Data Redis on the classpath. */
public class RedisClientCheck(
    private val properties: AccessProperties,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        problems {
            expect(
                properties.revocation.store != RevocationStore.REDIS,
                "${AccessProperties.PREFIX}.revocation.store",
                ProblemCode.CONTRADICTS,
            ) {
                "is redis and org.springframework.boot:spring-boot-data-redis is not on the classpath"
            }
            expect(
                properties.attempts.store != AttemptStore.REDIS,
                "${AccessProperties.PREFIX}.attempts.store",
                ProblemCode.CONTRADICTS,
            ) {
                "is redis and org.springframework.boot:spring-boot-data-redis is not on the classpath"
            }
        }
}
