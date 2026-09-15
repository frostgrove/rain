package com.gd.rain.persistence.json

/**
 * A typed value stored in a `jsonb` column. A module declares the shape once
 * (`data class Diagnostics(val entries: List<String>) : JsonbPayload`) and the column converts through
 * it, never through a string or a map.
 */
public interface JsonbPayload

/**
 * A Kotlin enum over a `CHECK`-constrained text column. [wire] is the stored string, written down per
 * constant rather than derived from the constant's name.
 */
public interface WireEnum {
    public val wire: String
}
