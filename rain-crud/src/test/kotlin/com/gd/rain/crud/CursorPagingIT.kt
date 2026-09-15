package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.QueryCompiler
import com.gd.rain.crud.query.SortKey
import com.gd.rain.test.PlanVerdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Gap 25: a page before a cursor used to drop the row nearest to it. Walking forward and then back over
 * real rows — with sort values shared by several rows — now reproduces every page exactly, and each page
 * reaches its rows through the order's index under a limit (criterion v2 of `QueryPlan.boundedScan`).
 */
@Tag("integration")
class CursorPagingIT {
    private val database = BookDatabase("crud_cursor")
    private val resource =
        CrudResource(Books.rules(), Books.policy(), database.store, SwitchableCallers(TestCaller.of("a", Books.READ)), emptyList())

    init {
        // 37 rows: created in groups of three per minute, and eleven titles shared around, so sort values repeat.
        (0 until 37).forEach { index ->
            database.insert(
                book(
                    "t%02d".format(index % 11),
                    if (index % 2 ==
                        0
                    ) {
                        "a"
                    } else {
                        "b"
                    },
                    index,
                    T0.plus(Duration.ofMinutes(index / 3L)),
                ),
            )
        }
    }

    private fun assertWalksBothWays(
        sort: String,
        orderBy: String,
    ) {
        val forward = resource.walk(sort, 5, start = null) { it.next }

        assertThat(forward.flatMap { it.ids() }).isEqualTo(database.idsOrderedBy(orderBy))
        assertThat(forward.map { it.items.size }).containsExactly(5, 5, 5, 5, 5, 5, 5, 2)
        assertThat(forward.first().cursor.prev).isNull()
        assertThat(forward.last().cursor.next).isNull()

        val backward = resource.walk(sort, 5, start = forward.last().cursor.prev) { it.prev }

        assertThat(backward.map { it.ids() }).isEqualTo(forward.dropLast(1).map { it.ids() }.reversed())
        assertThat(backward.last().cursor.prev).isNull()
        backward.forEach { page -> assertThat(page.cursor.next).isNotNull() }
    }

    @Test
    fun `forward pages by newest first, then backward pages, reproduce the same ids including shared sort values`() {
        assertWalksBothWays("-createdAt", "created_at DESC, id DESC")
    }

    @Test
    fun `the same holds for an ascending text sort`() {
        assertWalksBothWays("title", "title ASC, id ASC")
    }

    @Test
    fun `a page after a backward page continues from its last row`() {
        val forward = resource.walk("-createdAt", 5, start = null) { it.next }
        val back =
            resource.list(
                mapOf(
                    "sort" to listOf("-createdAt"),
                    "limit" to listOf("5"),
                    "cursor" to listOf(checkNotNull(forward[3].cursor.prev)),
                ),
            )

        val again =
            resource.list(
                mapOf(
                    "sort" to listOf("-createdAt"),
                    "limit" to listOf("5"),
                    "cursor" to listOf(checkNotNull(back.cursor.next)),
                ),
            )

        assertThat(back.ids()).isEqualTo(forward[2].ids())
        assertThat(again.ids()).isEqualTo(forward[3].ids())
    }

    @Test
    fun `a cursor page reaches its rows through the order's index as its condition, under a limit`() {
        val order = QueryCompiler(Books.SCHEMA, Books.rules(), emptySet()).effectiveOrder(SortKey.parse("-createdAt"))
        val boundary = resource.walk("-createdAt", 5, start = null) { null }.single()
        val keys = listOf(boundary.items.last()["createdAt"] as Any, boundary.items.last()["id"] as Any)
        val seek = Predicate.Keyset(order, keys, Predicate.Side.AFTER)

        val everywhere = database.plan(database.store.readQuery(RowRead(RowScope.Everything, null, seek, order, 6, 0, Projection.Full)))
        val shelf = RowScope.Matching(Predicate.eq(Books.SHELF, "a"))
        val scoped = database.plan(database.store.readQuery(RowRead(shelf, null, seek, order, 6, 0, Projection.Full)))

        assertThat(everywhere.usesIndex("books_created_at_id")).describedAs("%s", everywhere).isTrue()
        assertThat(everywhere.boundedScan("books")).describedAs("%s", everywhere).isEqualTo(PlanVerdict.Bounded)
        assertThat(scoped.usesIndex("books_shelf_created_at_id")).describedAs("%s", scoped).isTrue()
        assertThat(scoped.boundedScan("books")).describedAs("%s", scoped).isEqualTo(PlanVerdict.Bounded)
    }
}
