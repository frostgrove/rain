package com.gd.rain.test

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * `boundedScan` used to match a relation by its bare name, so a plan reading `public.books` and `archive.books` judged
 * both reads under one name. A relation is now named with its schema, as the VERBOSE plan reports it; a bare name is
 * accepted only when every read of that name is in one schema, and refused as ambiguous otherwise.
 */
class AmbiguousRelationNameRefusedTest {
    private val indexes =
        listOf(
            PlanIndex("public", "books", "books_pkey", "btree", listOf("id"), unique = true, partial = false),
            PlanIndex("archive", "books", "books_pkey", "btree", listOf("id"), unique = true, partial = false),
            PlanIndex("public", "books", "books_shelf_created_at_id", "btree", listOf("shelf", "created_at", "id"), false, false),
        )

    private fun plan(name: String): QueryPlan =
        QueryPlan(checkNotNull(javaClass.getResource("/plans/$name.json")) { "no plan fixture $name" }.readText(), indexes)

    @Test
    fun `a bare name read in two schemas is refused, naming both`() {
        assertThatThrownBy { plan("relation-in-two-schemas").boundedScan("books") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "the plan reads relations named books in 2 schemas (public, archive); name the one meant with boundedScan(schema, relation)",
            )
    }

    @Test
    fun `each schema-qualified relation is judged by its own reads`() {
        val plan = plan("relation-in-two-schemas")

        assertThat(plan.boundedScan("public", "books")).isEqualTo(PlanVerdict.Bounded)
        assertThat(plan.boundedScan("archive", "books")).isEqualTo(PlanVerdict.Bounded)
        assertThat((plan.boundedScan("other", "books") as PlanVerdict.Unbounded).reasons)
            .containsExactly("the plan reads no relation named other.books")
    }

    @Test
    fun `a qualified read is not judged by an index of the same name in another schema`() {
        val archiveOnly = indexes.filterNot { it.schema == "public" && it.name == "books_pkey" }
        val plan = QueryPlan(checkNotNull(javaClass.getResource("/plans/relation-in-two-schemas.json")).readText(), archiveOnly)

        assertThat(plan.boundedScan("archive", "books")).isEqualTo(PlanVerdict.Bounded)
        assertThat(plan.boundedScan("public", "books")).isInstanceOf(PlanVerdict.Unbounded::class.java)
    }

    @Test
    fun `a bare name read in one schema is still accepted`() {
        assertThat(plan("page").boundedScan("books")).isEqualTo(PlanVerdict.Bounded)
        assertThat(plan("page").boundedScan("public", "books")).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `a dotted name is not a bare name`() {
        assertThatThrownBy { plan("page").boundedScan("public.books") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("boundedScan(schema, relation)")
    }

    @Test
    fun `a plan without schemas names none, so a qualified relation asks for VERBOSE`() {
        val terse = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "books", "Alias": "books"}}]"""

        assertThat((QueryPlan(terse, emptyList()).boundedScan("public", "books") as PlanVerdict.Unbounded).reasons)
            .containsExactly("the plan names no schema for the relation books it reads; explain it with VERBOSE")
        assertThat(QueryPlan("""[{"Plan": {"Node Type": "Result"}}]""", emptyList()).boundedScan("public", "books"))
            .isEqualTo(PlanVerdict.Bounded)
    }
}
