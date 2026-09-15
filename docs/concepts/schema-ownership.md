# Schema ownership

Every rain module that stores data owns **one PostgreSQL schema**, named `rain_<module>`, with its own migrations and
its own Flyway history table. The application owns its schema (normally `public`). Application tables may reference
module tables; module tables never reference anything outside their schema.

```
rain_audit.flyway_schema_history   rain_audit.audit_log
rain_jobs.flyway_schema_history    rain_jobs.job_invocation …
public.flyway_schema_history       public.tickets (REFERENCES rain_…)
```

Because each Flyway only inspects its own schema, no module ever sees another owner's tables and no migration run
ever needs a baseline.

## Building a module that owns tables

```kotlin
plugins {
    id("rain.jooq-schema")
}

rainSchema {
    module.set("audit")
}
```

- Migrations live in `src/main/resources/db/rain/<module>/` (`V1__audit.sql`, …) and use unqualified names; they run
  with the module schema as default schema.
- `generateJooq` applies exactly those migrations to an empty PostgreSQL and generates `com.gd.rain.<module>.jooq`
  (tables qualified with `rain_<module>`), so generated code and migrations cannot drift. It uses a throwaway
  Testcontainers PostgreSQL, or — when `JOOQ_CODEGEN_SERVER_URL`, `JOOQ_CODEGEN_SERVER_USER` and
  `JOOQ_CODEGEN_SERVER_PASSWORD` are set — creates, migrates and drops a scratch database named
  `rain_jooq_<module>` on that server.
- `writeSchemaDescriptor` produces `META-INF/rain/schemas/<module>.properties`; module name, schema and location are
  one convention and are checked, so a module cannot migrate into another module's schema.

## How an application migrates

`RainSchemaMigrationStrategy` is the only `FlywayMigrationStrategy`:

1. every module schema found on the classpath, in module-name order, each with explicit settings (`baselineOnMigrate`
   off, validation on, `clean` disabled, missing locations fail);
2. then the application's own Flyway (`spring.flyway.*`).

`spring.flyway.enabled=false` means nothing migrates. A deployment can keep migration off in its serving processes and
run it as an explicit step with the `migrate` command, which enables Flyway for itself and prints what it applied:

```
migrate: rain_audit applied 1, now at 1
migrate: rain_jobs applied 1, now at 1
migrate: application applied 3, now at 3
```

A second strategy bean, or a malformed or duplicated schema descriptor, is a startup refusal.

## Identifiers

Rows are keyed by identifiers the application mints before writing (`IdGenerator`, UUIDv7 by default), so storage
keys, events and log lines can carry them. No id column has a database default.
