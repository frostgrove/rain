# ADR 0001 — One PostgreSQL schema per rain module

Status: accepted

## Context

rain modules that store data (audit, access, jobs, llm) ship tables into databases that also hold the application's
tables. The modules are versioned independently of each application, and an application adopts modules over time.

A single Flyway history shared by rain and the application means:

- migration version numbers of independent owners interleave, so a module release can collide with, or sort before,
  an application migration that is already applied;
- adding a module to an existing database needs `baselineOnMigrate` or out-of-order migrations, both of which let a
  database skip migrations silently;
- one code generator over one schema generates classes for every owner's tables, so a module's generated code
  depends on the application's migrations.

## Decision

Every module owns one schema named `rain_<module>`, with its own `flyway_schema_history` and its own migrations in
`classpath:db/rain/<module>`. The application keeps its own schema and history. `RainSchemaMigrationStrategy` applies
module schemas in module-name order, then the application's migrations. Module tables do not reference tables
outside their schema; application tables may reference module tables.

Each module generates jOOQ code only from its own migrations (`rain.jooq-schema` build plugin), and a schema
descriptor on the classpath ties module name, schema and migration location together, validated at start-up.

## Consequences

- A module can be added to a database with years of application history; no baseline is ever needed.
- Version numbers are per owner; a module release cannot collide with an application migration.
- A module cannot keep a foreign key to an application table (for example audit rows to users). Such relations are
  typed references (`actor_type`, `actor_id`) validated by the writer instead of by the database.
- Cross-schema queries are explicit (`rain_audit.audit_log`), which makes ownership visible in SQL.
