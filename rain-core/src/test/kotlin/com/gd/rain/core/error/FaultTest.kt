package com.gd.rain.core.error

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration

class FaultTest {
    @ParameterizedTest(name = "{0} renders {1} with {2}")
    @CsvSource(
        "INTERNAL,               500, internal",
        "BAD_REQUEST,            400, bad_request",
        "UNAUTHORIZED,           401, unauthenticated",
        "FORBIDDEN,              403, forbidden",
        "NOT_FOUND,              404, not_found",
        "METHOD_NOT_ALLOWED,     405, method_not_allowed",
        "NOT_ACCEPTABLE,         406, not_acceptable",
        "CONFLICT,               409, conflict",
        "TOO_LARGE,              413, too_large",
        "UNSUPPORTED_MEDIA_TYPE, 415, unsupported_media_type",
        "VALIDATION,             422, validation_failed",
        "TOO_MANY_REQUESTS,      429, too_many_requests",
        "RETRYABLE,              503, unavailable",
    )
    fun `every kind has one status and one default code`(
        kind: FaultKind,
        status: Int,
        code: String,
    ) {
        assertThat(kind.status).isEqualTo(status)
        assertThat(kind.defaultCode.value).isEqualTo(code)
    }

    @ParameterizedTest
    @EnumSource(FaultKind::class)
    fun `every default code is declared by rain-core`(kind: FaultKind) {
        assertThat(RainErrorCodes.codes).contains(kind.defaultCode)
    }

    @Test
    fun `the statuses of the kinds are distinct`() {
        assertThat(FaultKind.entries.map { it.status }).doesNotHaveDuplicates()
    }

    @Test
    fun `a validation fault without violations is refused`() {
        assertThatThrownBy { Fault(FaultKind.VALIDATION) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one violation")
    }

    @Test
    fun `retry-after is refused on a kind that repetition cannot fix`() {
        assertThatThrownBy { Fault(FaultKind.CONFLICT, retryAfter = Duration.ofSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("only a retryable fault")
    }

    @Test
    fun `retry-after is positive`() {
        assertThatThrownBy { Fault.retryable(retryAfter = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Fault.retryable(retryAfter = Duration.ofSeconds(2)).retryAfter).isEqualTo(Duration.ofSeconds(2))
        assertThat(Fault(FaultKind.TOO_MANY_REQUESTS, retryAfter = Duration.ofSeconds(1)).kind.retryable).isTrue()
    }

    @Test
    fun `the detail is the fault's own message, or the code's default`() {
        assertThat(Fault.conflict(message = "the ticket is closed").detail).isEqualTo("the ticket is closed")
        assertThat(Fault.conflict(RainErrorCodes.STALE_VERSION).detail).isEqualTo(RainErrorCodes.STALE_VERSION.defaultMessage)
    }

    @Test
    fun `an empty message is refused rather than rendered blank`() {
        assertThatThrownBy { Fault.notFound(message = "") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the log form names operation, entity, kind and code but no message`() {
        val fault = Fault(FaultKind.NOT_FOUND, message = "secret detail", entity = "ticket", op = "close")

        assertThat(fault.toString()).isEqualTo("fault: close ticket: not_found: not_found")
    }
}
