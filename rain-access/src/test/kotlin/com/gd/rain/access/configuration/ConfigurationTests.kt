package com.gd.rain.access.configuration

import com.gd.rain.access.AccessConfigurationContributor
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.internal.token.SigningKey
import com.gd.rain.access.internal.token.SigningKeyResolution
import com.gd.rain.access.support.KEY
import com.gd.rain.access.support.sampleAccessProperties
import com.gd.rain.access.support.sampleWebProperties
import com.gd.rain.boot.config.BoundSections
import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.boot.config.RuleOutcome
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.web.config.RainWebProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private val VALID: Map<String, String> =
    linkedMapOf(
        "spring.application.name" to "access-config",
        "rain.deployment.stage" to "test",
        "rain.runtime.roles" to "api",
        "rain.access.web.base-path" to "/api",
        "rain.access.web.delivery" to "cookies",
        "rain.access.token.issuer" to "rain-test",
        "rain.access.token.audience" to "rain-test-api",
        "rain.access.token.signing-key" to "base64:" + Base64.getEncoder().encodeToString(KEY),
        "rain.access.token.access-ttl" to "5m",
        "rain.access.session.ttl" to "30d",
        "rain.access.session.idle-ttl" to "7d",
        "rain.access.session.retention.keep-for" to "7d",
        "rain.access.session.retention.interval" to "1h",
        "rain.access.password.revoke-other-sessions-on-change" to "true",
        "rain.access.hashing.queue" to "8",
        "rain.access.attempts.store" to "redis",
        "rain.access.attempts.redis.key-prefix" to "app:attempts:",
        "rain.access.revocation.store" to "redis",
        "rain.access.revocation.redis.key-prefix" to "app:revoked:",
        "rain.access.revocation.redis.replay.interval" to "1m",
        "rain.access.gate.throttle.per-minute" to "120",
        "rain.access.gate.throttle.burst" to "60",
        "rain.access.gate.throttle.callers" to "20000",
        "rain.access.grants.max-roles-per-subject" to "32",
    )

private fun problemsOf(
    changes: Map<String, String?> = emptyMap(),
    contributors: List<com.gd.rain.boot.config.ConfigurationContributor> = listOf(AccessConfigurationContributor()),
): List<ConfigurationProblem> {
    val values = LinkedHashMap<String, Any>(VALID)
    changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
    val environment = StandardEnvironment()
    environment.propertySources.addFirst(MapPropertySource("test", values))
    return RainConfigurationValidator.validate(environment, contributors, emptyList()).problems
}

private fun List<ConfigurationProblem>.at(path: String): List<ConfigurationProblem> = filter { it.path == path }

/** Gap 20: every count is at least one and every duration positive, stated once in the properties; nothing is repaired. */
class AccessPropertiesRefusesZeroTest {
    @Test
    fun `a complete configuration has no problem`() {
        // Loopback origins in prod are rain-web's rule (ProdOriginRulesTest), whatever the credential delivery.
        assertThat(problemsOf()).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "rain.access.token.access-ttl=0s",
            "rain.access.session.ttl=0s",
            "rain.access.session.idle-ttl=-1s",
            "rain.access.session.refresh-grace=0s",
            "rain.access.session.rotation-attempts=0",
            "rain.access.session.revoke-batch=0",
            "rain.access.session.retention.keep-for=0s",
            "rain.access.session.retention.batch=-5",
            "rain.access.password.min-length=0",
            "rain.access.password.max-bytes=0",
            "rain.access.hashing.queue=0",
            "rain.access.hashing.argon2.memory-kib=0",
            "rain.access.attempts.per-identifier=0",
            "rain.access.attempts.window=0s",
            "rain.access.revocation.redis.replay.page-size=0",
            "rain.access.gate.throttle.per-minute=0",
            "rain.access.gate.throttle.callers=-1",
            "rain.access.grants.max-roles-per-subject=0",
            "rain.access.web.page.default-size=0",
            "rain.access.web.max-bulk-ids=0",
            "rain.access.catalogue.chunk-size=0",
        ],
    )
    fun `a value that is not positive is refused at its own path`(written: String) {
        val (path, value) = written.split('=', limit = 2)

        val problems = problemsOf(mapOf(path to value))

        assertThat(problems.at(path)).describedAs(problems.toString()).anyMatch { it.code == ProblemCode.INVALID }
    }

    @Test
    fun `every behaviour-deciding choice is required and reported together`() {
        val required =
            listOf(
                "rain.access.web.base-path",
                "rain.access.web.delivery",
                "rain.access.token.issuer",
                "rain.access.token.signing-key",
                "rain.access.session.idle-ttl",
                "rain.access.password.revoke-other-sessions-on-change",
                "rain.access.hashing.queue",
                "rain.access.gate.throttle.burst",
                "rain.access.grants.max-roles-per-subject",
            )

        val problems = problemsOf(required.associateWith { null })

        assertThat(problems.filter { it.code == ProblemCode.REQUIRED }.map { it.path }).containsAll(required)
    }

    @Test
    fun `the replay interval is shorter than the access token lifetime`() {
        val problems = problemsOf(mapOf("rain.access.revocation.redis.replay.interval" to "5m"))

        assertThat(problems.at("rain.access.revocation.redis.replay.interval"))
            .singleElement()
            .matches({ it.code == ProblemCode.CONTRADICTS }, "contradicts")
    }

    @Test
    fun `lifetimes nest - idle within the session, the token within idle, the grace within idle`() {
        assertThat(problemsOf(mapOf("rain.access.session.idle-ttl" to "60d")).at("rain.access.session.idle-ttl")).isNotEmpty()
        assertThat(problemsOf(mapOf("rain.access.token.access-ttl" to "8d")).at("rain.access.token.access-ttl")).isNotEmpty()
        assertThat(problemsOf(mapOf("rain.access.session.refresh-grace" to "7d")).at("rain.access.session.refresh-grace")).isNotEmpty()
    }

    @Test
    fun `a store stated as redis without its key prefix is refused, and a section for the other store contradicts`() {
        val problems =
            problemsOf(
                mapOf("rain.access.attempts.redis.key-prefix" to null, "rain.access.attempts.memory.maximum-keys" to "10"),
            )

        assertThat(problems.at("rain.access.attempts.redis.key-prefix"))
            .singleElement()
            .matches({ it.code == ProblemCode.REQUIRED }, "required")
        assertThat(problems.at("rain.access.attempts.memory"))
            .singleElement()
            .matches({ it.code == ProblemCode.CONTRADICTS }, "contradicts")
    }

    @Test
    fun `an unknown enum value is a binding problem, not a default`() {
        val problems = problemsOf(mapOf("rain.access.revocation.redis.eviction-policy-attested" to "allkeys-lru"))

        assertThat(problems)
            .anyMatch { it.path.startsWith("rain.access.revocation.redis.eviction-policy-attested") && it.code == ProblemCode.INVALID }
    }

    @Test
    fun `a base path with a trailing slash or none at all is refused`() {
        assertThat(problemsOf(mapOf("rain.access.web.base-path" to "/api/")).at("rain.access.web.base-path")).isNotEmpty()
        assertThat(problemsOf(mapOf("rain.access.web.base-path" to "/")).at("rain.access.web.base-path")).isNotEmpty()
    }
}

/** Gap 18: counters in one process are not shared by replicas, so production refuses them. */
class MemoryLimiterRefusedInProdTest {
    @Test
    fun `a memory attempt store is refused in the prod stage and allowed elsewhere`() {
        val memory =
            mapOf(
                "rain.access.attempts.store" to "memory",
                "rain.access.attempts.redis.key-prefix" to null,
                "rain.access.attempts.memory.maximum-keys" to "1000",
            )

        val prod =
            problemsOf(
                memory +
                    mapOf(
                        "rain.deployment.stage" to "prod",
                        "rain.access.token.signing-key" to VALID.getValue("rain.access.token.signing-key"),
                    ),
            )
        val dev = problemsOf(memory + mapOf("rain.deployment.stage" to "dev"))

        assertThat(prod.at("rain.access.attempts.store"))
            .singleElement()
            .matches({ it.code == ProblemCode.INVALID && it.message.contains("replica") }, "names replicas")
        assertThat(dev.at("rain.access.attempts.store")).isEmpty()
    }

    @Test
    fun `a memory store states its table bound`() {
        val problems = problemsOf(mapOf("rain.access.attempts.store" to "memory", "rain.access.attempts.redis.key-prefix" to null))

        assertThat(problems.at("rain.access.attempts.memory.maximum-keys"))
            .singleElement()
            .matches({ it.code == ProblemCode.REQUIRED }, "required")
    }
}

/** Gap 21: a signing key is at least 32 decoded bytes in strict RFC 4648 base64; nothing judges how random it looks. */
class SigningKeyStrictBase64Test {
    private val canonical: String = Base64.getEncoder().encodeToString(KEY)

    @Test
    fun `canonical padded base64 of 32 bytes resolves to those bytes`() {
        val resolved = SigningKey.resolve("base64:$canonical", DeploymentStage.PROD)

        assertThat(resolved).isInstanceOf(SigningKeyResolution.Resolved::class.java)
        assertThat((resolved as SigningKeyResolution.Resolved).material).isEqualTo(KEY)
    }

    @Test
    fun `a key that repeats one byte is a key - length is the rule, randomness is not judged`() {
        val repeated = Base64.getEncoder().encodeToString(ByteArray(32) { 0x61 })

        assertThat(SigningKey.resolve("base64:$repeated", DeploymentStage.PROD)).isInstanceOf(SigningKeyResolution.Resolved::class.java)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["unpadded", "url-safe", "whitespace", "non-canonical", "empty"])
    fun `anything but strict canonical base64 is refused`(case: String) {
        val written =
            when (case) {
                "unpadded" -> canonical.trimEnd('=')
                "url-safe" -> Base64.getUrlEncoder().encodeToString(ByteArray(32) { 0xFB.toByte() })
                "whitespace" -> canonical.substring(0, 20) + " " + canonical.substring(20)
                "non-canonical" -> nonCanonical(canonical)
                else -> ""
            }

        val resolved = SigningKey.resolve("base64:$written", DeploymentStage.DEV)

        assertThat(resolved).isInstanceOf(SigningKeyResolution.Refused::class.java)
        assertThat((resolved as SigningKeyResolution.Refused).problem).contains("strict RFC 4648").doesNotContain(written.ifEmpty { " " })
    }

    @Test
    fun `fewer than 32 decoded bytes are refused and measured, never quoted`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(31) { 1 })

        val resolved = SigningKey.resolve("base64:$short", DeploymentStage.DEV) as SigningKeyResolution.Refused

        assertThat(resolved.problem).contains("31 bytes").doesNotContain(short)
    }

    @Test
    fun `a raw literal is refused in prod and taken as its bytes elsewhere`() {
        val raw = "0123456789abcdef0123456789abcdef"

        val prod = SigningKey.resolve(raw, DeploymentStage.PROD)
        val dev = SigningKey.resolve(raw, DeploymentStage.DEV)

        assertThat((prod as SigningKeyResolution.Refused).problem).contains("raw literal").doesNotContain(raw)
        assertThat((dev as SigningKeyResolution.Resolved).material).isEqualTo(raw.toByteArray())
    }

    @Test
    fun `a key file holds strict base64 and at most one line feed`(
        @TempDir directory: Path,
    ) {
        val withLineFeed = Files.writeString(directory.resolve("lf.key"), "$canonical\n")
        val withCarriageReturn = Files.writeString(directory.resolve("crlf.key"), "$canonical\r\n")

        assertThat(SigningKey.resolve("file:$withLineFeed", DeploymentStage.PROD)).isInstanceOf(SigningKeyResolution.Resolved::class.java)
        assertThat(SigningKey.resolve("file:$withCarriageReturn", DeploymentStage.PROD))
            .isInstanceOf(SigningKeyResolution.Refused::class.java)
    }

    @Test
    fun `a key file named relatively, or not there, is refused`() {
        assertThat((SigningKey.resolve("file:keys/signing.key", DeploymentStage.DEV) as SigningKeyResolution.Refused).problem)
            .contains("relative")
        assertThat((SigningKey.resolve("file:/nonexistent/rain/signing.key", DeploymentStage.DEV) as SigningKeyResolution.Refused).problem)
            .contains("cannot be read")
            .doesNotContain("nonexistent")
    }

    /** The last data character with its unused low bits set: Java's decoder reads the same bytes, the canonical form does not. */
    private fun nonCanonical(text: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val position = text.indexOf('=') - 1
        val bumped = alphabet[alphabet.indexOf(text[position]) + 1]
        return text.substring(0, position) + bumped + text.substring(position + 1)
    }
}
