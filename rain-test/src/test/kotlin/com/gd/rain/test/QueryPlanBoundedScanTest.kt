package com.gd.rain.test

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Criterion v3 of [QueryPlan.boundedScan], over EXPLAIN (FORMAT JSON, VERBOSE) plans in `src/test/resources/plans`
 * (hand-written, or captured from PostgreSQL 18): the accepted forms, one plan per node kind that makes a read
 * unbounded, and the other ways a read fails.
 */
class QueryPlanBoundedScanTest {
    private val indexes =
        listOf(
            index("public", "books", "books_shelf_created_at_id", listOf("shelf", "created_at", "id")),
            index("public", "books", "books_shelf_id", listOf("shelf", "id")),
            index("public", "books", "books_created_at_id", listOf("created_at", "id")),
            index("public", "books", "books_created_at_id_pages", listOf("created_at", "id", "pages")),
            index("public", "books", "books_pkey", listOf("id"), unique = true),
            index("public", "shelves", "shelves_pkey", listOf("code"), unique = true),
            PlanIndex("public", "books", "books_title_trgm", "gist", listOf("title"), unique = false, partial = false),
            index("public", "books", "books_lower_title", listOf(null)),
            index("rain_jobs", "job_invocation", "ix_job_invocation_retention", listOf("profile", "finished_at")),
            index("rain_jobs", "job_invocation", "ix_job_invocation_lease", listOf("state", "lease_expires_at")),
            index("rain_jobs", "job_invocation", "ix_job_invocation_subject", listOf("subject_key", "state")),
            index("rain_jobs", "job_invocation", "job_invocation_pkey", listOf("id"), unique = true),
            index("rain_jobs", "job_intent", "uq_job_intent_held_invocation", listOf("invocation_id"), unique = true, partial = true),
            index("rain_jobs", "job_intent", "ix_job_intent_held_invocation", listOf("invocation_id", "released_at")),
        )

    private fun index(
        schema: String,
        table: String,
        name: String,
        keyColumns: List<String?>,
        unique: Boolean = false,
        partial: Boolean = false,
    ) = PlanIndex(schema, table, name, "btree", keyColumns, unique, partial)

    private fun replacing(vararg replacements: PlanIndex): List<PlanIndex> =
        indexes.map { index -> replacements.firstOrNull { it.name == index.name } ?: index }

    private fun plan(name: String): QueryPlan = QueryPlan(fixture(name), indexes)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/plans/$name.json")) { "no plan fixture $name" }.readText()

    private fun reasons(verdict: PlanVerdict): List<String> = (verdict as PlanVerdict.Unbounded).reasons

    @ParameterizedTest(name = "{0} reads {1} boundedly")
    @CsvSource(
        "page, books",
        "offset-page, books",
        "capped-count, books",
        "delete-limited-ids, job_invocation",
        "locked-page, job_invocation",
        "cte-keyed-update, job_invocation",
    )
    fun `the accepted forms are bounded`(
        fixture: String,
        relation: String,
    ) {
        val plan = plan(fixture)

        assertThat(plan.boundedScan(relation)).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "sort-below-limit | Sort is between Index Scan using books_shelf_created_at_id on books and any Limit above it",
            "incremental-sort-below-limit | Incremental Sort is between Index Scan using books_shelf_created_at_id on books",
            "hash-below-limit | Hash is between Index Scan using books_shelf_created_at_id on books",
            "materialize-below-limit | Materialize is between Index Scan using books_shelf_created_at_id on books",
            "gather-below-limit | Gather is between Index Scan using books_shelf_created_at_id on books",
            "aggregate-below-limit | Aggregate is between Index Scan using books_shelf_created_at_id on books",
            "append-below-limit | Index Scan using books_shelf_created_at_id on books is a Member input of Append, not under a Limit",
            "merge-append-below-limit | is a Member input of Merge Append, not under a Limit",
            "bitmap-scan | Bitmap Heap Scan on books is not an index scan",
            "seq-scan | Seq Scan on books is not an index scan",
            "hash-join-below-limit | Hash Join is between Index Scan using books_shelf_created_at_id on books",
            "merge-join-below-limit | Merge Join is between Index Scan using books_shelf_created_at_id on books",
            "nested-loop-below-limit | Nested Loop is between Index Scan using books_shelf_created_at_id on books",
        ],
    )
    fun `a node that reorders or accumulates rows below the Limit makes the read unbounded`(
        fixture: String,
        reason: String,
    ) {
        val verdict = plan(fixture).boundedScan("books")

        assertThat(reasons(verdict)).anyMatch { it.contains(reason) }
    }

    @Test
    fun `every failing read is named, not only the first`() {
        val verdict = plan("append-below-limit").boundedScan("books")

        assertThat(reasons(verdict)).hasSize(2)
        assertThat(reasons(verdict)[1]).contains("(books_1)")
    }

    @Test
    fun `a scan carrying a Filter is unbounded`() {
        assertThat(reasons(plan("filter").boundedScan("books")))
            .containsExactly("Index Scan using books_created_at_id on books carries a Filter: (books.pages >= 5)")
    }

    @Test
    fun `a scan without a Limit above it is unbounded`() {
        assertThat(reasons(plan("no-limit").boundedScan("books")))
            .containsExactly("no Limit is above Index Scan using books_shelf_created_at_id on books")
    }

    @Test
    fun `an Index Cond on a key column after one without an equality does not bound the range`() {
        assertThat(reasons(plan("unbounding-index-cond").boundedScan("books"))).containsExactly(
            "Index Cond clause (books.pages >= 5) of Index Only Scan using books_created_at_id_pages on books does not bound the " +
                "scanned range of public.books_created_at_id_pages (btree on created_at, id, pages): key columns created_at, id " +
                "before it have no equality condition",
        )
    }

    @Test
    fun `only b-tree scans are bounded`() {
        assertThat(reasons(plan("gist-count").boundedScan("books")))
            .containsExactly("Index Scan using books_title_trgm on books reads a gist index; criterion v3 bounds b-tree scans only")
    }

    @Test
    fun `an Index Cond the criterion cannot read is unbounded, never guessed`() {
        assertThat(reasons(plan("expression-index-cond").boundedScan("books")))
            .containsExactly(
                "Index Scan using books_lower_title on books has an Index Cond criterion v3 cannot read: (lower(books.title) = 'dune'::text)",
            )
    }

    @Test
    fun `a keyed lookup without Inner Unique is unbounded`() {
        val plan = plan("keyed-not-unique")

        assertThat(reasons(plan.boundedScan("job_intent"))).containsExactly(
            "Index Scan using ix_job_intent_held_invocation on job_intent is the inner input of a Nested Loop that is not Inner Unique",
        )
        assertThat(plan.boundedScan("job_invocation")).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `a keyed lookup under a Join Filter, or driven by an unbounded input, is unbounded`() {
        val notUnique = replacing(index("public", "books", "books_pkey", listOf("id")))

        assertThat(reasons(QueryPlan(fixture("keyed-join-filter"), notUnique).boundedScan("books"))).containsExactly(
            "Nested Loop above Index Scan using books_pkey on books carries a Join Filter: (books.shelf = \"ANY_subquery\".shelf)",
            "the output of Seq Scan on shelves (ANY_subquery) is not bounded",
        )
        // As a unique lookup it reads one row per outer row, so only the unbounded driver remains.
        assertThat(reasons(plan("keyed-join-filter").boundedScan("books")))
            .containsExactly("the output of Seq Scan on shelves (ANY_subquery) is not bounded")
    }

    @Test
    fun `a Limit re-executed once per row of another input bounds nothing`() {
        assertThat(reasons(plan("subplan").boundedScan("books"))).containsExactly(
            "Limit runs once per row of Index Scan using shelves_pkey on shelves (a SubPlan input)",
        )
    }

    @Test
    fun `an index the plan carries no description of is unbounded`() {
        val undescribed = QueryPlan(fixture("page"), emptyList())

        assertThat(reasons(undescribed.boundedScan("books")))
            .containsExactly("the plan carries no description of index public.books_shelf_created_at_id")
    }

    @Test
    fun `a plan that reads other relations but not this one is not bounded for it`() {
        assertThat(reasons(plan("page").boundedScan("authors"))).containsExactly("the plan reads no relation named authors")
    }

    @Test
    fun `a plan that reads no relation at all reads no row of any`() {
        assertThat(plan("constant-false").boundedScan("books")).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `the structural questions read the parsed plan`() {
        val page = plan("page")
        val seq = plan("seq-scan")

        assertThat(page.usesIndex("books_shelf_created_at_id")).isTrue()
        assertThat(page.usesIndex("books_shelf")).isFalse()
        assertThat(page.hasLimit()).isTrue()
        assertThat(plan("no-limit").hasLimit()).isFalse()
        assertThat(seq.scansSequentially("books")).isTrue()
        assertThat(page.scansSequentially("books")).isFalse()
        assertThat(page.indexReferences()).containsExactly("public" to "books_shelf_created_at_id")
    }

    @Test
    fun `anything but a one-plan EXPLAIN document is refused`() {
        listOf("not json", "{}", "[]", """[{"Plan": {}}]""", """[{"Plan": {"Node Type": "Limit"}}, {"Plan": {"Node Type": "Limit"}}]""")
            .forEach { json ->
                assertThatThrownBy { QueryPlan(json, emptyList()) }.describedAs(json).isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @ParameterizedTest(name = "{0} reads {1} boundedly")
    @CsvSource(
        "unique-lookup-terminal, job_invocation",
        "unique-lookup-terminal, job_intent",
        "unique-lookup-release, job_intent",
        "unique-lookup-release, job_invocation",
    )
    fun `a unique lookup reads at most one entry each time it runs, whatever its Filter and the join above it`(
        fixture: String,
        relation: String,
    ) {
        val plan = plan(fixture)

        assertThat(plan.boundedScan(relation)).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `a lookup is unique only on a unique index with a single-value equality on every key column`() {
        fun describedAs(index: PlanIndex) = QueryPlan(fixture("unique-lookup-release"), replacing(index))

        assertThat(
            reasons(
                describedAs(
                    index("rain_jobs", "job_intent", "uq_job_intent_held_invocation", listOf("invocation_id"), partial = true),
                ).boundedScan("job_intent"),
            ),
        ).containsExactly(
            "Index Scan using uq_job_intent_held_invocation on job_intent carries a Filter: (job_intent.released_at IS NULL)",
            "Index Scan using uq_job_intent_held_invocation on job_intent is the inner input of a Nested Loop that is not Inner Unique",
        )
        assertThat(
            describedAs(
                index("rain_jobs", "job_intent", "uq_job_intent_held_invocation", listOf("invocation_id", "definition"), unique = true),
            ).boundedScan("job_intent"),
        ).isInstanceOf(PlanVerdict.Unbounded::class.java)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "unique-key-scoped-find",
        "unique-key-scoped-update",
    )
    fun `a scan of another index whose bounding conditions pin a non-partial unique key reads at most one entry`(fixture: String) {
        val plan = plan(fixture)

        assertThat(plan.usesIndex("books_shelf_id")).isTrue()
        assertThat(plan.boundedScan("public", "books")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `a unique key pins another index's scan only when the unique index is described, unique and holds every row`() {
        val scoped = fixture("unique-key-scoped-find")
        val refusal = "no Limit is above Index Scan using books_shelf_id on books"

        assertThat(reasons(QueryPlan(scoped, indexes.filterNot { it.name == "books_pkey" }).boundedScan("public", "books")))
            .containsExactly(refusal)
        assertThat(
            reasons(QueryPlan(scoped, replacing(index("public", "books", "books_pkey", listOf("id")))).boundedScan("public", "books")),
        ).containsExactly(refusal)
        assertThat(
            reasons(
                QueryPlan(scoped, replacing(index("public", "books", "books_pkey", listOf("id"), unique = true, partial = true)))
                    .boundedScan("public", "books"),
            ),
        ).containsExactly(refusal)
        assertThat(
            reasons(
                QueryPlan(scoped, replacing(index("public", "shelves", "books_pkey", listOf("id"), unique = true)))
                    .boundedScan("public", "books"),
            ),
        ).containsExactly(refusal)
    }

    @Test
    fun `a pinned unique key does not bound a scan whose conditions do not bound its own range`() {
        assertThat(reasons(plan("unique-key-unbounding-pin").boundedScan("public", "books"))).containsExactly(
            "Index Cond clause (books.id = '00000000-0000-7000-8000-000000000001'::uuid) of Index Scan using " +
                "books_shelf_created_at_id on books does not bound the scanned range of public.books_shelf_created_at_id " +
                "(btree on shelf, created_at, id): key column created_at before it has no equality condition",
            "no Limit is above Index Scan using books_shelf_created_at_id on books",
        )
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "unique-any-literal",
        "unique-any-literal-scoped",
    )
    fun `a one-column unique key compared by = ANY with an inline literal of k elements reads at most k entries`(fixture: String) {
        val plan = plan(fixture)

        assertThat(plan.boundedScan("public", "books")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `= ANY over a generic plan's parameter states no k and bounds nothing`() {
        assertThat(reasons(plan("unique-any-generic").boundedScan("public", "books")))
            .containsExactly("ModifyTable on books is between Index Scan using books_pkey on books and any Limit above it")
    }

    @Test
    fun `= ANY pins only a one-column unique key`() {
        val twoColumns =
            replacing(
                index("public", "books", "books_shelf_id", listOf("shelf", "id"), unique = true),
                index("public", "books", "books_pkey", listOf("id")),
            )

        assertThat(reasons(QueryPlan(fixture("unique-any-literal-scoped"), twoColumns).boundedScan("public", "books")))
            .containsExactly("ModifyTable on books is between Index Scan using books_shelf_id on books and any Limit above it")
    }

    @Test
    fun `the version of the criterion is stated`() {
        assertThat(QueryPlan.CRITERION_VERSION).isEqualTo(3)
    }
}
