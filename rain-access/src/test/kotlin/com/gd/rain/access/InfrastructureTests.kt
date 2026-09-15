package com.gd.rain.access

import com.gd.rain.access.internal.HashingBulkheadCheck
import com.gd.rain.access.internal.UserDetailsServiceExclusionFilter
import com.gd.rain.access.internal.revocation.EvictionPolicyCheck
import com.gd.rain.core.config.ProblemCode
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer
import io.github.resilience4j.common.bulkhead.configuration.CommonBulkheadConfigurationProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisServerCommands
import org.springframework.test.util.ReflectionTestUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties

class HashingBulkheadCheckTest {
    private val path = "resilience4j.bulkhead.instances.rain-access-hashing"

    private fun properties(
        configure: CommonBulkheadConfigurationProperties.InstanceProperties.() -> Unit = {
        },
    ): CommonBulkheadConfigurationProperties =
        CommonBulkheadConfigurationProperties().apply {
            instances["rain-access-hashing"] = CommonBulkheadConfigurationProperties.InstanceProperties().apply(configure)
        }

    @Test
    fun `an instance stating its calls and wait has no problem`() {
        val stated =
            properties {
                setMaxConcurrentCalls(4)
                setMaxWaitDuration(Duration.ofSeconds(1))
            }

        assertThat(HashingBulkheadCheck(stated, emptyList()).problems()).isEmpty()
    }

    @Test
    fun `no auto-configuration, no instance, or an instance relying on defaults is refused`() {
        assertThat(
            HashingBulkheadCheck(null, emptyList()).problems(),
        ).singleElement().matches({ it.code == ProblemCode.REQUIRED }, "required")
        assertThat(HashingBulkheadCheck(CommonBulkheadConfigurationProperties(), emptyList()).problems()).singleElement().matches({
            it.path ==
                path
        }, "instance")
        assertThat(HashingBulkheadCheck(properties(), emptyList()).problems().map { it.path })
            .containsExactlyInAnyOrder("$path.max-concurrent-calls", "$path.max-wait-duration")
    }

    @Test
    fun `invalid numbers and a customizer naming the instance are refused`() {
        // Resilience4j's setters refuse these values at binding; the check still refuses an instance built around them.
        val invalid =
            properties {
                ReflectionTestUtils.setField(this, "maxConcurrentCalls", 0)
                ReflectionTestUtils.setField(this, "maxWaitDuration", Duration.ofSeconds(-1))
            }
        val customizer = BulkheadConfigCustomizer.of("rain-access-hashing") { it.maxConcurrentCalls(99) }

        assertThat(HashingBulkheadCheck(invalid, listOf(customizer)).problems().map { it.path to it.code }).containsExactlyInAnyOrder(
            "$path.max-concurrent-calls" to ProblemCode.INVALID,
            "$path.max-wait-duration" to ProblemCode.INVALID,
            path to ProblemCode.CONTRADICTS,
        )
    }
}

class EvictionPolicyCheckTest {
    private val factory = mockk<RedisConnectionFactory>()
    private val connection = mockk<RedisConnection>(relaxed = true)
    private val server = mockk<RedisServerCommands>()

    init {
        every { factory.connection } returns connection
        every { connection.serverCommands() } returns server
    }

    private fun check(attested: EvictionPolicyAttestation? = null) =
        EvictionPolicyCheck(
            "the revocation list",
            "rain.access.revocation.redis",
            "rain.access.revocation.redis.eviction-policy-attested",
            attested,
        ) {
            factory
        }.problems()

    private fun answering(policy: String?) {
        every { server.getConfig("maxmemory-policy") } returns Properties().apply { policy?.let { setProperty("maxmemory-policy", it) } }
    }

    @Test
    fun `noeviction passes and any other policy is refused naming it`() {
        answering("noeviction")
        assertThat(check()).isEmpty()

        answering("allkeys-lru")
        assertThat(
            check(),
        ).singleElement().matches({ it.code == ProblemCode.INVALID && it.message.contains("allkeys-lru") }, "names the policy")
    }

    @Test
    fun `a server that will not say is refused unless the deployment attested noeviction, which is then not evaluated`() {
        every { server.getConfig("maxmemory-policy") } throws RedisConnectionFailureException("ERR unknown command 'CONFIG'")
        every { connection.ping() } returns "PONG"

        assertThat(check()).singleElement().matches({
            it.code == ProblemCode.REQUIRED && it.path.endsWith("eviction-policy-attested")
        }, "required attestation")
        assertThat(
            check(EvictionPolicyAttestation.NOEVICTION),
        ).singleElement().matches({ it.code == ProblemCode.NOT_EVALUATED }, "not evaluated")

        answering(null)
        assertThat(check()).singleElement().matches({ it.code == ProblemCode.REQUIRED }, "an empty answer is no answer")
    }

    @Test
    fun `a server that answers nothing at all is unreachable, attested or not`() {
        every { server.getConfig("maxmemory-policy") } throws RedisConnectionFailureException("connection reset")
        every { connection.ping() } throws RedisConnectionFailureException("connection reset")
        assertThat(check(EvictionPolicyAttestation.NOEVICTION)).singleElement().matches({
            it.code == ProblemCode.INVALID &&
                it.message.contains("not reachable")
        }, "unreachable")

        every { factory.connection } throws RedisConnectionFailureException("refused")
        assertThat(
            check(EvictionPolicyAttestation.NOEVICTION),
        ).singleElement().matches({ it.message.contains("not reachable") }, "unreachable")
    }
}

class UserDetailsServiceExclusionFilterTest {
    @Test
    fun `only Spring Boot's in-memory user store is filtered, and the filter is registered`() {
        val candidates =
            arrayOf(
                UserDetailsServiceExclusionFilter.EXCLUDED,
                "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
                "com.gd.rain.access.autoconfigure.RainAccessAutoConfiguration",
            )

        assertThat(UserDetailsServiceExclusionFilter().match(candidates, mockk(relaxed = true)).toList()).containsExactly(false, true, true)
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(AutoConfigurationImportFilter::class.java))
            .anyMatch { it is UserDetailsServiceExclusionFilter }
    }
}

/** Gap 23: time comes from the injected clock everywhere; no main source reads the system clock itself. */
class InjectedClockEverywhereTest {
    @Test
    fun `no main source reads the system clock`() {
        val forbidden =
            Regex("""\b(Instant|OffsetDateTime|LocalDateTime|ZonedDateTime|LocalDate)\.now\(|Clock\.system|System\.currentTimeMillis\(""")
        val offenders =
            Files.walk(Path.of("src/main/kotlin")).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") }
                    .toList()
                    .flatMap { file ->
                        Files
                            .readAllLines(
                                file,
                            ).withIndex()
                            .filter { forbidden.containsMatchIn(it.value) }
                            .map { "${file.fileName}:${it.index + 1}" }
                    }
            }

        assertThat(offenders).isEmpty()
    }
}
