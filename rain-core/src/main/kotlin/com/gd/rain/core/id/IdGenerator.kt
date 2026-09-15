package com.gd.rain.core.id

import java.util.UUID

/**
 * Where a new identifier comes from.
 *
 * Identifiers are minted by the application before the row is written, so storage keys, events and
 * log lines can carry them; an interface so a test can hand in a deterministic sequence.
 */
public fun interface IdGenerator {
    public fun next(): UUID
}
