package com.gd.rain.crud.query

import com.gd.rain.crud.Books
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Gap 29: the number of search fields used to be bounded by `maxSort`; now it has its own `maxSearchFields`. */
class SearchFieldsBoundIndependentOfSortTest {
    private fun rules(
        searchFields: List<String>,
        limits: QueryLimits,
    ) = QueryRules(
        filterable = FieldGrant.None,
        sortable = FieldGrant.only("title"),
        selectable = FieldGrant.None,
        includable = FieldGrant.None,
        searchFields = searchFields,
        pagination = Pagination(10, 50, 100, setOf(SortKey.NONE), null),
        limits = limits,
    )

    @Test
    fun `more search fields than sort terms are accepted within maxSearchFields`() {
        val rules = rules(listOf("title", "shelf", "isbn"), QueryLimits(maxSortTerms = 1, maxSearchFields = 3))

        assertThat(rules.problems(Books.SCHEMA, emptySet())).isEmpty()
    }

    @Test
    fun `more search fields than maxSearchFields are refused, however many sort terms are allowed`() {
        val rules = rules(listOf("title", "shelf", "isbn"), QueryLimits(maxSortTerms = 16, maxSearchFields = 2))

        assertThat(rules.problems(Books.SCHEMA, emptySet()).map { it.message })
            .containsExactly("declares 3 fields, more than maxSearchFields (2)")
    }

    @Test
    fun `a search over every declared field compiles when one sort term is the bound`() {
        val compiler =
            QueryCompiler(
                Books.SCHEMA,
                rules(listOf("title", "shelf", "isbn"), QueryLimits(maxSortTerms = 1, maxSearchFields = 3)),
                emptySet(),
            )

        val plan = compiler.list(DialectV1.parse(mapOf("search" to listOf("dune"), "sort" to listOf("title"), "offset" to listOf("0"))))

        val searched = plan.filter as Predicate.AnyOf
        assertThat(searched.of.map { (it as Predicate.Compare).field.name }).containsExactly("title", "shelf", "isbn")
        assertThat(searched.of.map { (it as Predicate.Compare).operator }).containsOnly(Operator.ICONTAINS)
    }

    @Test
    fun `search fields are declared text fields, named once`() {
        val rules = rules(listOf("pages", "nope", "title", "title"), QueryLimits())

        assertThat(rules.problems(Books.SCHEMA, emptySet()).map { it.message }).containsExactly(
            "names title more than once",
            "names pages, which is a INT field, not TEXT",
            "names nope, which is not a field",
        )
    }
}
