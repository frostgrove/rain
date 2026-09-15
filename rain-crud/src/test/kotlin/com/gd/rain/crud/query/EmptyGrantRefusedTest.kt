package com.gd.rain.crud.query

import com.gd.rain.crud.Books
import com.gd.rain.crud.faultOf
import com.gd.rain.crud.pointedCodes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Gap 28: an empty allow-list used to grant every field; now a grant is None, All or a non-empty Only. */
class EmptyGrantRefusedTest {
    @Test
    fun `an Only grant naming nothing is refused where it is written`() {
        assertThatThrownBy { FieldGrant.Only(emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("grant nothing with FieldGrant.None")
        assertThatThrownBy { FieldGrant.only() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `None grants nothing, All grants every name, Only grants exactly its names`() {
        assertThat(FieldGrant.None.grants("title")).isFalse()
        assertThat(FieldGrant.All.grants("title")).isTrue()
        assertThat(FieldGrant.only("title").grants("title")).isTrue()
        assertThat(FieldGrant.only("title").grants("Title")).isFalse()
        assertThat(FieldGrant.only("title").grants("pages")).isFalse()
    }

    @Test
    fun `a grant naming a field the schema does not declare is a declaration problem`() {
        val rules =
            QueryRules(
                filterable = FieldGrant.only("title", "nope"),
                sortable = FieldGrant.None,
                selectable = FieldGrant.All,
                includable = FieldGrant.None,
                searchFields = emptyList(),
                pagination = Pagination(10, 50, 100, setOf(SortKey.NONE), null),
            )

        assertThat(rules.problems(Books.SCHEMA, emptySet()).map { it.path to it.message })
            .containsExactly("crud:books.filterable" to "grants nope, which is not a field")
    }

    @Test
    fun `a resource that grants None refuses every use of that kind`() {
        val rules =
            QueryRules(
                filterable = FieldGrant.None,
                sortable = FieldGrant.None,
                selectable = FieldGrant.None,
                includable = FieldGrant.None,
                searchFields = emptyList(),
                pagination = Pagination(10, 50, 100, setOf(SortKey.NONE), null),
            )
        val compiler = QueryCompiler(Books.SCHEMA, rules, emptySet())

        val refused =
            faultOf {
                compiler.list(
                    DialectV1.parse(mapOf("fields" to listOf("title"), "sort" to listOf("title"), "filter[title][eq]" to listOf("dune"))),
                )
            }

        assertThat(refused.pointedCodes()).containsExactly(
            "/filter/title/eq field_not_granted",
            "/sort field_not_granted",
            "/fields field_not_granted",
        )
    }
}
