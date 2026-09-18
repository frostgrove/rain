package com.gd.rain.crud.error

import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.ViolationOrigin
import com.gd.rain.core.error.path
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.persistence.fault.DatabaseError
import com.gd.rain.persistence.fault.DatabaseViolationMapper
import org.springframework.core.Ordered

/**
 * Resolves PostgreSQL's exact schema/table/column provenance through a declared CRUD schema. It
 * answers only when every matching resource agrees on one public field name; ambiguity stays a safe
 * general refusal instead of leaking or guessing a column name.
 */
public class CrudDatabaseViolationMapper(
    private val schemas: () -> List<ResourceSchema>,
) : DatabaseViolationMapper,
    Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun map(error: DatabaseError): Violation? {
        val source = error.source ?: return null
        val schema = source.schema ?: return null
        val table = source.table ?: return null
        val column = source.column ?: return null
        val matching =
            schemas()
                .asSequence()
                .filter { it.table.schema == schema && it.table.name == table }
                .toList()
        if (matching.isEmpty()) return null
        val resolved = matching.map { resource -> resource.fields.singleOrNull { it.column == column }?.name }
        if (resolved.any { it == null }) return null
        val publicNames =
            resolved
                .filterNotNull()
                .distinct()
        if (publicNames.size != 1) return null
        return Violation.at(path(publicNames.single()), error.defaultCode, origin = originOf(error))
    }

    private fun originOf(error: DatabaseError): ViolationOrigin =
        when (error.defaultCode) {
            RainErrorCodes.UNIQUE,
            RainErrorCodes.FOREIGN_KEY,
            RainErrorCodes.RESTRICT,
            RainErrorCodes.EXCLUSION,
            -> ViolationOrigin.STATE

            else -> ViolationOrigin.INPUT
        }
}
