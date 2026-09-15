package com.gd.rain.access.internal

import com.gd.rain.access.AccessProperties
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
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer
import io.github.resilience4j.common.bulkhead.configuration.CommonBulkheadConfigurationProperties
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata
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
 * What stands in for a Redis-backed store when a store is stated as `redis` and no Redis client is on the classpath.
 * [RedisClientCheck] refuses that start before anything is served, so none of these is ever called.
 */
public object UnavailableRedisStores : RevocationList, AttemptLimiter {
    override fun verdict(
        session: UUID,
        subject: SubjectRef,
        sessionIssuedAt: Instant,
    ): RevocationVerdict = unavailable()

    override fun announceSessions(sessions: List<RevokedSession>): Unit = unavailable()

    override fun announceCutoff(cutoff: SubjectCutoff): Unit = unavailable()

    override fun admit(attempt: Attempt): Admission = unavailable()

    override fun recordFailure(attempt: Attempt): FailureRecorded = unavailable()

    override fun recordSuccess(attempt: Attempt): Unit = unavailable()

    private fun unavailable(): Nothing = error("a store is stated as redis and no Redis client is on the classpath; start-up refuses that")
}

/** A store stated as `redis` needs Spring Data Redis on the classpath. */
public class RedisClientCheck(
    private val properties: AccessProperties,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        problems {
            expect(
                properties.revocation.store != com.gd.rain.access.RevocationStore.REDIS,
                "${AccessProperties.PREFIX}.revocation.store",
                ProblemCode.CONTRADICTS,
            ) {
                "is redis and org.springframework.boot:spring-boot-data-redis is not on the classpath"
            }
            expect(
                properties.attempts.store != com.gd.rain.access.AttemptStore.REDIS,
                "${AccessProperties.PREFIX}.attempts.store",
                ProblemCode.CONTRADICTS,
            ) {
                "is redis and org.springframework.boot:spring-boot-data-redis is not on the classpath"
            }
        }
}
