package com.gd.rain.web.config

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.web.get
import com.gd.rain.web.port
import com.gd.rain.web.startServer
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Gap 33: the client address is attributed to a forwarding header only when the deployment says a
 * trusted proxy writes it, and the server's forwarding strategy has to agree with that statement.
 */
class UntrustedForwardedHeaderIgnoredTest {
    @RestController
    class Address {
        @GetMapping("/address")
        fun address(request: HttpServletRequest): String = request.remoteAddr
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application {
        @Bean
        fun address(): Address = Address()
    }

    @Test
    fun `a direct deployment attributes a request to its peer, whatever X-Forwarded-For says`() {
        startServer(Application::class).use { context ->
            val answer = get(context.port(), "/address", "X-Forwarded-For" to "203.0.113.7")

            assertThat(answer.statusCode()).isEqualTo(200)
            assertThat(answer.body()).isEqualTo("127.0.0.1")
        }
    }

    @Test
    fun `a direct deployment whose server honours forwarding headers does not start`() {
        assertThatThrownBy { startServer(Application::class, "server.forward-headers-strategy=native") }
            .satisfies({ failure ->
                val refusal = generateSequence(failure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().first()
                assertThat(
                    refusal.problems.map { it.path to it.code },
                ).containsExactly("rain.web.client-address" to ProblemCode.CONTRADICTS)
            })
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource(
        "DIRECT,    none,      ",
        "DIRECT,    NONE,      ",
        "DIRECT,    native,    CONTRADICTS",
        "DIRECT,    framework, CONTRADICTS",
        "DIRECT,    ,          CONTRADICTS",
        "FORWARDED, native,    ",
        "FORWARDED, framework, ",
        "FORWARDED, none,      CONTRADICTS",
        "FORWARDED, ,          CONTRADICTS",
        "FORWARDED, sometimes, INVALID",
    )
    fun `the client address mode and the forwarding strategy agree`(
        mode: ClientAddressMode,
        strategy: String?,
        expected: ProblemCode?,
    ) {
        val environment = StandardEnvironment()
        if (strategy !=
            null
        ) {
            environment.propertySources.addFirst(MapPropertySource("test", mapOf(ForwardHeadersCheck.STRATEGY to strategy)))
        }

        val problems = ForwardHeadersCheck(mode, environment).problems()

        assertThat(problems.map { it.code }).isEqualTo(listOfNotNull(expected))
    }
}
