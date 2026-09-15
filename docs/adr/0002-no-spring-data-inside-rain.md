# ADR 0002 — No Spring Data repositories inside rain

Status: accepted

## Context

rain modules need data access. Spring Data JDBC repositories are convenient, but:

- enabling repositories from a library (`@EnableJdbcRepositories`, `@AutoConfigurationPackage` on module packages)
  switches Spring Boot's repository auto-configuration off for the consuming application, or makes it scan packages
  the application did not ask for;
- derived queries hide their SQL, so the indexed access path every rain query needs cannot be reviewed or asserted;
- the queries rain needs — keyset pages, `FOR UPDATE SKIP LOCKED`, conditional updates that report ownership,
  batched deletes with a budget — are not expressible as derived queries.

## Decision

rain modules access their own schema with jOOQ over code generated from their migrations, in store classes owned by
the module. No rain module declares Spring Data repositories or enables repository scanning. Applications remain
free to use Spring Data JDBC; `rain-data-jdbc` contributes id assignment, converters and the statement timeout to it
without enabling any repository.

## Consequences

- An application's own repositories register exactly as they would without rain (the sample application keeps one
  to prove it).
- Every rain query is visible SQL, and integration tests assert its plan (`EXPLAIN`, index used, `LIMIT` present).
- rain modules depend on jOOQ; applications that do not use jOOQ still get a `DSLContext` from Boot's jOOQ
  auto-configuration when they use a rain module that stores data.
