package com.gd.rain.crud.error

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog

/**
 * The codes rain-crud declares. The dialect also uses rain-core's `unknown_parameter`, `bad_query`,
 * `unknown_field`, `invalid_format`, `out_of_range` and `invalid_id`.
 */
public object RainCrudErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-crud"

    /** A declared field or relation the resource does not grant for the way the query uses it. */
    public val FIELD_NOT_GRANTED: ErrorCode = ErrorCode.of("field_not_granted", "this field may not be used this way")

    /** The `<op>` of a `filter[<field>][<op>]` parameter is not one of the dialect's operators. */
    public val UNKNOWN_OPERATOR: ErrorCode = ErrorCode.of("unknown_operator", "this is not an operator of the query dialect")

    /** A cursor that cannot be read, or one minted for a different order than the query asks for. */
    public val INVALID_CURSOR: ErrorCode = ErrorCode.of("invalid_cursor", "the cursor cannot continue this query")

    /** Something the dialect knows that this resource does not declare: a query shape, or counting. */
    public val NOT_OFFERED: ErrorCode = ErrorCode.of("not_offered", "this resource does not offer that")

    override val codes: List<ErrorCode> = listOf(FIELD_NOT_GRANTED, UNKNOWN_OPERATOR, INVALID_CURSOR, NOT_OFFERED)
}
