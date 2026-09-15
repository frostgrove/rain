package com.gd.rain.crud.error

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog

/**
 * The codes rain-crud declares.
 *
 * rain-crud also answers with these codes of rain-core: `unauthenticated`, `forbidden`, `not_found`, `unknown_parameter`,
 * `bad_query`, `unknown_field`, `invalid_format`, `out_of_range`, `required`, `invalid_id`, `malformed_body`,
 * `validation_failed` and `stale_version` — and, through rain-persistence, the codes a database refusal is classified as
 * (`unique`, `foreign_key`, …).
 */
public object RainCrudErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-crud"

    /** A declared field or relation the resource does not grant for the way the query or the write uses it. */
    public val FIELD_NOT_GRANTED: ErrorCode = ErrorCode.of("field_not_granted", "this field may not be used this way")

    /** The `<op>` of a `filter[<field>][<op>]` parameter is not one of the dialect's operators. */
    public val UNKNOWN_OPERATOR: ErrorCode = ErrorCode.of("unknown_operator", "this is not an operator of the query dialect")

    /** Something the dialect knows that this resource does not declare: a query shape, or counting. */
    public val NOT_OFFERED: ErrorCode = ErrorCode.of("not_offered", "this resource does not offer that")

    /** A write whose row, as it would be stored, is outside the rows the caller's scope reaches. */
    public val OUTSIDE_SCOPE: ErrorCode = ErrorCode.of("outside_scope", "the row would be outside the rows you may reach")

    override val codes: List<ErrorCode> = listOf(FIELD_NOT_GRANTED, UNKNOWN_OPERATOR, NOT_OFFERED, OUTSIDE_SCOPE)
}
