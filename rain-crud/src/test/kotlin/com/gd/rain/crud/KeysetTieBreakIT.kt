package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Gap 25: the effective order ends with the identifier, so rows sharing every requested sort value still page once each. */
@Tag("integration")
class KeysetTieBreakIT {
    private val database = BookDatabase("crud_tiebreak")
    private val resource =
        CrudResource(Books.rules(), Books.policy(), database.store, SwitchableCallers(TestCaller.of("a", Books.READ)), emptyList())

    @Test
    fun `rows sharing the sort value page once each, ordered by the identifier in the sort's direction`() {
        repeat(20) { database.insert(book("same", "a", 1, T0)) }

        val newestFirst = resource.walk("-createdAt", 3, start = null) { it.next }
        val byTitle = resource.walk("title", 3, start = null) { it.next }

        assertThat(newestFirst).hasSize(7)
        assertThat(newestFirst.flatMap { it.ids() }).doesNotHaveDuplicates().isEqualTo(database.idsOrderedBy("created_at DESC, id DESC"))
        assertThat(byTitle.flatMap { it.ids() }).doesNotHaveDuplicates().isEqualTo(database.idsOrderedBy("title ASC, id ASC"))
    }

    @Test
    fun `a request without a sort pages by the identifier alone`() {
        repeat(8) { database.insert(book("t$it", "a", 1, T0)) }

        val pages = resource.walk("", 3, start = null) { it.next }.flatMap { it.ids() }

        assertThat(pages).isEqualTo(database.idsOrderedBy("id ASC"))
    }

    @Test
    fun `a row written between two pages with the same sort value is neither skipped nor repeated`() {
        repeat(10) { database.insert(book("same", "a", 1, T0)) }
        val first = resource.list(mapOf("sort" to listOf("title"), "limit" to listOf("4")))
        val original = database.idsOrderedBy("title ASC, id ASC")

        val late = database.insert(book("same", "a", 1, T0)).single()
        val rest = resource.walk("title", 4, start = first.cursor.next) { it.next }.flatMap { it.ids() }

        assertThat(first.ids()).isEqualTo(original.take(4))
        assertThat(rest).isEqualTo(database.idsOrderedBy("title ASC, id ASC").filter { it !in first.ids() })
        assertThat(rest).containsAll(original.drop(4)).contains(late).doesNotHaveDuplicates()
    }
}
