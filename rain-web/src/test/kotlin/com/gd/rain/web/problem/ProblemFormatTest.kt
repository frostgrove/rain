package com.gd.rain.web.problem

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.web.Transcript
import com.gd.rain.web.readJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Duration

/** Problem format v1, byte for byte where the bytes are the contract. */
class ProblemFormatTest {
    private val renderer = ProblemRenderer(ErrorCodeRegistrar.register(listOf(RainErrorCodes, RainWebErrorCodes)))

    @Test
    fun `a refusal is written in the declared key order`() {
        val rendered = renderer.render(Fault.notFound())

        assertThat(String(rendered.body)).isEqualTo(
            """{"type":"about:blank","title":"Not Found","status":404,"detail":"not found","code":"not_found"}""",
        )
        assertThat(rendered.status).isEqualTo(404)
        assertThat(rendered.headers).isEmpty()
        assertThat(ProblemFormat.MEDIA_TYPE.toString()).isEqualTo("application/problem+json")
    }

    @Test
    fun `violations are sorted, carry a message, and are cut at one hundred with partial set`() {
        val violations = (149 downTo 0).map { Violation.at(path("items", it, "email"), RainErrorCodes.REQUIRED) }
        val body = readJson(String(renderer.render(Fault.validation(violations)).body))

        assertThat(body["status"].asInt()).isEqualTo(422)
        assertThat(body["title"].asString()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.reasonPhrase)
        assertThat(body["code"].asString()).isEqualTo("validation_failed")
        assertThat(body["errors"].size()).isEqualTo(ProblemFormat.MAX_ERRORS)
        assertThat(body["errors"][0]["pointer"].asString()).isEqualTo("/items/0/email")
        assertThat(body["errors"][99]["pointer"].asString()).isEqualTo("/items/99/email")
        assertThat(body["errors"][0]["code"].asString()).isEqualTo("required")
        assertThat(body["errors"][0]["message"].asString()).isEqualTo(RainErrorCodes.REQUIRED.defaultMessage)
        assertThat(body["partial"].asBoolean()).isTrue()
    }

    @Test
    fun `partial is absent when nothing was cut and the fault was whole, and present when the fault said so`() {
        val whole = readJson(String(renderer.render(Fault.validation(listOf(Violation.general(RainErrorCodes.CHECK, "no")))).body))
        val partial =
            readJson(String(renderer.render(Fault.validation(listOf(Violation.general(RainErrorCodes.CHECK)), partial = true)).body))

        assertThat(whole.has("partial")).isFalse()
        assertThat(whole["errors"][0]["message"].asString()).isEqualTo("no")
        assertThat(whole["errors"][0]["pointer"].asString()).isEmpty()
        assertThat(partial["partial"].asBoolean()).isTrue()
    }

    @Test
    fun `retry-after is sent only when the fault carries it, in whole seconds rounded up`() {
        assertThat(renderer.render(Fault.retryable(retryAfter = Duration.ofMillis(1200))).headers).containsEntry("Retry-After", "2")
        assertThat(renderer.render(Fault.retryable(retryAfter = Duration.ofSeconds(2))).headers).containsEntry("Retry-After", "2")
        assertThat(renderer.render(Fault.retryable()).headers).doesNotContainKey("Retry-After")
    }

    @Test
    fun `an internal fault says only that the request failed, and its cause goes to the log`() {
        Transcript.of(ProblemRenderer::class).use { transcript ->
            val rendered =
                renderer.render(
                    Fault(
                        FaultKind.INTERNAL,
                        message = "the disk at /var/data is full",
                        violations = listOf(Violation.general(RainErrorCodes.CHECK)),
                        cause = IllegalStateException("disk full"),
                    ),
                )

            assertThat(String(rendered.body)).isEqualTo(
                """{"type":"about:blank","title":"Internal Server Error","status":500,"detail":"the request failed","code":"internal"}""",
            )
            assertThat(transcript.only("a request failed").throwableProxy.message).isEqualTo("disk full")
        }
    }

    @Test
    fun `a fault with a code nobody declared renders as internal and names the code in the log`() {
        val stranger = ErrorCode.of("never_declared", "nobody declared this")
        Transcript.of(ProblemRenderer::class).use { transcript ->
            val rendered = renderer.render(Fault(FaultKind.CONFLICT, stranger))
            val line = transcript.only("a fault carries an error code no catalog declares; it is rendered as internal")

            assertThat(rendered.status).isEqualTo(500)
            assertThat(readJson(String(rendered.body))["code"].asString()).isEqualTo("internal")
            assertThat(transcript.attributes(line)["codes"]).isEqualTo("never_declared")
        }
    }

    @Test
    fun `a violation with a code nobody declared renders the whole fault as internal`() {
        val stranger = ErrorCode.of("never_declared", "nobody declared this")
        Transcript.of(ProblemRenderer::class).use {
            val rendered = renderer.render(Fault.validation(listOf(Violation.general(stranger))))

            assertThat(rendered.status).isEqualTo(500)
            assertThat(readJson(String(rendered.body)).has("errors")).isFalse()
        }
    }
}
