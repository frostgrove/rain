package com.gd.rain.crud

import com.gd.rain.test.PlanVerdict
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Condition
import org.jooq.Query
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The plan evidence for the operators dialect v1 no longer has: `ne`, `nin`, `contains`, `icontains`,
 * `startswith`, `istartswith`, `endswith`, `iendswith` and `search`.
 *
 * Each condition is rendered as rain-crud rendered it before they were dropped, over a table carrying every
 * index that could serve it — b-trees, `text_pattern_ops` b-trees (plain and lower-cased), trigram GIN and
 * GiST indexes (plain and lower-cased), and a btree_gist index — and explained on PostgreSQL 18 for a page
 * in the identifier order, a page in the filtered field's order, and a capped count. Criterion v1 accepts
 * none of them: a b-tree keeps a pattern or an inequality as a Filter, a GIN index is read through a bitmap,
 * and a GiST index is not a b-tree and gives no order.
 */
@Tag("integration")
class DroppedOperatorsNeverBoundedIT {
    private val database =
        BookDatabase("crud_dropped_operators").also {
            it.jdbc.execute(
                """
                CREATE EXTENSION IF NOT EXISTS pg_trgm;
                CREATE EXTENSION IF NOT EXISTS btree_gist;
                CREATE INDEX books_title_pattern_id ON public.books (title text_pattern_ops, id);
                CREATE INDEX books_lower_title_pattern_id ON public.books (lower(title) text_pattern_ops, id);
                CREATE INDEX books_title_trgm_gin ON public.books USING gin (title gin_trgm_ops);
                CREATE INDEX books_lower_title_trgm_gin ON public.books USING gin (lower(title) gin_trgm_ops);
                CREATE INDEX books_title_trgm_gist ON public.books USING gist (title gist_trgm_ops);
                CREATE INDEX books_lower_title_trgm_gist ON public.books USING gist (lower(title) gist_trgm_ops);
                CREATE INDEX books_isbn_trgm_gist ON public.books USING gist (isbn gist_trgm_ops);
                CREATE INDEX books_title_id_gist ON public.books USING gist (title, id);
                """,
            )
        }

    private val table = DSL.table(DSL.name("public", "books"))
    private val id = DSL.field(DSL.name("id"), SQLDataType.UUID)
    private val title = DSL.field(DSL.name("title"), SQLDataType.VARCHAR)
    private val isbn = DSL.field(DSL.name("isbn"), SQLDataType.VARCHAR)

    private val dropped: Map<String, Condition> =
        linkedMapOf(
            "ne" to title.ne("dune"),
            "nin" to title.notIn("dune", "emma"),
            "contains" to title.contains("un"),
            "icontains" to title.containsIgnoreCase("un"),
            "startswith" to title.startsWith("du"),
            "istartswith" to title.startsWithIgnoreCase("du"),
            "endswith" to title.endsWith("ne"),
            "iendswith" to title.endsWithIgnoreCase("ne"),
            "search" to DSL.or(title.containsIgnoreCase("un"), isbn.containsIgnoreCase("un")),
        )

    private fun statements(condition: Condition): Map<String, Query> =
        linkedMapOf(
            "page by id" to
                database.dsl
                    .select(id)
                    .from(table)
                    .where(condition)
                    .orderBy(id.asc())
                    .limit(51),
            "page by title" to
                database.dsl
                    .select(id)
                    .from(table)
                    .where(condition)
                    .orderBy(title.asc(), id.asc())
                    .limit(51),
            "capped count" to
                database.dsl.select(DSL.count()).from(
                    database.dsl
                        .selectOne()
                        .from(table)
                        .where(condition)
                        .limit(51)
                        .asTable("c"),
                ),
        )

    @Test
    fun `no index makes a statement with a dropped operator bounded`() {
        dropped.forEach { (operator, condition) ->
            statements(condition).forEach { (statement, query) ->
                val plan = database.plan(query)

                assertThat(
                    plan.boundedScan("books"),
                ).describedAs("%s, %s: %s", operator, statement, plan).isInstanceOf(PlanVerdict.Unbounded::class.java)
            }
        }
    }

    @Test
    fun `the kept comparisons over the same table are bounded, so the refusals are the operators' own`() {
        val kept =
            linkedMapOf(
                "eq" to title.eq("dune"),
                "gte" to title.ge("du"),
                "in" to title.`in`("dune", "emma"),
            )

        kept.forEach { (operator, condition) ->
            val plan =
                database.plan(
                    database.dsl
                        .select(id)
                        .from(table)
                        .where(condition)
                        .orderBy(title.asc(), id.asc())
                        .limit(51),
                )

            assertThat(plan.boundedScan("books")).describedAs("%s: %s", operator, plan).isEqualTo(PlanVerdict.Bounded)
        }
    }
}
