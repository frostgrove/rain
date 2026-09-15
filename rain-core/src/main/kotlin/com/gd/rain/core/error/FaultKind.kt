package com.gd.rain.core.error

/**
 * What kind of refusal a [Fault] is, and the HTTP status it renders as.
 *
 * The set is closed and total: every kind has exactly one status and one default code. How a
 * transport maps statuses it did not produce itself (a framework's 406, 408, 425, …) back to a
 * refusal is the transport's declarative status table, not a guess made here.
 */
public enum class FaultKind(
    public val status: Int,
    public val defaultCode: ErrorCode,
) {
    INTERNAL(500, RainErrorCodes.INTERNAL),
    BAD_REQUEST(400, RainErrorCodes.BAD_REQUEST),
    UNAUTHORIZED(401, RainErrorCodes.UNAUTHENTICATED),
    FORBIDDEN(403, RainErrorCodes.FORBIDDEN),
    NOT_FOUND(404, RainErrorCodes.NOT_FOUND),
    METHOD_NOT_ALLOWED(405, RainErrorCodes.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(406, RainErrorCodes.NOT_ACCEPTABLE),
    CONFLICT(409, RainErrorCodes.CONFLICT),
    TOO_LARGE(413, RainErrorCodes.TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(415, RainErrorCodes.UNSUPPORTED_MEDIA_TYPE),
    VALIDATION(422, RainErrorCodes.VALIDATION_FAILED),
    TOO_MANY_REQUESTS(429, RainErrorCodes.TOO_MANY_REQUESTS),
    RETRYABLE(503, RainErrorCodes.UNAVAILABLE),
    ;

    /** Whether the same request may succeed if repeated unchanged; it decides whether `Retry-After` is allowed. */
    public val retryable: Boolean get() = this == RETRYABLE || this == TOO_MANY_REQUESTS
}
