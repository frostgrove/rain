package com.gd.rain.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How criterion v3 reads PostgreSQL's deparsed `Index Cond` text. */
class IndexConditionsTest {
    private fun read(condition: String): List<String>? =
        IndexConditions.parse(condition)?.map { "${it.columns.joinToString(",")} ${it.kind}" }

    @Test
    fun `a single clause names its column and whether it is an equality`() {
        assertThat(read("(books.shelf = 'a'::text)")).containsExactly("shelf EQUALITY")
        assertThat(read("(shelf >= 5)")).containsExactly("shelf RANGE")
        assertThat(read("(books.shelf = ANY ('{a,b}'::text[]))")).containsExactly("shelf EQUALITY")
        assertThat(read("(books.price IS NULL)")).containsExactly("price EQUALITY")
        assertThat(read("(books.price IS NOT NULL)")).containsExactly("price RANGE")
        assertThat(read("(job_invocation_1.id = \"ANY_subquery\".id)")).containsExactly("id EQUALITY")
    }

    @Test
    fun `a conjunction splits into its clauses, row comparisons included`() {
        assertThat(
            read(
                "((books.shelf = 'a'::text) AND (ROW(books.created_at, books.id) > " +
                    "ROW('2000-01-01 00:00:00+00'::timestamp with time zone, '00000000-0000-7000-8000-000000000001'::uuid)))",
            ),
        ).containsExactly("shelf EQUALITY", "created_at,id RANGE")
        assertThat(read("((title ~>=~ 'du'::text) AND (title ~<~ 'dv'::text))")).containsExactly("title RANGE", "title RANGE")
    }

    @Test
    fun `quoted identifiers and text holding the syntax do not confuse the reading`() {
        assertThat(read("((books.\"Weird \"\"Col\" = 3) AND (books.id > '00000000-0000-7000-8000-000000000001'::uuid))"))
            .containsExactly("Weird \"Col EQUALITY", "id RANGE")
        assertThat(read("((books.title = 'a) AND (b'::text) AND (books.isbn = 'it''s ( a'::text))"))
            .containsExactly("title EQUALITY", "isbn EQUALITY")
    }

    @Test
    fun `anything else is not read`() {
        listOf(
            "(lower(books.title) = 'dune'::text)",
            "((books.title)::text = 'dune'::text)",
            "(books.title = 'unterminated)",
            "books.title = 'x'::text",
            "((books.title = 'x'::text) AND books.id)",
            "(books.title)",
            "(3 = books.pages)",
        ).forEach { condition -> assertThat(read(condition)).describedAs(condition).isNull() }
    }

    @Test
    fun `only an equality against one value is single-valued`() {
        assertThat(checkNotNull(IndexConditions.parse("(books.id = 'x'::uuid)")).single().singleValue).isTrue()
        assertThat(checkNotNull(IndexConditions.parse("(books.shelf = ANY ('{a,b}'::text[]))")).single().singleValue).isFalse()
        assertThat(checkNotNull(IndexConditions.parse("(books.shelf = ANY ('{a}'::text[]))")).single().singleValue).isFalse()
        assertThat(checkNotNull(IndexConditions.parse("(books.isbn IS NULL)")).single().singleValue).isFalse()
        assertThat(checkNotNull(IndexConditions.parse("(books.pages >= 5)")).single().singleValue).isFalse()
    }

    private fun values(condition: String): Int? = checkNotNull(IndexConditions.parse(condition)).single().values

    @Test
    fun `= ANY over an inline array literal equates the column with as many values as the literal has elements`() {
        assertThat(values("(books.id = 'x'::uuid)")).isEqualTo(1)
        assertThat(values("(books.shelf = ANY ('{a,b,c}'::text[]))")).isEqualTo(3)
        assertThat(values("(books.shelf = ANY ('{}'::text[]))")).isZero()
        assertThat(values("(books.shelf = ANY ('{\"a,b\",\"c}d\",NULL}'::text[]))")).isEqualTo(3)
        assertThat(values("(books.shelf = ANY ('{\"a\\\"b\",c}'::text[]))")).isEqualTo(2)
        assertThat(values("(books.shelf = ANY ('{it''s,b}'::text[]))")).isEqualTo(2)
        assertThat(values("(books.title = ANY ('{x,y}'::character varying[]))")).isEqualTo(2)
    }

    @Test
    fun `= ANY over anything but a one-dimensional inline literal states no number of values`() {
        listOf(
            "(books.id = ANY (\$1))",
            "(books.id = ANY (ARRAY[books.a, books.b]))",
            "(books.pages = ANY ('{{1,2},{3,4}}'::integer[]))",
            "(books.pages = ANY ('[1:2]={1,2}'::integer[]))",
            "(books.shelf = ANY ('{\"a}'::text[]))",
            "(books.shelf = ANY ('{a\\'::text[]))",
            "(books.shelf = ANY ('{a,b}'::text))",
            "(books.shelf = ANY ('{a,b}'))",
            "(books.shelf = ANY ('a,b'::text[]))",
            "(books.pages >= 5)",
            "(books.price IS NULL)",
        ).forEach { condition -> assertThat(values(condition)).describedAs(condition).isNull() }
    }
}
