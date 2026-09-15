package com.gd.rain.crud.query

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.crud.Books
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.MemoryStore
import com.gd.rain.crud.PageWindow
import com.gd.rain.crud.SwitchableCallers
import com.gd.rain.crud.TestCaller
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * A cursor carries the boundary row's value of every sort field in the clear, so a caller who selected only `title`
 * could read `createdAt` out of the cursor. Every sort field of every declared shape must now be selectable: the values a
 * cursor shows are values the caller may select anyway.
 */
class SortFieldsMustBeSelectableTest {
    @Test
    fun `a shape sorted by a field selectable does not grant is a declaration problem`() {
        val rules =
            Books.rules(
                selectable = FieldGrant.only("title"),
                shapes = listOf(QueryShape.of(SortKey.parse("title")), QueryShape.of(SortKey.parse("-createdAt,title"))),
            )

        assertThat(rules.problems(Books.SCHEMA, emptySet()).map { it.path to it.message }).containsExactly(
            "crud:books.shapes" to
                "shape filters [], sort -createdAt,title sorts by createdAt, which selectable does not grant; a cursor carries the " +
                "value of every sort field",
        )
        assertThatThrownBy { CrudResource(rules, Books.policy(), MemoryStore(Books.SCHEMA), SwitchableCallers(), emptyList()) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
    }

    @Test
    fun `every value a cursor carries is one the caller may select`() {
        val store = MemoryStore(Books.SCHEMA)
        repeat(3) { store.add(book("t$it", "a", it, T0.plusSeconds(it.toLong()))) }
        val resource = CrudResource(Books.rules(), Books.policy(), store, SwitchableCallers(TestCaller.of("a", Books.READ)), emptyList())

        val page = resource.list(mapOf("sort" to listOf("-createdAt"), "limit" to listOf("1"), "fields" to listOf("title")))
        val cursor = String(Base64.getUrlDecoder().decode((page.window as PageWindow.Cursor).next))

        assertThat(cursor).contains("2026-09-15T10:00:02Z")
        assertThat(
            Books.SHAPES
                .flatMap { it.sort.terms }
                .map { it.field }
                .distinct(),
        ).allMatch { Books.rules().selectable.grants(it) }
        assertThat(resource.list(mapOf("fields" to listOf("createdAt"))).items).allMatch { "createdAt" in it }
    }
}
