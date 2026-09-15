package com.gd.rain.core.error

import java.time.Duration

/**
 * A refusal on its way to a client.
 *
 * The [kind] decides the status, the [code] is what a client branches on, and [violations] say what
 * exactly was wrong. [entity] and [op] never reach a client; they make the log line name the
 * operation that refused.
 *
 * The invariants are checked at construction, so a transport never has to decide what an incoherent
 * fault meant: a validation fault names at least one violation, and only a kind that may succeed on
 * repetition carries a positive [retryAfter].
 */
public class Fault(
    public val kind: FaultKind,
    public val code: ErrorCode = kind.defaultCode,
    message: String? = null,
    public val violations: List<Violation> = emptyList(),
    public val partial: Boolean = false,
    public val retryAfter: Duration? = null,
    public val entity: String? = null,
    public val op: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    init {
        require(kind != FaultKind.VALIDATION || violations.isNotEmpty()) { "a validation fault names at least one violation" }
        require(retryAfter == null || kind.retryable) { "only a retryable fault carries retryAfter, not $kind" }
        require(retryAfter == null || (!retryAfter.isZero && !retryAfter.isNegative)) { "retryAfter is positive, got $retryAfter" }
        require(message == null || Violation.isValidMessage(message)) {
            "a fault message is non-empty and at most ${Violation.MAX_MESSAGE_BYTES} UTF-8 bytes"
        }
    }

    /** What a client is told: the fault's own message, or the code's declared default. */
    public val detail: String get() = message ?: code.defaultMessage

    override fun toString(): String =
        buildString {
            append("fault: ")
            op?.let { append(it).append(' ') }
            entity?.let { append(it).append(": ") }
            append(kind.name.lowercase()).append(": ").append(code.value)
            if (violations.isNotEmpty()) append(" (").append(violations.size).append(" violations)")
        }

    public companion object {
        public fun notFound(
            code: ErrorCode = RainErrorCodes.NOT_FOUND,
            message: String? = null,
        ): Fault = Fault(FaultKind.NOT_FOUND, code, message)

        public fun unauthorized(
            code: ErrorCode = RainErrorCodes.UNAUTHENTICATED,
            message: String? = null,
        ): Fault = Fault(FaultKind.UNAUTHORIZED, code, message)

        public fun forbidden(
            code: ErrorCode = RainErrorCodes.FORBIDDEN,
            message: String? = null,
        ): Fault = Fault(FaultKind.FORBIDDEN, code, message)

        public fun conflict(
            code: ErrorCode = RainErrorCodes.CONFLICT,
            message: String? = null,
        ): Fault = Fault(FaultKind.CONFLICT, code, message)

        public fun badRequest(
            code: ErrorCode = RainErrorCodes.BAD_REQUEST,
            message: String? = null,
        ): Fault = Fault(FaultKind.BAD_REQUEST, code, message)

        public fun validation(
            violations: List<Violation>,
            partial: Boolean = false,
        ): Fault = Fault(FaultKind.VALIDATION, violations = violations, partial = partial)

        public fun retryable(
            code: ErrorCode = RainErrorCodes.UNAVAILABLE,
            message: String? = null,
            retryAfter: Duration? = null,
        ): Fault = Fault(FaultKind.RETRYABLE, code, message, retryAfter = retryAfter)

        public fun tooLarge(message: String? = null): Fault = Fault(FaultKind.TOO_LARGE, RainErrorCodes.TOO_LARGE, message)
    }
}
