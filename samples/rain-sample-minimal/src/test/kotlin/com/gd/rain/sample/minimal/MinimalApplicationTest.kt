package com.gd.rain.sample.minimal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

/** A real start on a random port, exactly as `main` starts it, with only the stage and roles stated per process. */
class MinimalApplicationTest {
    companion object {
        private lateinit var context: ConfigurableApplicationContext
        private lateinit var base: URI

        @JvmStatic
        @BeforeAll
        fun start() {
            context =
                SpringApplicationBuilder(MinimalApplication::class.java)
                    .properties("server.port=0", "rain.deployment.stage=test", "rain.runtime.roles=api")
                    .run()
            base = URI.create("http://127.0.0.1:${checkNotNull((context as WebServerApplicationContext).webServer).port}")
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            context.close()
        }
    }

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    private fun post(
        path: String,
        body: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun HttpResponse<String>.json(): JsonNode = json.readTree(body())

    @Test
    fun `a greeting is answered, with the security headers every response carries`() {
        val response = post("/v1/greetings", """{"name":"Ada"}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json()["text"].asString()).isEqualTo("hello, Ada")
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
    }

    @Test
    fun `a missing name is a validation problem pointing at the field`() {
        val response = post("/v1/greetings", "{}")

        assertThat(response.statusCode()).isEqualTo(422)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        val problem = response.json()
        assertThat(problem["code"].asString()).isEqualTo("validation_failed")
        assertThat(problem["errors"][0]["pointer"].asString()).isEqualTo("/name")
        assertThat(problem["errors"][0]["code"].asString()).isEqualTo("required")
    }

    @Test
    fun `a reserved name is refused with the application's own registered code`() {
        val response = post("/v1/greetings", """{"name":"root"}""")

        assertThat(response.statusCode()).isEqualTo(409)
        assertThat(response.json()["code"].asString()).isEqualTo("name_reserved")
    }

    @Test
    fun `a body over the limit is refused before the controller reads it`() {
        val response = post("/v1/greetings", """{"name":"${"a".repeat(17 * 1024)}"}""")

        assertThat(response.statusCode()).isEqualTo(413)
        assertThat(response.json()["code"].asString()).isEqualTo("too_large")
    }

    @Test
    fun `a path nobody serves is a not-found problem`() {
        val response = get("/v1/nothing-here")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asString()).isEqualTo("not_found")
    }

    @Test
    fun `readiness is ready with no checks, and the application has no persistence at all`() {
        val ready = get("/ready")

        assertThat(ready.statusCode()).isEqualTo(200)
        assertThat(ready.body()).isEqualTo("""{"status":"ready","failing":[]}""")
        assertThat(context.getBeanNamesForType(DataSource::class.java)).isEmpty()
        assertThatThrownBy { Class.forName("com.gd.rain.persistence.sql.SqlStates") }.isInstanceOf(ClassNotFoundException::class.java)
    }
}
