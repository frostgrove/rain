package com.gd.rain.crud

import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.query.Projection
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.test.QueryPlan
import com.gd.rain.test.QueryPlans
import com.gd.rain.test.RainPostgres
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

/** A fresh database holding the application-owned `public.books` table, and the jOOQ store over it. */
class BookDatabase(
    prefix: String,
) {
    val dataSource: DataSource = RainPostgres.freshDatabase(prefix).dataSource()
    val dsl: DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)
    val store: JooqResourceStore<Map<String, Any?>> = JooqResourceStore(Books.SCHEMA, dsl, UuidV7Ids, RowReader.fields(Books.SCHEMA))
    val jdbc: JdbcTemplate = JdbcTemplate(dataSource)

    init {
        jdbc.execute(Books.DDL)
    }

    fun insert(vararg books: Map<String, Any?>): List<UUID> =
        books.map { (store.insert(RowScope.Everything, it) as InsertOutcome.Inserted).item["id"] as UUID }

    fun stored(id: UUID): Map<String, Any?>? = store.find(id, RowScope.Everything, Projection.Full)

    /** The identifiers in the order PostgreSQL itself sorts them by [orderBy] — the reference a paging walk is compared with. */
    fun idsOrderedBy(orderBy: String): List<UUID> =
        jdbc.queryForList("SELECT id FROM public.books ORDER BY $orderBy", UUID::class.java).map(::checkNotNull)

    fun plan(query: Query): QueryPlan = QueryPlans.explain(dataSource, dsl.renderInlined(query), generic = false)
}

/** Follows [PageWindow.Cursor.next] (or `prev`) from [start] until there is no page that way. */
fun CrudResource<Map<String, Any?>>.walk(
    sort: String,
    limit: Int,
    start: String?,
    towards: (PageWindow.Cursor) -> String?,
): List<Page<Map<String, Any?>>> {
    val pages = mutableListOf<Page<Map<String, Any?>>>()
    var cursor = start
    do {
        val parameters =
            buildMap {
                if (sort.isNotEmpty()) put("sort", listOf(sort))
                put("limit", listOf(limit.toString()))
                cursor?.let { put("cursor", listOf(it)) }
            }
        val page = list(parameters)
        pages += page
        cursor = towards(page.window as PageWindow.Cursor)
    } while (cursor != null)
    return pages
}

fun Page<Map<String, Any?>>.ids(): List<UUID> = items.map { it["id"] as UUID }

val Page<Map<String, Any?>>.cursor: PageWindow.Cursor get() = window as PageWindow.Cursor
