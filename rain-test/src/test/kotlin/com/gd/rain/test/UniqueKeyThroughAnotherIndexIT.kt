package com.gd.rain.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Criterion v3 against PostgreSQL 18: a lookup by primary key under a scope is planned over the scope's composite
 * index, which is not unique. `QueryPlans.explain` describes the unique indexes of every relation an index scan reads,
 * so the primary key still bounds the read (rule 0); a statement on `id = ANY` of an inline list reads at most that many
 * entries; and without a unique key the same statements are unbounded.
 */
@Tag("integration")
class UniqueKeyThroughAnotherIndexIT {
    private val dataSource =
        RainPostgres.freshDatabase("rain_test_unique_key").dataSource().also { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        """
                        CREATE TABLE public.keyed (id integer PRIMARY KEY, grp integer NOT NULL, note text);
                        CREATE INDEX keyed_grp_id ON public.keyed (grp, id);
                        CREATE TABLE public.unkeyed (id integer NOT NULL, grp integer NOT NULL, note text);
                        CREATE INDEX unkeyed_grp_id ON public.unkeyed (grp, id);
                        """,
                    )
                }
            }
        }

    @Test
    fun `a scoped lookup over another index is bounded by the primary key`() {
        val plan = QueryPlans.explain(dataSource, "SELECT note FROM public.keyed WHERE id = 7 AND grp = 1", generic = false)

        assertThat(plan.usesIndex("keyed_grp_id")).describedAs(plan.json).isTrue()
        assertThat(plan.boundedScan("public", "keyed")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `a scoped delete of an inline id list is bounded by the list's length`() {
        val plan = QueryPlans.explain(dataSource, "DELETE FROM public.keyed WHERE id IN (1, 2, 3) AND grp = 1", generic = false)

        assertThat(plan.json).describedAs(plan.json).contains("= ANY ('{1,2,3}'::integer[])")
        assertThat(plan.boundedScan("public", "keyed")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `without a unique key the same statements are unbounded`() {
        listOf(
            "SELECT note FROM public.unkeyed WHERE id = 7 AND grp = 1",
            "DELETE FROM public.unkeyed WHERE id IN (1, 2, 3) AND grp = 1",
        ).forEach { sql ->
            val plan = QueryPlans.explain(dataSource, sql, generic = false)

            assertThat(plan.boundedScan("public", "unkeyed")).describedAs("%s: %s", sql, plan.json).isInstanceOf(
                PlanVerdict.Unbounded::class.java,
            )
        }
    }
}
