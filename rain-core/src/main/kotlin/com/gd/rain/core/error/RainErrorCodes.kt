package com.gd.rain.core.error

/**
 * The codes rain-core itself declares: storage constraints, request shape, transport refusals and
 * the kinds' defaults. Module-specific codes (credentials, jobs, …) are declared by those modules.
 */
public object RainErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-core"

    public val UNIQUE: ErrorCode = ErrorCode.of("unique", "this value is already taken")
    public val NOT_UNIQUE: ErrorCode = ErrorCode.of("not_unique", "more than one record matches")
    public val FOREIGN_KEY: ErrorCode = ErrorCode.of("foreign_key", "the record this refers to does not exist")
    public val RESTRICT: ErrorCode = ErrorCode.of("restrict", "this record is still referred to")
    public val REQUIRED: ErrorCode = ErrorCode.of("required", "this field is required")
    public val CHECK: ErrorCode = ErrorCode.of("check", "this value is not allowed")
    public val EXCLUSION: ErrorCode = ErrorCode.of("exclusion", "this value overlaps one that is already there")
    public val TOO_LONG: ErrorCode = ErrorCode.of("too_long", "this value is too long")
    public val OUT_OF_RANGE: ErrorCode = ErrorCode.of("out_of_range", "this value is out of range")
    public val INVALID_FORMAT: ErrorCode = ErrorCode.of("invalid_format", "this value is not in the expected format")
    public val INVALID_ENUM: ErrorCode = ErrorCode.of("invalid_enum", "this value is not one of the allowed ones")
    public val STALE_VERSION: ErrorCode = ErrorCode.of("stale_version", "the record was changed by someone else")

    public val VALIDATION_FAILED: ErrorCode = ErrorCode.of("validation_failed", "the request is not valid")
    public val MALFORMED_BODY: ErrorCode = ErrorCode.of("malformed_body", "the request body could not be read")
    public val INVALID_ID: ErrorCode = ErrorCode.of("invalid_id", "the identifier could not be read")
    public val UNKNOWN_FIELD: ErrorCode = ErrorCode.of("unknown_field", "the request names a field that does not exist")
    public val UNKNOWN_PARAMETER: ErrorCode = ErrorCode.of("unknown_parameter", "the request names a parameter that does not exist")
    public val BAD_REQUEST: ErrorCode = ErrorCode.of("bad_request", "the request could not be understood")
    public val BAD_QUERY: ErrorCode = ErrorCode.of("bad_query", "the query could not be read")

    public val NOT_FOUND: ErrorCode = ErrorCode.of("not_found", "not found")
    public val CONFLICT: ErrorCode = ErrorCode.of("conflict", "the request conflicts with the current state")
    public val FORBIDDEN: ErrorCode = ErrorCode.of("forbidden", "not allowed")
    public val UNAUTHENTICATED: ErrorCode = ErrorCode.of("unauthenticated", "authentication is required")
    public val TOO_LARGE: ErrorCode = ErrorCode.of("too_large", "the request body is too large")
    public val METHOD_NOT_ALLOWED: ErrorCode = ErrorCode.of("method_not_allowed", "this path does not answer that method")
    public val NOT_ACCEPTABLE: ErrorCode = ErrorCode.of("not_acceptable", "no acceptable representation is available")
    public val UNSUPPORTED_MEDIA_TYPE: ErrorCode = ErrorCode.of("unsupported_media_type", "the request body has an unsupported media type")
    public val TOO_MANY_REQUESTS: ErrorCode = ErrorCode.of("too_many_requests", "too many requests; try again later")

    public val DEADLOCK: ErrorCode = ErrorCode.of("deadlock", RETRY_MESSAGE)
    public val SERIALIZATION_FAILURE: ErrorCode = ErrorCode.of("serialization_failure", RETRY_MESSAGE)
    public val LOCK_TIMEOUT: ErrorCode = ErrorCode.of("lock_timeout", RETRY_MESSAGE)
    public val STATEMENT_TIMEOUT: ErrorCode = ErrorCode.of("statement_timeout", RETRY_MESSAGE)
    public val TRANSACTION_ABORTED: ErrorCode = ErrorCode.of("transaction_aborted", RETRY_MESSAGE)
    public val UNAVAILABLE: ErrorCode = ErrorCode.of("unavailable", RETRY_MESSAGE)
    public val DEADLINE_EXCEEDED: ErrorCode = ErrorCode.of("deadline_exceeded", "the request did not finish within its time budget")

    public val UNMAPPED_STATUS: ErrorCode = ErrorCode.of("unmapped_status", "the request failed")
    public val INTERNAL: ErrorCode = ErrorCode.of("internal", "the request failed")

    override val codes: List<ErrorCode> =
        listOf(
            UNIQUE,
            NOT_UNIQUE,
            FOREIGN_KEY,
            RESTRICT,
            REQUIRED,
            CHECK,
            EXCLUSION,
            TOO_LONG,
            OUT_OF_RANGE,
            INVALID_FORMAT,
            INVALID_ENUM,
            STALE_VERSION,
            VALIDATION_FAILED,
            MALFORMED_BODY,
            INVALID_ID,
            UNKNOWN_FIELD,
            UNKNOWN_PARAMETER,
            BAD_REQUEST,
            BAD_QUERY,
            NOT_FOUND,
            CONFLICT,
            FORBIDDEN,
            UNAUTHENTICATED,
            TOO_LARGE,
            METHOD_NOT_ALLOWED,
            NOT_ACCEPTABLE,
            UNSUPPORTED_MEDIA_TYPE,
            TOO_MANY_REQUESTS,
            DEADLOCK,
            SERIALIZATION_FAILURE,
            LOCK_TIMEOUT,
            STATEMENT_TIMEOUT,
            TRANSACTION_ABORTED,
            UNAVAILABLE,
            DEADLINE_EXCEEDED,
            UNMAPPED_STATUS,
            INTERNAL,
        )
}

private const val RETRY_MESSAGE = "the request could not be completed; try again"
