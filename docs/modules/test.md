# rain-test

Test support for rain modules and rain applications: one PostgreSQL server per test JVM with a fresh database per
test, query-plan assertions that prove a statement is bounded without depending on how many rows a test inserted,
and a clock a test moves by hand.

Add it to the test classpath of anything that tests against PostgreSQL or reads time.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    testImplementation("com.gd.rain:rain-test")
}
```

It exposes Spring Boot's test support, `spring-jdbc`, the JUnit Jupiter API, AssertJ, Testcontainers' PostgreSQL
module and the PostgreSQL driver. Tests that use `RainPostgres` need a Docker daemon and belong to the integration
tier ([conventions](../conventions.md#tests)).

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

`QueryPlans.explain(dataSource, sql, generic = true)` runs `SET enable_seqscan = off` on the connection and then
`EXPLAIN (FORMAT JSON, GENERIC_PLAN)` for a statement with `$1`-style parameters, or `EXPLAIN (FORMAT JSON)` with
`generic = false` for a statement with its values inlined (jOOQ's `renderInlined`). With sequential scans disabled,
any scan the planner still chooses is one no index can serve.

| `QueryPlan` member | Answers |
|---|---|
| `usesIndex(name)` | whether a node reads the named index |
| `scansSequentially(relation)` | whether a node is a sequential scan of the relation |
| `hasLimit()` | whether the plan has a `Limit` node |
| `json` | the plan, for the assertion message |

```kotlin
val query = ledger.deadLettersQuery(definition = null, after = null, limit = 50)
val plan = QueryPlans.explain(database.dataSource(), dsl.renderInlined(query), generic = false)

assertThat(plan.usesIndex("ix_job_invocation_dead_letters")).describedAs(plan.json).isTrue()
assertThat(plan.hasLimit()).isTrue()
```

A statement whose plan reaches its rows through a named index under a `Limit` costs the same whether the table holds
ten rows or ten million; that is what the assertion proves, and why it needs no large fixture. Pass a data source
whose connections are not reused by other tests — `RainDatabase.dataSource()` — because the setting stays on the
connection.

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
