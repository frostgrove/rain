package com.gd.rain.web.config

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

private val VALID =
    mapOf(
        "rain.web.body-limit" to "1MB",
        "rain.web.request-budget" to "30s",
        "rain.web.client-address" to "direct",
    )

/** Problems the validator reports under `rain.web` and `server.`, with the valid section overridden by [overrides]. */
fun webProblems(vararg overrides: Pair<String, String>): List<ConfigurationProblem> = problemsFor(VALID + overrides)

fun problemsFor(web: Map<String, String>): List<ConfigurationProblem> {
    val environment = StandardEnvironment()
    val values = mapOf("spring.application.name" to "sample", "rain.runtime.roles" to "api", "rain.deployment.stage" to "test") + web
    environment.propertySources.addFirst(MapPropertySource("test", values))
    return RainConfigurationValidator
        .validate(environment, listOf(RainWebConfigurationContributor()), emptyList())
        .problems
        .filter { it.path.startsWith("rain.web") }
}

/** `rain.web`: required leaves, declared defaults, and every rule about a stated value. */
class RainWebPropertiesValidationTest {
    @Test
    fun `a valid section reports nothing and binds the declared defaults`() {
        assertThat(webProblems()).isEmpty()
        val defaults =
            RainWebProperties(
                org.springframework.util.unit.DataSize
                    .ofMegabytes(1),
                java.time.Duration.ofSeconds(30),
                ClientAddressMode.DIRECT,
            )
        assertThat(defaults.probes.livePath).isEqualTo("/live")
        assertThat(defaults.probes.readyPath).isEqualTo("/ready")
        assertThat(defaults.securityHeaders.keys).containsExactly(
            "Content-Security-Policy",
            "X-Frame-Options",
            "X-Content-Type-Options",
            "Referrer-Policy",
            "Strict-Transport-Security",
        )
        assertThat(defaults.cors.allowedOrigins).isEmpty()
    }

    @Test
    fun `an absent section is required`() {
        assertThat(problemsFor(emptyMap()).map { it.path to it.code }).containsExactly("rain.web" to ProblemCode.REQUIRED)
    }

    @Test
    fun `a section missing its leaves names every one of them`() {
        assertThat(problemsFor(mapOf("rain.web.cors.allowed-origins" to "https://app.example")).map { it.path to it.code })
            .containsExactlyInAnyOrder(
                "rain.web.body-limit" to ProblemCode.REQUIRED,
                "rain.web.request-budget" to ProblemCode.REQUIRED,
                "rain.web.client-address" to ProblemCode.REQUIRED,
            )
    }

    @Test
    fun `a client address outside the enumeration and a budget that is not positive are refused`() {
        assertThat(webProblems("rain.web.client-address" to "proxy").map { it.path to it.code })
            .containsExactly("rain.web.client-address" to ProblemCode.INVALID)
        assertThat(webProblems("rain.web.request-budget" to "0s").map { it.path to it.code })
            .containsExactly("rain.web.request-budget" to ProblemCode.INVALID)
    }

    @Test
    fun `cross-origin rules are refused together`() {
        val problems =
            webProblems(
                "rain.web.cors.allowed-origins[0]" to "https://app.example/path",
                "rain.web.cors.allowed-origins[1]" to "*",
                "rain.web.cors.allow-credentials" to "true",
                "rain.web.cors.allowed-headers[0]" to "Bad Header",
                "rain.web.cors.max-age" to "-1s",
            )

        assertThat(problems.map { it.path to it.code }).containsExactly(
            "rain.web.cors.allowed-origins[0]" to ProblemCode.INVALID,
            "rain.web.cors.allow-credentials" to ProblemCode.CONTRADICTS,
            "rain.web.cors.allowed-methods" to ProblemCode.REQUIRED,
            "rain.web.cors.allowed-headers[0]" to ProblemCode.INVALID,
            "rain.web.cors.max-age" to ProblemCode.INVALID,
        )
    }

    @Test
    fun `security headers must be header names with single-line values`() {
        val problems =
            webProblems(
                "rain.web.security-headers[Bad Header]" to "x",
                "rain.web.security-headers.x-frame-options" to "DENY\r\nSet-Cookie: stolen=1",
            )

        assertThat(problems.map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.web.security-headers.Bad Header" to ProblemCode.INVALID,
            "rain.web.security-headers.x-frame-options" to ProblemCode.INVALID,
        )
    }

    @Test
    fun `probe paths are absolute literal paths and differ`() {
        assertThat(webProblems("rain.web.probes.live-path" to "live", "rain.web.probes.ready-path" to "/{x}").map { it.path to it.code })
            .containsExactly("rain.web.probes.live-path" to ProblemCode.INVALID, "rain.web.probes.ready-path" to ProblemCode.INVALID)
        assertThat(
            webProblems("rain.web.probes.live-path" to "/health", "rain.web.probes.ready-path" to "/health").map { it.path to it.code },
        ).containsExactly("rain.web.probes.ready-path" to ProblemCode.CONTRADICTS)
    }

    @Test
    fun `a key nothing declares is reported`() {
        assertThat(webProblems("rain.web.body-limt" to "1MB").map { it.path to it.code })
            .containsExactly("rain.web.body-limt" to ProblemCode.UNKNOWN_KEY)
    }
}
