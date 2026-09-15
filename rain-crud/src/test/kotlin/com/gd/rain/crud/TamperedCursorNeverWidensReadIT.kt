package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.CursorCodec
import com.gd.rain.crud.query.CursorDirection
import com.gd.rain.crud.query.CursorPosition
import com.gd.rain.crud.query.QueryCompiler
import com.gd.rain.crud.query.SortKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A cursor is readable JSON a caller can rewrite: its keys only say where a page starts. Against PostgreSQL, a cursor
 * whose keys were forged to lie before every row, after every row, or on another caller's row still pages through the
 * caller's scope, the request's filter and the request's fields only.
 */
@Tag("integration")
class TamperedCursorNeverWidensReadIT {
    private val database = BookDatabase("crud_tampered_cursor")
    private val resource =
        CrudResource(
            Books.rules(),
            Books.policy(Books.SHELF_SCOPE),
            database.store,
            SwitchableCallers(TestCaller.of("a", Books.READ)),
            emptyList(),
        )
    private val order = QueryCompiler(Books.SCHEMA, Books.rules(), emptySet(), setOf(Books.ID)).effectiveOrder(SortKey.parse("-createdAt"))

    private val mine = (0 until 4).flatMap { database.insert(book("a$it", "a", it, T0.plusSeconds(it.toLong()))) }
    private val theirs = (0 until 4).flatMap { database.insert(book("b$it", "b", it, T0.plusSeconds(it.toLong()))) }

    private fun forged(
        direction: CursorDirection,
        createdAt: Instant,
        id: UUID,
    ): String = CursorCodec.encode(order, CursorPosition(direction, listOf(createdAt, id)))

    private fun page(
        cursor: String,
        vararg parameters: Pair<String, String>,
    ): Page<Map<String, Any?>> =
        resource.list(
            mapOf("sort" to listOf("-createdAt"), "cursor" to listOf(cursor)) + parameters.associate { it.first to listOf(it.second) },
        )

    @Test
    fun `a cursor forged past either end of the table reads only the caller's shelf`() {
        val beforeEveryRow =
            forged(CursorDirection.NEXT, Instant.parse("9999-12-31T00:00:00Z"), UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"))
        val afterEveryRow = forged(CursorDirection.PREV, Instant.EPOCH, UUID(0, 0))

        assertThat(page(beforeEveryRow).ids()).containsExactlyElementsOf(mine.reversed())
        assertThat(page(afterEveryRow).ids()).containsExactlyElementsOf(mine.reversed())
    }

    @Test
    fun `a cursor keyed on another caller's row continues through the caller's own rows`() {
        val theirNewest = database.stored(theirs.last())!!

        val next = page(forged(CursorDirection.NEXT, theirNewest["createdAt"] as Instant, theirs.last()))

        assertThat(next.ids()).isNotEmpty().allMatch { it in mine }
        assertThat(next.items.map { it["shelf"] }.toSet()).containsExactly("a")
    }

    @Test
    fun `a forged cursor keeps the request's filter and fields`() {
        val beforeEveryRow =
            forged(CursorDirection.NEXT, Instant.parse("9999-12-31T00:00:00Z"), UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"))

        assertThat(page(beforeEveryRow, "filter[shelf][eq]" to "b").items).isEmpty()
        val selected = page(beforeEveryRow, "filter[shelf][eq]" to "a", "fields" to "title")
        assertThat(selected.ids()).containsExactlyElementsOf(mine.reversed())
        assertThat(selected.items).allMatch { it.keys == setOf("id", "title") }
    }
}
