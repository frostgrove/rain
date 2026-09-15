package com.gd.rain.web.config

import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * An allowed origin is not a note about CORS: the cross-site guard lets an unsafe request through when its `Origin` is one
 * of these, so a page served from an allowed origin drives the API as whoever is signed in. A production deployment therefore
 * names https origins of its own, never `*`, and never this machine.
 */
class ProdOriginRulesTest {
    private fun web(vararg origins: String) =
        RainWebProperties(
            DataSize.ofMegabytes(1),
            Duration.ofSeconds(30),
            ClientAddressMode.DIRECT,
            cors = RainWebProperties.Cors(allowedOrigins = origins.toList(), allowedMethods = listOf("GET", "POST")),
        )

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "https://localhost:3300",
            "https://127.0.0.1:3000",
            "https://[::1]:3000",
            "https://app.localhost",
            "*",
            "http://app.example.com",
        ],
    )
    fun `an origin a production deployment cannot have is refused, naming the entry`(origin: String) {
        val problems = web("https://app.example.com", origin).problems(DeploymentStage.PROD)

        assertThat(problems.map { it.path to it.code }).containsExactly("rain.web.cors.allowed-origins[1]" to ProblemCode.INVALID)
        assertThat(problems.single().message).contains("prod deployment")
    }

    @Test
    fun `the https origins a production deployment names itself are accepted`() {
        assertThat(web("https://app.example.com", "https://admin.example.com:8443").problems(DeploymentStage.PROD)).isEmpty()
    }

    @Test
    fun `the rule is production's alone - a workstation may name its own browser`() {
        listOf(DeploymentStage.DEV, DeploymentStage.TEST).forEach { stage ->
            assertThat(web("http://localhost:3300", "http://127.0.0.1:3300").problems(stage)).describedAs(stage.wire).isEmpty()
        }
    }
}
