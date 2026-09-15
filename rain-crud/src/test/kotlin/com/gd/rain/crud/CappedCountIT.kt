package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.Predicate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Gap 27: `count(*)` used to scan every matching row; a count now reads through `LIMIT cap + 1` and says whether it is exact. */
@Tag("integration")
class CappedCountIT {
    private val database = BookDatabase("crud_count")
    private val resource =
        CrudResource(
            Books.rules(countCap = 10),
            Books.policy(),
            database.store,
            SwitchableCallers(TestCaller.of("a", Books.READ)),
            emptyList(),
        )

    private fun countOn(shelf: String) = resource.count(mapOf("filter[shelf][eq]" to listOf(shelf)))

    @Test
    fun `a count stops at the cap and says whether it is exact`() {
        repeat(25) { database.insert(book("a$it", "a", 1, T0)) }
        repeat(7) { database.insert(book("b$it", "b", 1, T0)) }

        assertThat(countOn("a")).isEqualTo(CappedCount(10, exact = false))
        assertThat(countOn("b")).isEqualTo(CappedCount(7, exact = true))

        repeat(3) { database.insert(book("b+$it", "b", 1, T0)) }
        assertThat(countOn("b")).isEqualTo(CappedCount(10, exact = true))

        database.insert(book("b++", "b", 1, T0))
        assertThat(countOn("b")).isEqualTo(CappedCount(10, exact = false))
        assertThat(
            resource.list(mapOf("filter[shelf][eq]" to listOf("b"), "count" to listOf("capped"))).count,
        ).isEqualTo(CappedCount(10, exact = false))
    }

    @Test
    fun `the count statement reads through a limit of cap plus one over an index`() {
        repeat(25) { database.insert(book("a$it", "a", 1, T0)) }
        database.jdbc.execute("ANALYZE public.books")
        val query = database.store.countQuery(RowScope.Everything, Predicate.eq(Books.SHELF, "a"), 10)

        val plan = database.plan(query)

        assertThat(database.dsl.renderInlined(query)).contains("fetch next 11 rows only")
        assertThat(plan.hasLimit()).describedAs("%s", plan).isTrue()
        assertThat(plan.json).describedAs("the limit node reads cap + 1 rows of the 25 that match").contains("\"Plan Rows\": 11")
        assertThat(plan.usesIndex("books_shelf_created_at_id")).describedAs("%s", plan).isTrue()
        assertThat(plan.scansSequentially("books")).isFalse()
    }
}
