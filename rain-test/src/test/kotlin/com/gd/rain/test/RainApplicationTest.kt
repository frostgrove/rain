package com.gd.rain.test

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

private val STATED: List<String> =
    listOf("spring.application.name=rain-test-start", "rain.deployment.stage=test", "rain.runtime.roles=api")

/** What a test application reads: the greeting its property file and its arguments state, and the clock it was given. */
class Greeting(
    val text: String,
    val clock: Clock,
)

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@PropertySource("classpath:start/greeting.properties")
class GreetingApplication {
    @Bean
    fun greeting(
        environment: Environment,
        clock: Clock,
    ): Greeting = Greeting(environment.getRequiredProperty("greeting.text"), clock)
}

@Configuration(proxyBeanMethods = false)
class ExitsThree {
    @Bean
    fun exitCode(): ExitCodeGenerator = ExitCodeGenerator { 3 }
}

@RestController
class EchoController {
    @GetMapping("/hello")
    fun hello(): String = "hello"

    @PostMapping("/echo")
    fun echo(
        @RequestHeader(HttpHeaders.CONTENT_TYPE) type: String,
        @RequestBody body: String,
    ): String = "$type $body"
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
class EchoApplication {
    @Bean
    fun echoController(): EchoController = EchoController()
}

/** RainApplication starts a real application from exactly what the test states. */
class RainApplicationTest {
    private val clock = MutableClock(Instant.parse("2026-09-16T08:00:00Z"))

    @Test
    fun `stated properties outrank the application's own property files, and a singleton is a bean before any other exists`() {
        RainApplication
            .start(
                listOf(GreetingApplication::class.java),
                WebApplicationType.NONE,
                STATED + "greeting.text=from the test",
                singletons = mapOf("clock" to clock),
            ).use { application ->
                val greeting = application.bean(Greeting::class)

                assertThat(greeting.text).isEqualTo("from the test")
                assertThat(greeting.clock).isSameAs(clock)
                assertThatThrownBy { application.port }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessage("the application runs no web server, so it listens on no port")
            }
    }

    @Test
    fun `exit closes the context and answers the exit code the application generates`() {
        val application =
            RainApplication.start(
                listOf(GreetingApplication::class.java, ExitsThree::class.java),
                WebApplicationType.NONE,
                STATED,
                singletons = mapOf("clock" to clock),
            )

        assertThat(application.exit()).isEqualTo(3)
        assertThat(application.context.isActive).isFalse()
    }

    @Test
    fun `a property that is not name=value, and a start from no source, are refused before anything starts`() {
        assertThatThrownBy { RainApplication.start(listOf(GreetingApplication::class.java), WebApplicationType.NONE, listOf("stage")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("a property is stated as name=value, got \"stage\"")
        assertThatThrownBy { RainApplication.start(emptyList(), WebApplicationType.NONE, STATED) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("an application is started from at least one source")
    }

    @Test
    fun `a servlet application answers on the port its server published, and a body is sent as JSON unless a type is stated`() {
        RainApplication
            .start(listOf(EchoApplication::class.java), WebApplicationType.SERVLET, STATED + "server.port=0")
            .use { application ->
                val http = application.http

                assertThat(application.port).isPositive()
                assertThat(http.port).isEqualTo(application.port)
                assertThat(http.uri("/hello").toString()).isEqualTo("http://127.0.0.1:${application.port}/hello")
                assertThat(http.get("/hello").body()).isEqualTo("hello")
                assertThat(http.send("POST", "/echo", "{}").body()).isEqualTo("application/json {}")
                assertThat(http.send("POST", "/echo", "plain", "content-type" to "text/plain").body()).isEqualTo("text/plain plain")
                val head = http.send("HEAD", "/hello")
                assertThat(head.statusCode() to head.body()).isEqualTo(200 to "")
            }
    }
}
