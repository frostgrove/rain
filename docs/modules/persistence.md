# rain-persistence

PostgreSQL persistence on Spring Boot's JDBC, jOOQ and Flyway: application-minted UUIDv7 identifiers, one statement
timeout for every statement, SQLSTATE classification into faults, transaction retry, transaction-scoped advisory
locks, and schema-per-module migrations with the `migrate` command.

Add it when the application stores data in PostgreSQL. rain-audit, rain-jobs, rain-llm, rain-realtime and
rain-data-jdbc depend on it.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-persistence")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
```

rain-persistence brings Boot's JDBC, jOOQ and Flyway modules, Flyway's PostgreSQL support and the PostgreSQL driver.
It does not choose a connection pool; `spring-boot-starter-jdbc` brings HikariCP.

## What it contributes

`RainPersistenceAutoConfiguration` — after Boot's jOOQ auto-configuration, when jOOQ is on the classpath, in every
role:

| Bean | Condition | What it is |
|---|---|---|
| `idGenerator` | no other `IdGenerator` bean | `UuidV7Ids` |
| `transactionRetry` | — | `TransactionRetry` from `rain.persistence.retry` |
| `dataAccessFaultTranslator` | — | `DataAccessFaultTranslator`, a `FaultTranslator` |
| `statementTimeout` | — | `StatementTimeout` from `rain.persistence.statement-timeout` |
| `statementTimeoutJooqCustomizer` | — | sets jOOQ's `queryTimeout` on the `DSLContext` Boot builds |
| `statementTimeoutJdbcTemplatePostProcessor` | — | sets `queryTimeout` on every `JdbcTemplate` bean, which Spring Data JDBC executes through |
| `statementTimeoutAgreementCheck` | — | `spring.jdbc.template.query-timeout`, when stated, equals rain's timeout |
| `advisoryLockStore`, `advisoryLocks` | a `DSLContext` and a `PlatformTransactionManager` | `JooqAdvisoryLockStore`, `AdvisoryLocks` |

`RainSchemaAutoConfiguration` — before Boot's Flyway auto-configuration, when Flyway is on the classpath:

| Bean | Condition | What it is |
|---|---|---|
| `rainSchemaMigrationStrategy` | — | `RainSchemaMigrationStrategy` over every schema descriptor on the classpath; a malformed or duplicated descriptor refuses the start |
| `singleMigrationStrategyCheck` | — | rain's strategy is the only `FlywayMigrationStrategy` |
| `migrateCommand` | command `migrate` | see [commands](#commands) |

## Configuration

`rain.persistence` is required whenever rain-persistence is on the classpath; `rain.locks` is optional.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.persistence.statement-timeout` | required | the bound on every jOOQ and `JdbcTemplate` statement; JDBC counts whole seconds, so it is rounded up and never down to "no limit" | positive |
| `rain.persistence.retry.attempts` | `3` | how many times a transaction runs, the first run included | at least 1 |
| `rain.persistence.retry.initial-delay` | `50ms` | the wait before the first rerun; each wait doubles | positive |
| `rain.persistence.retry.max-delay` | `1s` | the longest wait | not below `initial-delay` |
| `rain.locks.timeout` | `10s` | `lock_timeout` for each advisory lock taken | positive |
| `rain.locks.attempts` | `3` | how many times `AdvisoryLocks.guarded` runs its transaction | at least 1 |

`guarded` waits between runs from `rain.persistence.retry.initial-delay`, capped at the larger of `rain.locks.timeout`
and that initial delay.

Bean-time problems:

| Path | Code | When |
|---|---|---|
| `spring.jdbc.template.query-timeout` | `contradicts` | stated with a value other than `rain.persistence.statement-timeout` |
| `spring.flyway` | `contradicts` | a `FlywayMigrationStrategy` bean other than rain's, or none |

## API

### Identifiers

`UuidV7Ids` is RFC 9562 version 7: 48 bits of Unix milliseconds and random bits drawn on every call, so ids are
time-ordered for index locality and not guessable from one another. No rain table gives an id column a database
default.

### Statement timeout

`StatementTimeout(timeout)` refuses a timeout that is not positive; `seconds` is the timeout rounded up to whole
seconds, at least 1.

### Transaction retry

`TransactionRetry.run { }` runs a block again when it failed with a SQLSTATE that repetition fixes:
`40P01` deadlock, `55P03` lock not available, `40001` serialization failure. Every other failure is rethrown on the
first attempt; after the last attempt the last failure is rethrown. It wraps a transaction boundary, never a statement
inside a transaction — PostgreSQL has already rolled the transaction back.

```kotlin
class TicketClosing(
    private val retry: TransactionRetry,
    transactions: PlatformTransactionManager,
    private val tickets: TicketStore,
) {
    private val transaction = TransactionTemplate(transactions)

    fun close(id: UUID) {
        retry.run { transaction.executeWithoutResult { tickets.close(id) } }
    }
}
```

`SqlStates.of(failure)` reads the SQLSTATE from the cause chain and every `SQLException.nextException`, visiting each
exception once; `SqlStates.retryable(failure)` answers whether it is one of the three above.

### Fault translation

`DataAccessFaultTranslator` answers for a `DataAccessException` or any failure with a `SQLException` in its cause
chain, from a declared table; the table is in [errors](../concepts/errors.md#data-access). A state outside the table is
`500 internal`, never a guess.

### Advisory locks

```kotlin
class TicketNumbers(
    private val locks: AdvisoryLocks,
    private val store: TicketNumberStore,
) {
    fun next(queue: String): Long =
        locks.guarded(listOf(Exclusively(keyOf("ticket-number", queue)))) {
            store.increment(queue)
        }
}
```

| Method | Transaction | Behaviour |
|---|---|---|
| `take(guard)`, `take(guards)` | joins the caller's; refuses with `IllegalStateException` outside one | `SET LOCAL lock_timeout`, then `pg_advisory_xact_lock` or `pg_advisory_xact_lock_shared` per guard, in the order given |
| `guarded(guards, prepare, block)` | opens its own (`REQUIRES_NEW`); refuses inside another with `NestedTransactionNotAllowed` | runs `prepare`, takes the guards, runs `block`; the whole transaction is retried on deadlock, lock timeout or serialization failure |

Locks are released at commit or rollback. `guarded` refuses to run inside a transaction because the outer
transaction already holds a pooled connection, and a second connection requested while it waits is a deadlock the
pool cannot resolve. Callers that take the same locks take them in the same order.

`AdvisoryLockStore` is the statement port (`lockTimeout`, `statementTimeout`, `exclusive`, `shared`);
`JooqAdvisoryLockStore` sets the timeouts with `set_config(…, true)`, which is transaction-local.

### Stored values

`JsonbPayload` marks a typed value stored in a `jsonb` column; `WireEnum` marks an enum over a `CHECK`-constrained
text column whose stored string is `wire`, written per constant. [rain-data-jdbc](data-jdbc.md) registers the
converters for Spring Data JDBC.

### Schema migration

`RainSchemaMigrationStrategy`, `SchemaDescriptor` and `MigrationReport` implement
[schema ownership](../concepts/schema-ownership.md) and [ADR 0001](../adr/0001-schema-per-module.md): every module
schema in module-name order, each with its own history table, then the application's own Flyway.

## Commands

| Command | Roles | Declared properties | Output | Exit |
|---|---|---|---|---|
| `migrate` | none | `spring.flyway.enabled=true` | one line per module schema, then one for the application, on `out` | 0 |

```
migrate: rain_jobs applied 1, now at 1
migrate: application applied 1, now at 1
```

Serving processes can keep `spring.flyway.enabled=false`; the migration then runs as an explicit step.

## Error codes, health checks, schema

rain-persistence declares no error codes of its own; its translator uses rain-core's storage and retry codes. It
offers no health check — the `database` check is [rain-observability](observability.md)'s — and owns no tables.

## Scale guarantees

- Every statement issued through jOOQ or a `JdbcTemplate` carries the statement timeout; no statement is unbounded.
- Advisory locks are transaction-scoped and wait at most `rain.locks.timeout`.
- A retried transaction runs at most `attempts` times with capped waits.

## What it does not do

- It does not choose a connection pool or size it.
- It does not retry a statement inside a transaction, and it does not retry `57014` (a statement that hit its
  timeout).
- It does not classify a SQLSTATE outside its table.
- It never baselines, never cleans and never migrates a process in which `spring.flyway.enabled` is false.
- It registers no Spring Data repository ([ADR 0002](../adr/0002-no-spring-data-inside-rain.md)).
