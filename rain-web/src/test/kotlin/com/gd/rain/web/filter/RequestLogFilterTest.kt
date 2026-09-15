package com.gd.rain.web.filter

import ch.qos.logback.classic.Level
import com.gd.rain.web.Transcript
import com.gd.rain.web.route.RequestPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** One line per request, through the dispatcher, with the correlation id rule applied. */
class RequestLogFilterTest {
    @Test
    fun `a served request leaves one line in the application log`() {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            serving().perform(get("/things"))

            val line = transcript.only(RequestLogFilter.MESSAGE)
            assertThat(transcript.attributes(line)).containsAllEntriesOf(mapOf("method" to "GET", "path" to "/things", "status" to "200"))
            assertThat(transcript.attributes(line)["request_id"]).isNotBlank()
            assertThat(transcript.attributes(line)["took"]).startsWith("PT")
            assertThat(line.level).isEqualTo(Level.INFO)
        }
    }

    @ParameterizedTest
    @CsvSource("/missing,404,WARN", "/boom,500,ERROR")
    fun `the line reports the status the client actually saw`(
        path: String,
        status: String,
        level: String,
    ) {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            val mvc = serving()
            if (path == "/boom") {
                assertThatThrownBy { mvc.perform(get(path)) }.hasStackTraceContaining("the disk is gone")
            } else {
                mvc.perform(get(path))
            }

            val line = transcript.only(RequestLogFilter.MESSAGE)
            assertThat(transcript.attributes(line)["status"]).isEqualTo(status)
            assertThat(line.level.levelStr).isEqualTo(level)
            assertThat(transcript.attributes(line)["error"]).isNotBlank()
        }
    }

    @Test
    fun `the correlation id the client sent is kept and echoed back`() {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            val answer = serving().perform(get("/things").header(CorrelationId.HEADER, "b7c1e0d2-42:x.y_z")).andReturn()

            assertThat(transcript.attributes(transcript.only(RequestLogFilter.MESSAGE))["request_id"]).isEqualTo("b7c1e0d2-42:x.y_z")
            assertThat(answer.response.getHeader(CorrelationId.HEADER)).isEqualTo("b7c1e0d2-42:x.y_z")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "abc\ninjected level=ERROR",
            "has space",
            "curly{brace}",
            "слово",
            " padded",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        ],
    )
    fun `a correlation id that breaks the rule is replaced by a fresh one`(forged: String) {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            val answer = serving().perform(get("/things").header(CorrelationId.HEADER, forged)).andReturn()

            val correlation = checkNotNull(transcript.attributes(transcript.only(RequestLogFilter.MESSAGE))["request_id"])
            assertThat(UUID.fromString(correlation)).isNotNull()
            assertThat(answer.response.getHeader(CorrelationId.HEADER)).isEqualTo(correlation)
        }
    }

    @Test
    fun `sixty-four characters is still an id, and the sixty-fifth is not`() {
        assertThat(CorrelationId.accepts("a".repeat(64))).isTrue()
        assertThat(CorrelationId.accepts("a".repeat(65))).isFalse()
        assertThat(CorrelationId.accepts("")).isFalse()
    }

    @Test
    fun `the line names the principal the request authenticated as, and an anonymous one names none`() {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            serving { RequestPrincipal { "subject-42" } }.perform(get("/things"))
            serving { RequestPrincipal { null } }.perform(get("/things"))
            serving { null }.perform(get("/things"))

            val principals = transcript.lines.map { transcript.attributes(it)["principal"] }
            assertThat(principals).containsExactly("subject-42", null, null)
        }
    }

    @Test
    fun `neither the query string nor the credentials reach the log`() {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            serving().perform(get("/things?token=s3cret").header("Authorization", "Bearer s3cret").header("Cookie", "session=s3cret"))

            transcript.only(RequestLogFilter.MESSAGE)
            assertThat(transcript.written()).doesNotContain("s3cret")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/live", "/ready"])
    fun `a probe is logged below the noise floor of real traffic`(path: String) {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            serving().perform(get(path))

            assertThat(transcript.only(RequestLogFilter.MESSAGE).level).isEqualTo(Level.DEBUG)
        }
    }

    @Test
    fun `a probe that failed is still loud`() {
        Transcript.of(RequestLogFilter::class).use { transcript ->
            serving(routes = BrokenProbe()).perform(get("/ready"))

            assertThat(transcript.only(RequestLogFilter.MESSAGE).level).isEqualTo(Level.ERROR)
        }
    }

    private fun serving(
        routes: Any = Routes(),
        principal: () -> RequestPrincipal? = { null },
    ): MockMvc {
        val builder = MockMvcBuilders.standaloneSetup(routes)
        builder.addFilters<StandaloneMockMvcBuilder>(RequestLogFilter(setOf("/live", "/ready"), principal))
        return builder.build()
    }

    private fun serving(principal: () -> RequestPrincipal?): MockMvc = serving(Routes(), principal)

    @RestController
    class Routes {
        @GetMapping("/things")
        fun things(): String = "ok"

        @GetMapping("/live", "/ready")
        fun probe(): String = "probe"

        @GetMapping("/missing")
        fun missing(): String = throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing")

        @GetMapping("/boom")
        fun boom(): String = throw IllegalStateException("the disk is gone")
    }

    @RestController
    class BrokenProbe {
        @GetMapping("/ready")
        fun ready(): String = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "the dependency is gone")
    }
}
