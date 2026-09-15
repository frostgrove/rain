# rain-test

Test support for rain modules and rain applications: one PostgreSQL server per test JVM with a fresh database per
test, query plans judged by plan criterion v2 — which proves a statement bounded without depending on how many rows a
test inserted — and a clock a test moves by hand.

Add it to the test classpath of anything that tests against PostgreSQL or reads time.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    testImplementation("com.gd.rain:rain-test")
}
```

It exposes rain-boot, Spring Boot's test support, `spring-jdbc`, the JUnit Jupiter API, AssertJ, Testcontainers'
PostgreSQL module and the PostgreSQL driver. Tests that use `RainPostgres` need a Docker daemon and belong to the
integration tier ([conventions](../conventions.md#tests)).

## What it contributes

No auto-configuration and no beans. Everything is called from test code.

## API

### A database per test

| Member | Behaviour |
|---|---|
| `RainPostgres.IMAGE` | `postgres:18` |
| `RainPostgres.freshDatabase(prefix)` | starts the JVM's one container on first use, creates a database named `<prefix>_<n>` with `n` sequential within the JVM, and answers its coordinates; `prefix` matches `^[a-z][a-z0-9_]{0,40}$` |
| `RainDatabase.url`, `username`, `password` | the coordinates |
| `RainDatabase.dataSource()` | a non-pooled `PGSimpleDataSource` |
| `RainDatabase.springProperties()` | `spring.datasource.url`, `.username` and `.password` for an application context |

A failing run names the database it left behind, so its state can be inspected.

```kotlin
@Tag("integration")
class TicketStoreIT {
    private val database = RainPostgres.freshDatabase("tickets")

    @Test
    fun `a closed ticket stays closed`() {
        SpringApplicationBuilder(TicketApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                *(
                    database.springProperties() +
                        listOf(
                            "spring.application.name=tickets-it",
                            "rain.deployment.stage=test",
                            "rain.runtime.roles=api",
                            "rain.persistence.statement-timeout=30s",
                            "spring.flyway.enabled=true",
                        )
                ).toTypedArray(),
            ).run()
            .use { context ->
                // …
            }
    }
}
```

Every context a test starts states its stage and roles; there is no implicit test role
([ADR 0003](../adr/0003-roles-not-profiles.md)).

### Query plans

`QueryPlans.explain(dataSource, sql, generic = true)` answers the plan PostgreSQL chooses for a statement, read so that
boundedness can be judged from the plan alone. On one connection of `dataSource` it:

1. turns auto-commit off and applies `QueryPlans.SETTINGS` with `SET LOCAL`;
2. runs `EXPLAIN (FORMAT JSON, VERBOSE, GENERIC_PLAN)` for a statement with `$1`-style placeholders, or
   `EXPLAIN (FORMAT JSON, VERBOSE)` with `generic = false` for a statement with its values inlined (jOOQ's
   `renderInlined`);
3. describes, from the catalog, every index an index scan of the plan uses: its access method, its key columns in index
   order (`null` for an expression key) and whether it is unique (`pg_index.indisunique`);
4. rolls the transaction back and restores the connection's auto-commit, also when the statement cannot be explained.

A pooled connection is therefore handed back as it was lent (`ExplainLeavesPooledConnectionUnchangedIT`). `EXPLAIN`
without `ANALYZE` does not run the statement. `VERBOSE` is what reports each scan's schema and `Inner Unique` on joins.

| `QueryPlans.SETTINGS` | |
|---|---|
| `enable_seqscan = off` | criterion v2 never accepts a sequential scan |
| `enable_bitmapscan = off` | nor a bitmap scan |
| `enable_tidscan = off` | nor a TID scan |
| `max_parallel_workers_per_gather = 0` | no parallel plan |

PostgreSQL 18 prefers any plan with fewer disabled nodes over a cheaper plan with more, so the plan shows an index access
path whenever the indexes offer one, however few rows the test database holds. Sorts, hashed aggregates and joins stay
enabled: above a `Limit` they are part of bounded statements, such as a capped count or a delete of the ids a limited
subquery picked.

| `QueryPlan` member | Answers |
|---|---|
| `boundedScan(relation)` | the `PlanVerdict` of criterion v2 for the relation |
| `usesIndex(name)` | whether any node reads the named index |
| `scansSequentially(relation)` | whether any node is a `Seq Scan` of the relation |
| `hasLimit()` | whether the plan has a `Limit` node anywhere; `boundedScan` is the criterion for boundedness |
| `json`, `toString()` | the plan as PostgreSQL wrote it, for the assertion message |

| Type | What it is |
|---|---|
| `QueryPlan(json, indexes)` | one `EXPLAIN (FORMAT JSON, VERBOSE)` plan and the descriptions of the indexes it uses; the JSON is parsed on construction, and anything but a one-plan EXPLAIN document is an `IllegalArgumentException`, so a plan captured as a file can be judged too |
| `PlanIndex(schema, name, method, keyColumns, unique)` | an index as the catalog describes it; a partial unique index is unique among the rows its predicate admits |
| `PlanVerdict.Bounded` | every read of the relation is bounded |
| `PlanVerdict.Unbounded(reasons)` | at least one reason, for every read of the relation that fails |

`boundedScan` names a relation as the plan's `Relation Name` does: the table name, without its schema.

```kotlin
val plan = QueryPlans.explain(fixture.dataSource, fixture.dsl.renderInlined(query), generic = false)

assertThat(plan.usesIndex("ix_audit_log_resource")).describedAs(plan.json).isTrue()
assertThat(plan.boundedScan("audit_log")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
```

A statement whose every read of a table is bounded costs the same whether the table holds ten rows or ten million; that
is what the assertion proves, and why it needs no large fixture. rain-crud's [plan proof](crud.md#the-plan-proof) runs
every statement of every declared query shape of a resource through `QueryPlans.explain` and `boundedScan`.

### Plan criterion v2

Criterion **v2**: whether every row a plan reads from a relation is bounded, whatever the size of the relation — by the
statement's own `LIMIT` (and `OFFSET`), or by the uniqueness of the key it looks up.

The plan is `Bounded` when it reads the relation at all and every node that reads it (every node naming it as
`Relation Name`, except the `ModifyTable` a write targets) is a unique lookup (rule 0) or satisfies all of rules 1–5:

0. **Unique lookup.** An `Index Scan` or `Index Only Scan` of a unique b-tree index whose `Index Cond` has an `=` clause
   against one value (not `= ANY`, not `IS NULL`) on every key column reads at most one index entry each time it runs, so
   its `Filter` is evaluated on at most one row and no `Limit` is needed. It is bounded when it runs once (rule 5) or, as
   the `Inner` input of a `Nested Loop` — with or without `Inner Unique`, with or without a `Join Filter` — when that
   join's `Outer` input has a bounded output and the join runs once. A unique lookup also counts as a bounded output
   wherever rule 4 asks for one.
1. **Index scan.** It is an `Index Scan` or an `Index Only Scan`.
2. **No filter.** It carries no `Filter`: every condition is an `Index Cond`.
3. **Bounding conditions.** Its index is a b-tree described in the plan's `PlanIndex`es, and every clause of its
   `Index Cond` bounds the scanned range: a clause on key column *k* is preceded, on every key column before *k*, by an
   equality clause (`=`, `= ANY (…)` or `IS NULL`); a row comparison covers consecutive key columns. A clause on a later
   column alone would be checked against every entry of the range instead of ending it, so the entries visited would grow
   with the relation. A clause criterion v2 cannot read (an expression key, any other form) makes the scan unbounded.
4. **Limited or keyed.** Either
   - *limited*: walking up from the scan through its row input, the first node that is not `LockRows`, a `Subquery Scan`
     without `Filter` or a `Result` without `Filter` is a `Limit`. Every other node between them — `Sort`,
     `Incremental Sort`, `Hash`, `Materialize`, a bitmap or sequential scan, a join, `Append`, `Merge Append`, `Gather`,
     `Aggregate` — reorders or accumulates rows and makes the scan unbounded. (`LockRows` with `SKIP LOCKED` passes over
     rows other transactions hold.) Or
   - *keyed*: the scan is the `Inner` input of a `Nested Loop` (directly or through `Memoize`) that reports
     `Inner Unique`, carries no `Join Filter`, and whose `Outer` input has a bounded output — a `Limit`; a plain
     `Aggregate`; a `CTE Scan` of a bounded CTE; or a node that emits no more rows than its bounded input
     (`Subquery Scan`, `LockRows`, `Unique`, a grouped `Aggregate`, `Sort`, `Incremental Sort`, `Materialize`, `Memoize`,
     `Hash`, `Result`, `Gather`, `Gather Merge`, `WindowAgg`, `ModifyTable`, a semi or anti or inner-unique
     `Nested Loop`, an `Append` of bounded members).
5. **Executed once.** From that `Limit` (or that `Nested Loop`) up to the root, every edge is an `Outer`, `InitPlan`,
   `Subquery` or `Member` edge: nothing re-executes it once per row of another input.

Rows read are then at most the `LIMIT` plus `OFFSET` for a limited scan, and at most one per row of the bounded outer
input for a keyed scan. Nodes above the `Limit` are not constrained, so a capped count (`Aggregate` over `Limit`) and
`DELETE … WHERE id IN (SELECT … LIMIT n)` are bounded.

A plan that reads no relation at all — PostgreSQL proved the conditions contradictory and planned a constant-false
`Result` — reads no row of the relation and is bounded. A plan that reads other relations but not this one is unbounded
for it, so a misspelled relation (or a view, whose plan reads the tables under it) is never proven by default.

`Unbounded` lists every reason, for every read of the relation that fails. v2 adds rule 0 to v1; every plan v1 accepts,
v2 accepts.

A bounded page, over `books_shelf_created_at_id` (b-tree on `shelf, created_at, id`):

```json
[{"Plan": {"Node Type": "Limit", "Plans": [
  {"Node Type": "Index Only Scan", "Parent Relationship": "Outer", "Index Name": "books_shelf_created_at_id",
   "Relation Name": "books", "Schema": "public", "Alias": "books",
   "Index Cond": "((books.shelf = 'a'::text) AND (ROW(books.created_at, books.id) > ROW('2000-01-01 00:00:00+00'::timestamp with time zone, '00000000-0000-7000-8000-000000000001'::uuid)))"}
]}}]
```

An index-only scan (rule 1) without a `Filter` (rule 2); `shelf =` is an equality on the first key column and the row
comparison covers the two consecutive key columns after it (rule 3); its parent is the `Limit` (rule 4), which is the root
(rule 5).

A capped count over the same index:

```json
[{"Plan": {"Node Type": "Aggregate", "Strategy": "Plain", "Plans": [
  {"Node Type": "Limit", "Parent Relationship": "Outer", "Plans": [
    {"Node Type": "Index Only Scan", "Parent Relationship": "Outer", "Index Name": "books_shelf_created_at_id",
     "Relation Name": "books", "Schema": "public", "Alias": "books", "Index Cond": "(books.shelf = 'a'::text)"}
  ]}
]}}]
```

The `Aggregate` is above the `Limit`, where nothing is constrained; the scan satisfies rules 1–5.

A unique lookup: `SELECT id FROM t WHERE id = 1` over `CREATE TABLE t (id int PRIMARY KEY)`. The plan has no `Limit`,
but its scan reads the unique b-tree index of the primary key with `=` against one value on its only key column, so it
reads at most one entry and runs once (rule 0).

Plans the criterion refuses, with a reason it names (`QueryPlanBoundedScanTest`, over captured plans):

| Plan | Reason |
|---|---|
| a sequential scan | `Seq Scan on books is not an index scan` |
| a bitmap scan | `Bitmap Heap Scan on books is not an index scan` |
| an index scan with a `Filter` | `Index Scan using books_created_at_id on books carries a Filter: (books.pages >= 5)` |
| no `Limit` | `no Limit is above Index Scan using books_shelf_created_at_id on books` |
| a `Sort` below the `Limit` | `Sort is between Index Scan using books_shelf_created_at_id on books and any Limit above it` |
| a range on a later key column | `Index Cond clause (books.pages >= 5) of Index Only Scan using books_created_at_id_pages on books does not bound the scanned range of public.books_created_at_id_pages (btree on created_at, id, pages): key columns created_at, id before it have no equality condition` |
| a GiST index | `Index Scan using books_title_trgm on books reads a gist index; criterion v2 bounds b-tree scans only` |
| an expression key | `Index Scan using books_lower_title on books has an Index Cond criterion v2 cannot read: (lower(books.title) = 'dune'::text)` |
| a `Limit` in a SubPlan | `Limit runs once per row of Index Scan using shelves_pkey on shelves (a SubPlan input)` |
| a keyed scan of a non-unique index under a join that is not `Inner Unique` | `Index Scan using ix_job_intent_held_invocation on job_intent is the inner input of a Nested Loop that is not Inner Unique` |
| an index the plan carries no description of | `the plan carries no description of index public.books_shelf_created_at_id` |
| another relation only | `the plan reads no relation named authors` |

### Time

`MutableClock(now, zone = UTC)` is a `java.time.Clock` that moves only when a test moves it: `advance(by)` and
`set(to)`, both refusing to move backwards. Every rain module reads time from the application's `Clock` bean, so a
test replaces that bean.

## Configuration, error codes, health checks, schema, commands

None.

## What it does not do

- It starts no application context of its own and hides no roles or stage.
- It does not share a database between tests, migrate a template database or truncate tables.
- It offers no container for any service other than PostgreSQL.
- It judges a plan by its structure, never by its cost estimates, row estimates or timing.
