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
    /** One shape per operator the tests compile, on top of the fixture's shapes. */
    private val operatorShapes: List<QueryShape> =
        listOf(
            QueryShape.of(SortKey.NONE, "pages" to Operator.GT),
            QueryShape.of(SortKey.NONE, "pages" to Operator.GTE),
            QueryShape.of(SortKey.NONE, "createdAt" to Operator.LT),
            QueryShape.of(SortKey.NONE, "publishedOn" to Operator.LTE),
            QueryShape.of(SortKey.NONE, "available" to Operator.EQ),
            QueryShape.of(SortKey.NONE, "copies" to Operator.GT),
            QueryShape.of(SortKey.NONE, "price" to Operator.GTE),
            QueryShape.of(SortKey.NONE, "title" to Operator.IN),
            QueryShape.of(SortKey.NONE, "price" to Operator.GTE, "publishedOn" to Operator.EQ, "copies" to Operator.LT),
        )

    private val compiler =
        QueryCompiler(
            Books.SCHEMA,
            Books.rules(includable = FieldGrant.only("reviews"), shapes = Books.SHAPES + operatorShapes),
            setOf("reviews"),
        )

    private fun parameters(vararg parameters: Pair<String, String>): Map<String, List<String>> =
        parameters.groupBy({ it.first }, { it.second })

    private fun list(vararg parameters: Pair<String, String>): ListPlan = compiler.list(DialectV1.parse(parameters(*parameters)))

    private fun refusal(vararg parameters: Pair<String, String>) = faultOf { list(*parameters) }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(
        "title, eq, dune",
        "pages, gt, 10",
        "pages, gte, 10",
        "createdAt, lt, 2026-09-15T10:00:00Z",
        "publishedOn, lte, 1965-08-01",
        "price, isnull, true",
        "available, eq, false",
        "copies, gt, 5000000000",
        "price, gte, 12.50",
        "title, in, dune",
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
    fun `the dialect has exactly the operators an index can bound`() {
        assertThat(Operator.entries.map(Operator::wire)).containsExactly("eq", "gt", "gte", "lt", "lte", "in", "isnull")
        listOf("ne", "nin", "contains", "icontains", "startswith", "istartswith", "endswith", "iendswith").forEach { wire ->
            assertThat(
                refusal("filter[title][$wire]" to "dune").pointedCodes(),
            ).describedAs(wire).containsExactly("/filter/title/$wire unknown_operator")
        }
    }

    @Test
    fun `search is not a parameter of the dialect`() {
        val refused = refusal("search" to "dune")

        assertThat(refused.code.value).isEqualTo("unknown_parameter")
        assertThat(refused.pointedCodes()).containsExactly("/search unknown_parameter")
    }

    @Test
    fun `values are carried as their field's kind, conjoined in filter order`() {
        val plan = list("filter[price][gte]" to "12.50", "filter[publishedOn][eq]" to "1965-08-01", "filter[copies][lt]" to "5000000000")

        val compared = (plan.filter as Predicate.AllOf).of.map { it as Predicate.Compare }
        assertThat(compared.map { it.field.name }).containsExactly("copies", "price", "publishedOn")
        assertThat(compared.map { it.values.single() }).containsExactly(5_000_000_000L, BigDecimal("12.50"), LocalDate.of(1965, 8, 1))
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
            refusal("filter[available][gt]" to "true").violations.single().message,
        ).isEqualTo("gt applies to ordered fields; available is BOOLEAN")
        assertThat(
            refusal("filter[title][isnull]" to "true").violations.single().message,
        ).isEqualTo("isnull applies to nullable fields; title is not nullable")
        assertThat(refusal("filter[price][isnull]" to "yes").pointedCodes()).containsExactly("/filter/price/isnull invalid_format")
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
    fun `a sort is name or -name terms, each once, each a field`() {
        assertThat(
            list("sort" to "-createdAt").order.map { "${it.field.name} ${it.direction}" },
        ).containsExactly("createdAt DESC", "id DESC")
        assertThat(refusal("sort" to "+title").pointedCodes()).containsExactly("/sort bad_query")
        assertThat(refusal("sort" to "title,,pages").pointedCodes()).containsExactly("/sort bad_query")
        assertThat(refusal("sort" to "title,-title").pointedCodes()).containsExactly("/sort bad_query")
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

        assertThat(plan.shape).isEqualTo(QueryShape.of(SortKey.NONE))
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
