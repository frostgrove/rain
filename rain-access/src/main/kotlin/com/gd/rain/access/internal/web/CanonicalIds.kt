package com.gd.rain.access.internal.web

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import java.util.UUID

/**
 * A UUID in its canonical text form and no other: 36 characters, hex digits in groups of 8-4-4-4-12 separated by
 * hyphens. `UUID.fromString` also accepts `1-1-1-1-1` and over-long groups, so two spellings would name one id — or a
 * spelling no stored id has would reach a query as a different value.
 */
public object CanonicalIds {
    private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    public fun parse(text: String): UUID? = if (CANONICAL.matches(text)) UUID.fromString(text) else null

    /** The id, or `400 invalid_id` naming the path segment or parameter it was read from. */
    public fun required(
        text: String,
        name: String,
    ): UUID =
        parse(text) ?: throw Fault(
            FaultKind.BAD_REQUEST,
            RainErrorCodes.INVALID_ID,
            violations = listOf(Violation.at(path(name), RainErrorCodes.INVALID_ID, "\"$name\" is not a canonical UUID")),
        )
}
