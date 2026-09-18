package com.gd.rain.persistence.fault

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.Violation

/** Structured PostgreSQL provenance. It is mapper input and is never serialized to a client. */
public data class DatabaseErrorSource(
    public val schema: String?,
    public val table: String?,
    public val column: String?,
    public val constraint: String?,
) {
    init {
        require(listOf(schema, table, column, constraint).any { !it.isNullOrBlank() }) {
            "database error provenance names at least one source"
        }
    }
}

/** A classified database refusal offered to a targeted violation mapper. */
public data class DatabaseError(
    public val sqlState: String,
    public val kind: FaultKind,
    public val defaultCode: ErrorCode,
    public val source: DatabaseErrorSource?,
) {
    init {
        require(SQL_STATE.matches(sqlState)) { "SQLSTATE \"$sqlState\" is five upper-case letters or digits" }
    }

    private companion object {
        val SQL_STATE: Regex = Regex("^[0-9A-Z]{5}$")
    }
}

/**
 * Maps one classified database refusal to a public violation path/code. Mappers are asked in Spring
 * order and the first answer wins; returning `null` keeps looking and ultimately uses Rain's safe
 * general refusal. Constraint, table and column names never reach the wire by themselves.
 */
public fun interface DatabaseViolationMapper {
    public fun map(error: DatabaseError): Violation?
}
