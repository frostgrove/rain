package com.gd.rain.crud.persistence

import com.gd.rain.crud.BookDatabase
import com.gd.rain.crud.Books
import com.gd.rain.crud.CrudStore
import com.gd.rain.crud.CrudStoreContract
import com.gd.rain.crud.RowRead
import com.gd.rain.crud.RowScope
import com.gd.rain.crud.query.Direction
import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.persistence.id.UuidV7Ids
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The store contract against PostgreSQL: quoted identifiers, typed binds, `RETURNING`, transactional scope checks and `timestamptz` read back. */
@Tag("integration")
class JooqResourceStoreIT : CrudStoreContract() {
    override fun freshStore(schema: ResourceSchema): CrudStore<Map<String, Any?>> {
        val database = BookDatabase("crud_store")
        return JooqResourceStore(schema, database.dsl, UuidV7Ids, RowReader.fields(schema))
    }

    @Test
    fun `statements name the application's schema-qualified table and bind every value`() {
        val database = BookDatabase("crud_store_sql")
        val read =
            RowRead(
                RowScope.Matching(Predicate.eq(Books.SHELF, "a'; DROP TABLE books; --")),
                Predicate.eq(Books.TITLE, "dune"),
                null,
                listOf(Order(Books.TITLE, Direction.ASC), Order(Books.ID, Direction.ASC)),
                5,
                0,
                Projection.Full,
            )

        val query = database.store.readQuery(read)

        assertThat(query.sql).contains("from \"public\".\"books\"").doesNotContain("dune").doesNotContain("DROP")
        assertThat(query.bindValues).contains("a'; DROP TABLE books; --", "dune")
        assertThat(database.dsl.fetch(query)).isEmpty()
        assertThat(database.jdbc.queryForObject("SELECT count(*) FROM public.books", Long::class.java)).isZero()
    }
}
