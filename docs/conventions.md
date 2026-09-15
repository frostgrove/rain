# Conventions

What an application built on rain and a rain module follow. Most of these are enforced — by the compiler flags, by a
start-up refusal, by a test — and the rest are the rules the enforced ones come from.

## Decisions come from declared rules

No outcome is decided by a guess: no inferred default, no similarity match, no threshold fitted to an example, no list of
phrases, no best-effort classification. A decision comes from a deterministic algorithm or from explicit, versioned,
declarative rules. Input that is insufficient or invalid produces an explicit not-evaluated state or a typed error, never
a fallback.

How that reads in rain:

| Situation | What rain does |
|---|---|
| a configuration rule reads a section the application does not bind | `not_evaluated`, logged; the rule does not run |
| a check's importance is not stated | the start is refused; importance is never inferred from the checker |
| a status a framework chose is outside the status table | `500 unmapped_status`, logged |
| a SQLSTATE outside the declared table | `500 internal`; no guess from the message text |
| a fault carries a code no catalog declares | `500 internal`, and the unregistered code is logged |
| a model has no declared token counter | `OutputBudget.NotEvaluated`; no characters-per-token ratio |
| a declared breaker has no explicit configuration | the start is refused; no library default |
| an invocation of a definition the application does not declare | `dead` with `unknown_definition`; never retried under an assumed profile |
| rows of a job profile the application no longer declares | kept and reported on every pass; no assumed retention |
| a handler that declares no access | the start is refused; no package or library exempts it, only a `SurfaceExemption` bean |
| a revocation list or an attempt store that cannot be asked | `503 revocation_unavailable` or `503 unavailable`; never read as a live session or an admitted attempt |
| a Redis server that will not say what it evicts | the start is refused, unless the deployment attests `noeviction`; then `not_evaluated` |
| a search for a usable role holder that runs out of its page budget | `HolderSearch.NotEvaluated`; no answer from the pages it did read |

Rule sets that can change carry a version: problem format v1, status table v1, query dialect v1, cursor format v1, plan
criterion v2, the rain-crud plan proof version 1, `NotifyRules` version 1, the breaker configuration rules version 1.

## Design for ten million rows

The amount of data in a table today says nothing about tomorrow. Any collection a person or a process can add to is
designed as though it holds ten million entries, from the first line.

- No per-item operation scans, loads, serialises or sends a whole extensible collection — not into memory, a request
  body, a log line or a prompt.
- Every read over an extensible table goes through an index and is bounded: a keyset page with `LIMIT`, a batch of fixed
  size, a seek of one row. No page counts its total.
- Every write over many rows is batched, each batch one statement, with a budget for the whole pass.
- A narrowing that could change an outcome is deterministic and reproducible, and a miss surfaces as an explicit state.
- A measurement of the present data never justifies a design, and a bounding mechanism is never removed because the data
  is small today.

Examples in rain: audit and dead-letter pages are keyset over `(occurred_at|finished_at, id)` with at most 500 items and no
total; cancellation by subject runs 500 rows per statement; job retention deletes in batches and stops at a run budget;
the reaper takes one page `FOR UPDATE SKIP LOCKED`; an LLM slot is decided in one statement over one pool's index range;
a throttle's caller table is bounded and finds room with one heap look; readiness detail is found by name in a map; a
rain-crud list answers only a declared query shape, reads `limit + 1` rows and counts no further than a declared cap; a
rain-access permission question reads the asked codes and the subject's own grants, bounded by the role ceiling; closing
every session of a subject writes one cutoff row and one revocation key, then closes sessions in batches.

### Plan-proven queries

Scale is proven from the query plan, not from timing or row counts. An integration test renders the real statement,
explains it with `QueryPlans` ([rain-test](modules/test.md)) — sequential, bitmap and TID scans and parallel plans
disabled — and asserts `QueryPlan.boundedScan`, plan criterion v2, for each table it reads: every read is a b-tree index
scan without a `Filter` whose index conditions bound the scanned range, under a `Limit` or keyed by a bounded input, and
run once — or a lookup of at most one entry of a unique index. A statement whose plan passes costs the same at ten rows
and at ten million, so the test needs no large fixture. rain's own: `AuditIT`, `RetentionUsesIndexIT`,
`DeadLetterKeysetIT`, `ReaperIT`, `CancelBySubjectIT`, `HeldReservationIsKeyedByInvocationIT`, `CursorPagingIT`,
`CappedCountIT`; `LlmSlotsIT` asserts the indexes its statements read and that neither table is scanned sequentially.
rain-access's `SessionPagesKeysetIT` and `DirectoryPagesKeysetIT` explain each keyset page with sequential scans, bitmap
scans and sorts priced out and assert the named index under a `Limit` with no `Filter` and no sort;
`PermissionCheckBoundedCostIT` and `SessionsRetentionIT` read their plans through `QueryPlans` and assert the indexes used,
index conditions and no sequential scan ([rain-access](modules/access.md#scale-guarantees)).

A rain-crud resource is proven as a whole: `CrudPlanProof` explains every statement of every declared query shape against
the application's migrated, empty database ([rain-crud](modules/crud.md#the-plan-proof)).

## Configuration

- **Required over inferred.** A value that decides behaviour — a timeout that bounds every statement, the stage, the
  roles, a pool name, a memory bound — has no default and is required. A declared default is reserved for a tuning
  number that is sound everywhere.
- **One refusal names every problem.** A section's rules collect problems; they never stop at the first.
- **A value that is not acceptable is refused, never corrected.** Zero, a negative duration or a ceiling above its
  maximum is a problem, not a clamp or a substitution.
- **Secrets in production come from the environment**, marked with `@RequiredFromEnvironment`.
- **A declared default is documented with its section**, on the module's page.
- **Every section is declared** through a `ConfigurationContributor`, so validation knows every key, and a key nobody
  declares is refused.

## Runtime roles and commands

- A contribution that consumes — a scheduler, a renewer, a listener — is gated with `@ConditionalOnRainRole`. A client —
  a work queue, a publisher — exists in every role.
- Activation is a declaration, not a side effect of some bean asking for a type.
- Background work a component owns starts and stops with the component's lifecycle (`SmartLifecycle`): the job worker,
  the realtime listener.
- A command is declared in `spring.factories` and answered by exactly one `RainCommand` bean under
  `@ConditionalOnRainCommand`.

See [runtime roles and commands](concepts/runtime-roles-and-commands.md) and [ADR 0003](adr/0003-roles-not-profiles.md).

## Naming

| Thing | Convention | Example |
|---|---|---|
| Maven group | `com.gd.rain` | |
| artifact | `rain-<module>` | `rain-data-jdbc` |
| package | `com.gd.rain.<module>`, hyphens as package separators | `com.gd.rain.data.jdbc` |
| auto-configuration package | `com.gd.rain.<module>.autoconfigure` | |
| implementation package | `com.gd.rain.<module>.internal` where a module has one | `com.gd.rain.jobs.internal` |
| properties | `rain.<module>.*` | `rain.jobs.lease.ttl` |
| schema | `rain_<module>`, hyphens as underscores | `rain_jobs` |
| migrations | `src/main/resources/db/rain/<module>/` | `V1__jobs.sql` |
| generated jOOQ package | `com.gd.rain.<module without hyphens>.jooq` | `com.gd.rain.jobs.jooq` |
| schema descriptor | `META-INF/rain/schemas/<module>.properties` | |
| error catalog owner | the artifact name | `rain-jobs` |
| error code | `^[a-z][a-z0-9_]{0,63}$` | `job_intent_conflict` |
| health check name / public code | `^[a-z][a-z0-9._-]{0,127}$` / `^[a-z][a-z0-9._-]{0,63}$` | `realtime.listener` / `realtime` |
| command | `^[a-z][a-z0-9-]{0,63}$` | `config-check` |

A few sections cross module lines by what they are about: `rain.deployment` and `rain.runtime` (rain-boot), `rain.health`
(rain-observability), `rain.locks` (rain-persistence).

## Building a module

Every module is built with the convention plugins in `build-logic`:

| Plugin | What it applies |
|---|---|
| `rain.kotlin-library` | Kotlin on the Java 25 toolchain (provisioned by the foojay resolver), `explicitApi()`, `-Werror`, `-Xjspecify-annotations=strict`, `-Xconsistent-data-class-copy-visibility`, JVM default methods without compatibility bridges, the `rain-dependencies` platform, Spotless with ktlint 1.8.0 over `src/*/kotlin/**/*.kt` and `*.gradle.kts`, Kover, the two test tiers, a 2 GB test heap |
| `rain.spring-module` | `rain.kotlin-library` plus the Kotlin Spring plugin and Boot's auto-configuration API |
| `rain.jooq-schema` | `rain.spring-module` plus jOOQ code generation from the module's own migrations and the schema descriptor ([schema ownership](concepts/schema-ownership.md)) |
| `rain.sample-app` | `rain.kotlin-library` plus the Spring Boot plugin; explicit API mode off |

- **Public API is explicit.** Explicit API mode makes every public declaration a decision. Implementation is `internal`.
  Auto-configuration classes and their `@Bean` methods are public, because their names are bean names.
- **Warnings are errors.** A compiler warning fails the build; a warning accepted on purpose is suppressed where it is
  made, with the reason visible there.
- **Formatting is checked.** `spotlessCheck` is part of `check`; `spotlessApply` formats.
- **A module contributes through two files only**: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  for auto-configuration, and `META-INF/spring.factories` for what has to be known before beans exist
  (`ConfigurationContributor`, `CommandDeclaration`). No component scan, no `@EnableJdbcRepositories`, no
  `@AutoConfigurationPackage`.
- **Every bean an application may replace** is `@ConditionalOnMissingBean`.
- **rain-core stays framework-free**: no Spring, Jackson, jOOQ or servlet API (`CoreDependenciesTest`).
- **Time comes from the `Clock` bean**, never from `Instant.now()` in a decision.

## Tests

Two tiers in one source set, split by the JUnit tag `integration`:

| Task | Runs | Needs |
|---|---|---|
| `test` | every test class not tagged `integration` | no Docker for the tests themselves |
| `integrationTest` | exactly the classes tagged `@Tag("integration")` | a Docker daemon |

`check` runs `test`, `spotlessCheck` and Kover's `koverVerify`. Kover's report depends on every test task of the module,
so `check` runs `integrationTest` as well and needs Docker. Integration classes are named `…IT`. Generating the jOOQ
code of a module that owns tables needs a PostgreSQL: a throwaway Testcontainers one, or the server named by
`JOOQ_CODEGEN_SERVER_URL`, `JOOQ_CODEGEN_SERVER_USER` and `JOOQ_CODEGEN_SERVER_PASSWORD`.

- A test that depends on time moves a `MutableClock`; one that depends on a random draw injects it (`Jitter`); one that
  depends on a timer or a scheduler drives a fake by hand.
- A test that starts a context states its stage and roles; there is no implicit test role.
- Each integration test gets a database of its own (`RainPostgres.freshDatabase`).
- A query over an extensible table has a plan assertion (`QueryPlan.boundedScan`), and every rain-crud resource has a
  `CrudPlanProof` test.

## Data access

- **A module owns one schema** and never references anything outside it; an application may reference module tables
  ([ADR 0001](adr/0001-schema-per-module.md)).
- **A module's statements are visible SQL.** Statements over a module's tables are written with jOOQ against code
  generated from that module's migrations, in store classes the module owns; no module declares Spring Data repositories
  ([ADR 0002](adr/0002-no-spring-data-inside-rain.md)). A statement jOOQ cannot type — `LISTEN`, `UNLISTEN *` — is a
  constant next to the code that issues it.
- **An application picks the narrowest mechanism**: a derived repository method for a finder its name expresses, jOOQ for
  anything statement-shaped — a partial update, `RETURNING`, `FOR UPDATE`, `ON CONFLICT`, a common table expression, a
  batch, an advisory lock. The repository stays the one entry point; statement-shaped methods live in a custom fragment
  backed by jOOQ.
- **Identifiers are minted by the application**, UUIDv7 through `IdGenerator`, before the row is written. No id column
  has a database default, and an insert needs no `RETURNING id`. Migrations and seed data that insert rows spell their ids.
- **Insert or update** under Spring Data JDBC is decided by `Persistable.isNew()`, then the `@Version` property, then the
  id — not by whether the id is null.

### Migrations

- Module migrations use unqualified names; they run with the module schema as the default schema.
- Until an owner's schema has reached a database somebody else also reads, its migrations are edited in place: one file
  per owner holds that owner's current schema, and development databases are recreated to pick a change up. A patch
  migration before that point describes a history nobody has.
- From the first release on, the file set is frozen and every change is a new numbered migration. Nothing is baselined;
  Flyway validates on migrate, and `clean` is disabled for module schemas.

## Errors

- Every refusal is a `Fault` with a registered `ErrorCode`; a module declares its codes in one `ErrorCodeCatalog` whose
  owner is the artifact name.
- A failure a module does not raise as a fault — a library exception — is turned into one by a `FaultTranslator` bean,
  or is `500 internal`.
- A message never quotes a secret, and a public answer never carries a failure's internals.

See [errors](concepts/errors.md).

## Health

A module offers `HealthCheck` beans with a name and a public code and never decides their importance; the application
states it under `rain.health.checks`. See [transport and health](concepts/transport-and-health.md).

## Documentation

Every module has a page under `docs/modules/`: what it is for, its dependency, what its auto-configuration contributes and
in which role, every property with its default and validation, its API, error codes, health checks, schema, scale
guarantees, commands, and what it does not do. A statement on a page is verified in the source.
