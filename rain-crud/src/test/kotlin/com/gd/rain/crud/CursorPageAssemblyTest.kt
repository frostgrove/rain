package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.CursorCodec
import com.gd.rain.crud.query.CursorDirection
import com.gd.rain.crud.query.CursorPosition
import com.gd.rain.crud.query.Direction
import com.gd.rain.crud.query.Order
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Gap 25 at the resource: how cursor and offset pages are read and assembled, over the in-memory store. */
class CursorPageAssemblyTest {
    private val store = MemoryStore(Books.SCHEMA)
    private val resource =
        CrudResource(Books.rules(), Books.policy(), store, SwitchableCallers(TestCaller.of("a", Books.READ)), emptyList())

    init {
        "abcdefg".forEachIndexed { index, title -> store.add(book(title.toString(), "a", index, T0.plusSeconds(index.toLong()))) }
    }

    private fun page(vararg parameters: Pair<String, String>): Pair<List<Any?>, PageWindow.Cursor> {
        val page = resource.list(parameters.associate { (name, value) -> name to listOf(value) })
        return page.items.map { it["title"] } to page.window as PageWindow.Cursor
    }

    @Test
    fun `a page before a cursor keeps the rows nearest to it and reports the page before those`() {
        val (_, first) = page("sort" to "title", "limit" to "3")
        val (_, second) = page("sort" to "title", "limit" to "3", "cursor" to checkNotNull(first.next))
        val (last, third) = page("sort" to "title", "limit" to "3", "cursor" to checkNotNull(second.next))
        assertThat(last).containsExactly("g")

        val (backSecond, backSecondWindow) = page("sort" to "title", "limit" to "3", "cursor" to checkNotNull(third.prev))
        val (backFirst, backFirstWindow) = page("sort" to "title", "limit" to "3", "cursor" to checkNotNull(backSecondWindow.prev))

        assertThat(backSecond).containsExactly("d", "e", "f")
        assertThat(backSecondWindow.next).isNotNull()
        assertThat(backFirst).containsExactly("a", "b", "c")
        assertThat(backFirstWindow.prev).isNull()
        assertThat(
            page("sort" to "title", "limit" to "3", "cursor" to checkNotNull(backFirstWindow.next)).first,
        ).containsExactly("d", "e", "f")
    }

    @Test
    fun `a first page has no prev and a last page no next`() {
        val (items, first) = page("sort" to "title", "limit" to "7")

        assertThat(items).hasSize(7)
        assertThat(first.next).isNull()
        assertThat(first.prev).isNull()
    }

    @Test
    fun `an empty page past either end offers no cursor`() {
        val order = listOf(Order(Books.TITLE, Direction.ASC), Order(Books.ID, Direction.ASC))
        val firstRow = store.read(RowRead(RowScope.Everything, null, null, order, 1, 0, com.gd.rain.crud.query.Projection.Full)).single()
        val beforeFirst = CursorCodec.encode(order, CursorPosition(CursorDirection.PREV, firstRow.keys))

        val (items, window) = page("sort" to "title", "cursor" to beforeFirst)

        assertThat(items).isEmpty()
        assertThat(window.next).isNull()
        assertThat(window.prev).isNull()
    }

    @Test
    fun `a cursor page reads one row past its limit in the effective order, and a page before reads the inverted order`() {
        val (_, first) = page("sort" to "-createdAt", "limit" to "3")
        page("sort" to "-createdAt", "limit" to "3", "cursor" to checkNotNull(first.next))
        val forward = store.reads.last()
        page(
            "sort" to "-createdAt",
            "limit" to "3",
            "cursor" to checkNotNull(page("sort" to "-createdAt", "limit" to "3", "cursor" to checkNotNull(first.next)).second.prev),
        )
        val backward = store.reads.last()

        assertThat(forward.limit).isEqualTo(4)
        assertThat(forward.order.map { "${it.field.name}:${it.direction}" }).containsExactly("createdAt:DESC", "id:DESC")
        assertThat(backward.order.map { "${it.field.name}:${it.direction}" }).containsExactly("createdAt:ASC", "id:ASC")
        assertThat(backward.seek?.order).isEqualTo(backward.order)
    }

    @Test
    fun `an offset page reads one row past its limit and reports hasNext from it`() {
        val tail = resource.list(mapOf("sort" to listOf("pages"), "offset" to listOf("5"), "limit" to listOf("2")))
        val middle = resource.list(mapOf("sort" to listOf("pages"), "offset" to listOf("4"), "limit" to listOf("2")))

        assertThat(tail.items.map { it["title"] }).containsExactly("f", "g")
        assertThat(tail.window).isEqualTo(PageWindow.Offset(2, 5, hasNext = false))
        assertThat(middle.window).isEqualTo(PageWindow.Offset(2, 4, hasNext = true))
        assertThat(store.reads.last().limit).isEqualTo(3)
    }
}
