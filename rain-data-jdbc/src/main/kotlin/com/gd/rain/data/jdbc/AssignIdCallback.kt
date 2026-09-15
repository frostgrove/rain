package com.gd.rain.data.jdbc

import com.gd.rain.core.id.IdGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback
import java.util.UUID

/**
 * Fills a null `UUID` `@Id` from the application's [IdGenerator] before the aggregate is converted.
 *
 * It does not decide insert versus update: Spring Data JDBC asks `Persistable.isNew()` first, then the
 * version property, and only then the id. The accessor is used rather than the field so an immutable
 * aggregate gets a copy with the id, which is why the accessor's bean is returned.
 */
public class AssignIdCallback(
    private val ids: IdGenerator,
    private val mappingContext: ObjectProvider<RelationalMappingContext>,
) : BeforeConvertCallback<Any> {
    override fun onBeforeConvert(aggregate: Any): Any {
        val entity = mappingContext.getObject().getRequiredPersistentEntity(aggregate.javaClass)
        val id = entity.idProperty ?: return aggregate
        if (id.type != UUID::class.java) return aggregate

        val accessor = entity.getPropertyAccessor(aggregate)
        if (accessor.getProperty(id) != null) return aggregate

        accessor.setProperty(id, ids.next())
        return accessor.bean
    }
}
