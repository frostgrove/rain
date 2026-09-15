package com.gd.rain.persistence.id

import com.fasterxml.uuid.Generators
import com.gd.rain.core.id.IdGenerator
import java.util.UUID

/**
 * RFC 9562 version 7: 48 bits of Unix milliseconds, then random bits re-rolled on every call, so an
 * identifier is time-ordered for index locality and not guessable from another one.
 */
public object UuidV7Ids : IdGenerator {
    private val generator = Generators.timeBasedEpochRandomGenerator()

    override fun next(): UUID = generator.generate()
}
