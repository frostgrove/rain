# rain-test

Test support for rain modules and rain applications: one PostgreSQL server per test JVM with a fresh database per
test, query plans judged by plan criterion v3 — which proves a statement bounded without depending on how many rows a
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
        val stated =
            database.springProperties() +
                listOf(
                    "rain.deployment.stage=test",
                    "rain.runtime.roles=api",
                    "rain.persistence.statement-timeout=30s",
                    "spring.flyway.enabled=true",
                )

        SpringApplicationBuilder(TicketApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(*stated.map { "--$it" }.toTypedArray())
            .use { context ->
                // …
            }
    }
}
```

Every context a test starts states its stage and roles; there is no implicit test role
([ADR 0003](../adr/0003-roles-not-profiles.md)).

A test states the properties of its process as command-line arguments to `run`, `--name=value`, which outrank
`application.yml` the way a deployment's environment does. `SpringApplicationBuilder.properties(...)` does not: it sets
Spring Boot's default properties, the source every other one outranks, so a value the application's `application.yml`
states wins over it — an application whose file says `spring.flyway.enabled: false` would start without migrating. A JVM
system property (`-D`, or a Gradle test task's `systemProperty`) outranks the file too, but rain judges its keys as it
judges a file's: a key under `rain.` that no section declares is refused as `unknown_key`; only keys from environment
variables are not judged ([rain-boot](boot.md#environment-variable-names)). `samples/rain-sample` starts every process of
its integration tests this way (`Stand.arguments`).

### Query plans

`QueryPlans.explain(dataSource, sql, generic = true)` answers the plan PostgreSQL chooses for a statement, read so that
boundedness can be judged from the plan alone. On one connection of `dataSource` it:

1. turns auto-commit off and applies `QueryPlans.SETTINGS` with `SET LOCAL`;
2. runs `EXPLAIN (FORMAT JSON, VERBOSE, GENERIC_PLAN)` for a statement with `$1`-style placeholders, or
   `EXPLAIN (FORMAT JSON, VERBOSE)` with `generic = false` for a statement with its values inlined (jOOQ's
   `renderInlined`);
3. describes, from the catalog, every index an index scan of the plan uses and every unique index of a relation an index
   scan reads: its relation, its access method, its key columns in index order (`null` for an expression key), whether it
   is unique (`pg_index.indisunique`) and whether it has a predicate (`pg_index.indpred`);
4. rolls the transaction back and restores the connection's auto-commit, also when the statement cannot be explained.

A pooled connection is therefore handed back as it was lent (`ExplainLeavesPooledConnectionUnchangedIT`). `EXPLAIN`
without `ANALYZE` does not run the statement. `VERBOSE` is what reports each scan's schema and `Inner Unique` on joins.

| `QueryPlans.SETTINGS` | |
|---|---|
| `enable_seqscan = off` | criterion v3 never accepts a sequential scan |
| `enable_bitmapscan = off` | nor a bitmap scan |
| `enable_tidscan = off` | nor a TID scan |
| `max_parallel_workers_per_gather = 0` | no parallel plan |

PostgreSQL 18 prefers any plan with fewer disabled nodes over a cheaper plan with more, so the plan shows an index access
path whenever the indexes offer one, however few rows the test database holds. Sorts, hashed aggregates and joins stay
enabled: above a `Limit` they are part of bounded statements, such as a capped count or a delete of the ids a limited
subquery picked.

| `QueryPlan` member | Answers |
|---|---|
| `boundedScan(schema, relation)` | the `PlanVerdict` of criterion v3 for the relation in the schema |
| `boundedScan(relation)` | the same for a bare relation name, accepted only when the plan reads that name in one schema |
| `usesIndex(name)` | whether any node reads the named index |
| `scansSequentially(relation)` | whether any node is a `Seq Scan` of the relation |
| `hasLimit()` | whether the plan has a `Limit` node anywhere; `boundedScan` is the criterion for boundedness |
| `json`, `toString()` | the plan as PostgreSQL wrote it, for the assertion message |

| Type | What it is |
|---|---|
| `QueryPlan(json, indexes)` | one `EXPLAIN (FORMAT JSON, VERBOSE)` plan and the descriptions of the indexes it uses; the JSON is parsed on construction, and anything but a one-plan EXPLAIN document is an `IllegalArgumentException`, so a plan captured as a file can be judged too |
| `PlanIndex(schema, table, name, method, keyColumns, unique, partial)` | an index of `schema.table` as the catalog describes it; a partial unique index is unique only among the rows its predicate admits |
| `PlanVerdict.Bounded` | every read of the relation is bounded |
| `PlanVerdict.Unbounded(reasons)` | at least one reason, for every read of the relation that fails |

`boundedScan(schema, relation)` names a relation as the VERBOSE plan does, by its `Schema` and `Relation Name`. A bare
`boundedScan(relation)` is accepted only when every read of that name is in one schema; a plan that reads the name in
two schemas (`public.books` and `archive.books`) refuses it with an `IllegalArgumentException` naming both, and a name
holding a `.` is refused too (`AmbiguousRelationNameRefusedTest`). A qualified relation in a plan explained without
`VERBOSE`, which carries no schema, is unbounded with the reason that the plan names no schema.

```kotlin
val plan = QueryPlans.explain(fixture.dataSource, fixture.dsl.renderInlined(query), generic = false)

assertThat(plan.usesIndex("ix_audit_log_resource")).describedAs(plan.json).isTrue()
assertThat(plan.boundedScan("rain_audit", "audit_log")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
```

A statement whose every read of a table is bounded costs the same whether the table holds ten rows or ten million; that
is what the assertion proves, and why it needs no large fixture. rain-crud's [plan proof](crud.md#the-plan-proof) runs
every statement a mounted resource runs — its declared query shapes' pages and counts, and its statements by identifier —
through `QueryPlans.explain` and `boundedScan`.

### Plan criterion v3

Criterion **v3** (`QueryPlan.CRITERION_VERSION`): whether every row a plan reads from a relation is bounded, whatever
the size of the relation — by the statement's own `LIMIT` (and `OFFSET`), or by a unique key the read is pinned to.

The plan is `Bounded` when it reads the relation at all and every node that reads it (every node naming it as
`Relation Name` in `Schema`, except the `ModifyTable` a write targets) is a unique-key lookup (rule 0) or satisfies all of
rules 1–5:

0. **Unique-key lookup.** An `Index Scan` or `Index Only Scan` of a b-tree index, every clause of whose `Index Cond`
   bounds the scanned range (rule 3), and whose `Index Cond` pins a unique key of the relation. A unique key is the key
   columns, none an expression, of a unique b-tree index described in the plan's `PlanIndex`es: the scanned index itself,
   or another index of the same relation without a predicate (a partial unique index is unique only among the rows it
   holds). The `Index Cond` pins the key when
   - every key column has an `=` clause against one value (not `= ANY`, not `IS NULL`): the scan reads at most one entry
     each time it runs; or
   - the key has exactly one column, compared by `= ANY` with an inline array literal of *k* elements
     (`= ANY ('{…}'::uuid[])`, as PostgreSQL deparses an `IN` list of constants): the scan reads at most *k* entries each
     time it runs. `= ANY` over a parameter (`$1` of a generic plan) or any other operand states no *k* and pins nothing.

   Its `Filter` is then evaluated on at most that many rows and no `Limit` is needed. It is bounded when it runs once
   (rule 5) or, as the `Inner` input of a `Nested Loop` — with or without `Inner Unique`, with or without a `Join Filter` —
   when that join's `Outer` input has a bounded output and the join runs once. A unique-key lookup also counts as a
   bounded output wherever rule 4 asks for one.
1. **Index scan.** It is an `Index Scan` or an `Index Only Scan`.
2. **No filter.** It carries no `Filter`: every condition is an `Index Cond`.
3. **Bounding conditions.** Its index is a b-tree described in the plan's `PlanIndex`es, and every clause of its
   `Index Cond` bounds the scanned range: a clause on key column *k* is preceded, on every key column before *k*, by an
   equality clause (`=`, `= ANY (…)` or `IS NULL`); a row comparison covers consecutive key columns. A clause on a later
   column alone would be checked against every entry of the range instead of ending it, so the entries visited would grow
   with the relation. A clause criterion v3 cannot read (an expression key, any other form) makes the scan unbounded.
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

Rows read are then at most the `LIMIT` plus `OFFSET` for a limited scan, at most one per row of the bounded outer input
for a keyed scan, and at most one (or *k*) per run for a unique-key lookup. Nodes above the `Limit` are not constrained,
so a capped count (`Aggregate` over `Limit`) and `DELETE … WHERE id IN (SELECT … LIMIT n)` are bounded.

A plan that reads no relation at all — PostgreSQL proved the conditions contradictory and planned a constant-false
`Result` — reads no row of the relation and is bounded. A plan that reads other relations but not this one is unbounded
for it, so a misspelled relation (or a view, whose plan reads the tables under it) is never proven by default.

`Unbounded` lists every reason, for every read of the relation that fails. v2 added rule 0 for a scan of the unique index
itself with `=` on every key column; v3 extends rule 0 to a scan of another index that pins a non-partial unique key —
PostgreSQL plans `WHERE id = ? AND shelf = ?` over `(shelf, id)` rather than the primary key — and to `= ANY` over an
inline literal on a one-column unique key. A scan v2 accepts by rule 0 bounds its range trivially, so every plan v2
accepts, v3 accepts. `QueryPlanBoundedScanTest` holds plans captured from PostgreSQL 18 for both extensions, and
`UniqueKeyThroughAnotherIndexIT` explains them against a live database.

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

A unique-key lookup: `SELECT id FROM t WHERE id = 1` over `CREATE TABLE t (id int PRIMARY KEY)`. The plan has no
`Limit`, but its scan reads the unique b-tree index of the primary key with `=` against one value on its only key column,
so it reads at most one entry and runs once (rule 0). So does `SELECT note FROM keyed WHERE id = 7 AND grp = 1` over an
index `(grp, id)`: its `Index Cond` bounds the range of `(grp, id)` and pins the primary key `(id)`. And
`DELETE FROM keyed WHERE id IN (1, 2, 3) AND grp = 1` reads at most three entries: its `Index Cond` compares the
one-column primary key by `= ANY ('{1,2,3}'::integer[])`.

Plans the criterion refuses, with a reason it names (`QueryPlanBoundedScanTest`, over captured plans):

| Plan | Reason |
|---|---|
| a sequential scan | `Seq Scan on books is not an index scan` |
| a bitmap scan | `Bitmap Heap Scan on books is not an index scan` |
| an index scan with a `Filter` | `Index Scan using books_created_at_id on books carries a Filter: (books.pages >= 5)` |
| no `Limit` | `no Limit is above Index Scan using books_shelf_created_at_id on books` |
| a `Sort` below the `Limit` | `Sort is between Index Scan using books_shelf_created_at_id on books and any Limit above it` |
| a range on a later key column | `Index Cond clause (books.pages >= 5) of Index Only Scan using books_created_at_id_pages on books does not bound the scanned range of public.books_created_at_id_pages (btree on created_at, id, pages): key columns created_at, id before it have no equality condition` |
| a GiST index | `Index Scan using books_title_trgm on books reads a gist index; criterion v3 bounds b-tree scans only` |
| an expression key | `Index Scan using books_lower_title on books has an Index Cond criterion v3 cannot read: (lower(books.title) = 'dune'::text)` |
| `= ANY ($1)` of a generic plan on the primary key | `ModifyTable on books is between Index Scan using books_pkey on books and any Limit above it` |
| a `Limit` in a SubPlan | `Limit runs once per row of Index Scan using shelves_pkey on shelves (a SubPlan input)` |
| a keyed scan of a non-unique index under a join that is not `Inner Unique` | `Index Scan using ix_job_intent_held_invocation on job_intent is the inner input of a Nested Loop that is not Inner Unique` |
| an index the plan carries no description of | `the plan carries no description of index public.books_shelf_created_at_id` |
| another relation only | `the plan reads no relation named public.authors` |

### Redis servers

| Member | Behaviour |
|---|---|
| `RainRedis.IMAGE` | `redis:8-alpine` |
| `RedisPolicy.RETAINING` | `maxmemory-policy noeviction`: every key is kept for as long as it was written for |
| `RedisPolicy.EVICTING` | `maxmemory 64mb`, `maxmemory-policy allkeys-lru` |
| `RedisPolicy.SILENT` | `noeviction` with `CONFIG` renamed away: a server that answers and will not say how it is configured, as a managed Redis does |
| `RainRedis.shared(policy)` | the JVM's one server of that policy, started on first use; a test names its keys under a prefix of its own, and `close()` on it is refused |
| `RainRedis.start(policy)` | a server of the test's own, started now; the test closes it, for instance to stop it mid-test |
| `RedisServer.host`, `port`, `springProperties()` | where it listens; `spring.data.redis.host` and `.port` for an application context |

### A real application

`RainApplication.start(sources, web, properties, singletons = emptyMap())` starts a real Spring Boot application —
listeners, environment post-processors and auto-configuration all take part. Every `name=value` of `properties` is passed
as a command-line argument, which outranks the application's own configuration files as a deployment's environment does
and which a command reads as its arguments; nothing else is stated for the test. `singletons` are registered by name
before any bean is created, a test's `Clock` for instance.

| Member | Behaviour |
|---|---|
| `context` | the running `ConfigurableApplicationContext` |
| `port` | the port the web server published (`local.server.port`); refused for an application with no web server |
| `http` | an `ApplicationHttp` over `port` |
| `bean(type)` | a bean of the context |
| `exit()` | closes the context and answers the exit code its `ExitCodeGenerator`s agree on, a command's for one |
| `close()` | closes the context |
| `ApplicationHttp(port)` | `send(method, path, body, headers)` and `get(path, headers)` with no cookie jar; a body with no `Content-Type` of its own is sent as `application/json`; `uri(path)`, `client` |

### Time

`MutableClock(now, zone = UTC)` is a `java.time.Clock` that moves only when a test moves it: `advance(by)` and
`set(to)`, both refusing to move backwards. Every rain module reads time from the application's `Clock` bean, so a
test replaces that bean.

## Configuration, error codes, health checks, schema, commands

None.

## What it does not do

- It starts no application context unless a test calls `RainApplication.start`, and states no role, stage or property for it.
- It does not share a database between tests, migrate a template database or truncate tables.
- It offers no container for any service other than PostgreSQL and Redis.
- It judges a plan by its structure, never by its cost estimates, row estimates or timing.
