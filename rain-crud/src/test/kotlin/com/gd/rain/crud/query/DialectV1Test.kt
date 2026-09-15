package com.gd.rain.crud.query

import com.gd.rain.crud.Books
import com.gd.rain.crud.faultOf
import com.gd.rain.crud.pointedCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate

/** Query dialect v1 against the books fixture: what compiles to what, and what is refused where. */
class DialectV1Test {
    private val compiler = QueryCompiler(Books.SCHEMA, Books.rules(includable = FieldGrant.only("reviews")), setOf("reviews"))

    private fun parameters(vararg parameters: Pair<String, String>): Map<String, List<String>> =
        parameters.groupBy({ it.first }, { it.second })

    private fun list(vararg parameters: Pair<String, String>): ListPlan = compiler.list(DialectV1.parse(parameters(*parameters)))

    private fun refusal(vararg parameters: Pair<String, String>) = faultOf { list(*parameters) }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(
        "title, eq, dune",
        "title, ne, dune",
        "pages, gt, 10",
        "pages, gte, 10",
        "createdAt, lt, 2026-09-15T10:00:00Z",
        "publishedOn, lte, 1965-08-01",
        "title, contains, un",
        "title, icontains, UN",
        "title, startswith, du",
        "title, istartswith, DU",
        "title, endswith, ne",
        "title, iendswith, NE",
        "price, isnull, true",
        "available, eq, false",
        "copies, gt, 5000000000",
        "price, gte, 12.50",
    )
    fun `every operator compiles on a field it applies to`(
        field: String,
        operator: String,
        value: String,
    ) {
        val compared = list("filter[$field][$operator]" to value).filter as Predicate.Compare

        assertThat(compared.field.name).isEqualTo(field)
        assertThat(compared.operator.wire).isEqualTo(operator)
    }

    @Test
    fun `values are carried as their field's kind`() {
        val plan = list("filter[price][gte]" to "12.50", "filter[publishedOn][eq]" to "1965-08-01", "filter[copies][lt]" to "5000000000")

        val values = (plan.filter as Predicate.AllOf).of.map { (it as Predicate.Compare).values.single() }
        assertThat(values).containsExactlyInAnyOrder(BigDecimal("12.50"), LocalDate.of(1965, 8, 1), 5_000_000_000L)
    }

    @Test
    fun `a list operator takes one value per repetition, and a comma is part of a value`() {
        val compared = list("filter[title][in]" to "a,b", "filter[title][in]" to "c").filter as Predicate.Compare

        assertThat(compared.values).containsExactly("a,b", "c")
    }

    @Test
    fun `a value that does not fit points at the value`() {
        assertThat(
            refusal("filter[pages][in]" to "1", "filter[pages][in]" to "two").pointedCodes(),
        ).containsExactly("/filter/pages/in/1 invalid_format")
        assertThat(refusal("filter[pages][eq]" to "two").pointedCodes()).containsExactly("/filter/pages/eq invalid_format")
    }

    @Test
    fun `a scalar operator given twice is refused, and so is a scalar parameter`() {
        assertThat(
            refusal("filter[title][eq]" to "a", "filter[title][eq]" to "b").pointedCodes(),
        ).containsExactly("/filter/title/eq bad_query")
        assertThat(refusal("limit" to "1", "limit" to "2").pointedCodes()).containsExactly("/limit bad_query")
    }

    @Test
    fun `an operator that does not apply to the field is refused`() {
        assertThat(
            refusal("filter[pages][contains]" to "1").violations.single().message,
        ).isEqualTo("contains applies to text fields; pages is INT")
        assertThat(
            refusal("filter[available][gt]" to "true").violations.single().message,
        ).isEqualTo("gt applies to ordered fields; available is BOOLEAN")
        assertThat(
            refusal("filter[title][isnull]" to "true").violations.single().message,
        ).isEqualTo("isnull applies to nullable fields; title is not nullable")
        assertThat(refusal("filter[price][isnull]" to "yes").pointedCodes()).containsExactly("/filter/price/isnull invalid_format")
    }

    @Test
    fun `a declared field that is not filterable is not granted`() {
        assertThat(
            refusal("filter[id][eq]" to "0192f1c0-0000-7000-8000-000000000001").pointedCodes(),
        ).containsExactly("/filter/id/eq field_not_granted")
    }

    @Test
    fun `in takes at most maxInValues values, and a query at most maxFilterTerms filters`() {
        val tight = QueryCompiler(Books.SCHEMA, Books.rules(limits = QueryLimits(maxInValues = 2, maxFilterTerms = 1)), emptySet())

        assertThat(faultOf { tight.list(DialectV1.parse(mapOf("filter[title][in]" to listOf("a", "b", "c")))) }.pointedCodes())
            .containsExactly("/filter/title/in out_of_range")
        assertThat(
            faultOf {
                tight.list(DialectV1.parse(mapOf("filter[title][eq]" to listOf("a"), "filter[pages][eq]" to listOf("1"))))
            }.pointedCodes(),
        ).containsExactly("/filter out_of_range")
    }

    @Test
    fun `a sort is name or -name terms, each once, each sortable`() {
        assertThat(
            list("sort" to "-createdAt").order.map { "${it.field.name} ${it.direction}" },
        ).containsExactly("createdAt DESC", "id DESC")
        assertThat(refusal("sort" to "+title").pointedCodes()).containsExactly("/sort bad_query")
        assertThat(refusal("sort" to "title,,pages").pointedCodes()).containsExactly("/sort bad_query")
        assertThat(refusal("sort" to "title,-title").pointedCodes()).containsExactly("/sort bad_query")
        assertThat(refusal("sort" to "shelf").pointedCodes()).containsExactly("/sort field_not_granted")
        assertThat(refusal("sort" to "nope").pointedCodes()).containsExactly("/sort unknown_field")
        assertThat(refusal("sort" to "title,pages,price,createdAt,-title2").pointedCodes()).containsExactly("/sort out_of_range")
    }

    @Test
    fun `the effective order ends with the identifier in the last term's direction`() {
        fun effective(sort: String) =
            compiler.effectiveOrder(if (sort.isEmpty()) SortKey.NONE else SortKey.parse(sort)).joinToString(",") {
                "${it.field.name}:${it.direction}"
            }

        assertThat(effective("")).isEqualTo("id:ASC")
        assertThat(effective("title")).isEqualTo("title:ASC,id:ASC")
        assertThat(effective("title,-pages")).isEqualTo("title:ASC,pages:DESC,id:DESC")
        assertThat(effective("-id,title")).isEqualTo("id:DESC,title:ASC")
    }

    @Test
    fun `no sort pages by cursor over the identifier, with the default limit`() {
        val plan = list()

        assertThat(plan.window).isInstanceOf(Window.Cursor::class.java)
        assertThat((plan.window as Window.Cursor).position).isNull()
        assertThat(plan.window.limit).isEqualTo(10)
        assertThat(plan.filter).isNull()
        assertThat(plan.projection).isEqualTo(Projection.Full)
        assertThat(plan.counted).isFalse()
    }

    @Test
    fun `include names declared, includable relations`() {
        assertThat(list("include" to "reviews").includes).containsExactly("reviews")
        assertThat(refusal("include" to "authors").pointedCodes()).containsExactly("/include unknown_field")
        assertThat(refusal("include" to "reviews,reviews").pointedCodes()).containsExactly("/include bad_query")
    }

    @Test
    fun `count is capped, and only where a cap is declared`() {
        assertThat(list("count" to "capped").counted).isTrue()
        assertThat(refusal("count" to "exact").pointedCodes()).containsExactly("/count invalid_format")
        val uncapped = QueryCompiler(Books.SCHEMA, Books.rules(countCap = null), emptySet())
        assertThat(
            faultOf {
                uncapped.list(DialectV1.parse(mapOf("count" to listOf("capped"))))
            }.pointedCodes(),
        ).containsExactly("/count not_offered")
    }

    @Test
    fun `search matches the declared search fields and is refused where there are none, or when empty or too long`() {
        val searched = list("search" to "Dune").filter as Predicate.Compare
        assertThat(searched.field).isEqualTo(Books.TITLE)
        assertThat(searched.operator).isEqualTo(Operator.ICONTAINS)
        assertThat(refusal("search" to "").pointedCodes()).containsExactly("/search bad_query")
        assertThat(refusal("search" to "x".repeat(201)).pointedCodes()).containsExactly("/search out_of_range")

        val rules = Books.rules()
        val unsearchable = QueryRules(rules.filterable, rules.sortable, rules.selectable, rules.includable, emptyList(), rules.pagination)
        assertThat(
            faultOf {
                QueryCompiler(Books.SCHEMA, unsearchable, emptySet()).list(DialectV1.parse(mapOf("search" to listOf("x"))))
            }.pointedCodes(),
        ).containsExactly("/search not_offered")
    }

    @Test
    fun `every problem of a query is refused together`() {
        val refused = refusal("limit" to "0", "sort" to "nope", "filter[available][gt]" to "true", "fields" to "isbn")

        assertThat(refused.code.value).isEqualTo("bad_query")
        assertThat(refused.pointedCodes()).containsExactlyInAnyOrder(
            "/limit out_of_range",
            "/sort unknown_field",
            "/filter/available/gt bad_query",
            "/fields field_not_granted",
        )
    }

    @Test
    fun `one item and a count refuse the parameters that mean nothing to them`() {
        assertThat(
            faultOf {
                compiler.item(DialectV1.parse(parameters("limit" to "1", "filter[title][eq]" to "x", "fields" to "title")))
            }.pointedCodes(),
        ).containsExactlyInAnyOrder("/limit bad_query", "/filter/title/eq bad_query")
        assertThat(faultOf { compiler.count(DialectV1.parse(parameters("sort" to "title", "cursor" to "x"))) }.pointedCodes())
            .containsExactlyInAnyOrder("/sort bad_query", "/cursor bad_query")
        assertThat(compiler.count(DialectV1.parse(parameters("count" to "capped", "filter[title][eq]" to "x"))).filter).isNotNull()
    }
}
