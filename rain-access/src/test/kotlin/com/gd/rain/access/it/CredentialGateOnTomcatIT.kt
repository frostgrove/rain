package com.gd.rain.access.it

import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.accessProperties
import com.gd.rain.test.ApplicationHttp
import com.gd.rain.test.RainApplication
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper

/**
 * The credential gate on the container rain deploys on. A mock request is told its path; Tomcat hands the request URI
 * back undecoded while handler mapping matches the decoded path, so a gate reading the raw URI passes every mock test
 * and still lets `POST /api/%61uth/agent/login` reach the login handler. Over a real socket, with a client that sends the
 * request target verbatim.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialGateOnTomcatIT {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var http: ApplicationHttp
    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun start() {
        val database = RainPostgres.freshDatabase("access_gate_tomcat")
        val application =
            RainApplication.start(
                listOf(AccessApplication::class.java),
                WebApplicationType.SERVLET,
                accessProperties("server.port=0").toList() + database.springProperties(),
            )
        context = application.context
        http = application.http
    }

    @AfterAll
    fun stop() {
        if (::context.isInitialized) context.close()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/api/auth/agent/login", "/api/%61uth/agent/login", "/api/auth/agent/%6Cogin", "/api/%61uth/agent/%6Cogin"])
    fun `a form reaches no credential however the path is spelled`(path: String) {
        val answer = http.send("POST", path, "identifier=a&password=x", "Content-Type" to "application/x-www-form-urlencoded")

        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(415)
        assertThat(json.readTree(answer.body())["code"].asString()).isEqualTo("unsupported_media_type")
    }

    @Test
    fun `a cross-site write is refused however the path is spelled`() {
        val answer =
            http.send("POST", "/api/%61uth/agent/login", """{"identifier":"a","password":"x"}""", "Sec-Fetch-Site" to "cross-site")

        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(403)
        assertThat(json.readTree(answer.body())["code"].asString()).isEqualTo("cross_site")
    }

    @Test
    fun `outside the credential surface a form is not the gate's to refuse, however the path is spelled`() {
        val answer =
            http.send("POST", "/%74ickets", "a=b", "Content-Type" to "application/x-www-form-urlencoded", "Sec-Fetch-Site" to "same-origin")

        assertThat(answer.statusCode()).describedAs(answer.body()).isNotIn(415, 429)
    }
}
