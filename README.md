# rain

rain is a meta-framework over Spring Boot 4 for Kotlin backends on PostgreSQL. It adds what every service needs and Boot
leaves to each application: configuration that is validated as a whole before any bean exists, processes started with
explicit roles or as one-shot commands, one wire format for every refusal, readiness composed from checks whose
importance the application states, schema-per-module migrations, and bounded data access for durable jobs, audit,
sign-in and grants, live updates, circuit breakers and language models.

Version `0.1.0-SNAPSHOT`; Kotlin 2.4.20, Spring Boot 4.1.1, Java 25.

## Modules

| Module | What it is |
|---|---|
| [rain-core](docs/modules/core.md) | Spring-free types: faults and error codes, configuration problems, identifiers, lock names, actors |
| [rain-boot](docs/modules/boot.md) | runtime roles and commands, the deployment stage, whole-configuration validation, seeding, `runRain` |
| [rain-observability](docs/modules/observability.md) | the health registry behind `/live` and `/ready`, draining, the Actuator bridge, the OpenTelemetry log bridge |
| [rain-web](docs/modules/web.md) | problem+json for every refusal, transport filters, probes, role gating of the web surface, a bounded throttle |
| [rain-persistence](docs/modules/persistence.md) | UUIDv7 ids, one statement timeout, SQLSTATE classification, transaction retry, advisory locks, schema-per-module migrations, `migrate` |
| [rain-data-jdbc](docs/modules/data-jdbc.md) | id assignment and column conversions for an application's own Spring Data JDBC repositories |
| [rain-crud](docs/modules/crud.md) | declarative resources over PostgreSQL: query dialect v1 over declared query shapes, row-level policy, keyset-first pagination, capped counts, a plan proof for every shape |
| [rain-audit](docs/modules/audit.md) | the append-only audit trail, written in the transaction that made the change |
| [rain-jobs](docs/modules/jobs.md) | the durable job queue: leases, fenced effects, deduplication, retries into dead letters, redrive, retention |
| [rain-realtime](docs/modules/realtime.md) | PostgreSQL `LISTEN`/`NOTIFY` as a bus with transactional publishing and bounded subscriptions |
| [rain-resilience](docs/modules/resilience.md) | circuit breakers on Resilience4j with atomic admission and one probe per cooldown |
| [rain-llm](docs/modules/llm.md) | a gateway over a Spring AI `ChatModel` with cluster-wide slots, breaker accounting and token budgets |
| [rain-access](docs/modules/access.md) | password sign-in with database sessions and rotating refresh credentials, an HS256 access token, a revocation list on Redis, role and permission grants, and every route's `@Access` verified at start-up and enforced per request |
| [rain-test](docs/modules/test.md) | a PostgreSQL per test JVM with a database per test, query plans judged by plan criterion v3, a movable clock |
| `rain-dependencies` | the one platform an application imports |

## Principles

- **Required configuration over inferred defaults.** Anything that decides behaviour — the stage, the roles, every
  timeout that bounds work, every memory bound — is stated by the deployment. Defaults exist only for tuning numbers that
  are sound everywhere.
- **One refusal names every problem.** A start with three mistakes costs one restart: required values, invalid values,
  contradictions and undeclared keys of rain and of the application are reported together
  ([configuration](docs/concepts/configuration.md)).
- **Roles and commands, not profiles.** A process states `rain.runtime.roles` (`api`, `worker`, `seeder`) or
  `rain.runtime.command`; nothing is guessed from a profile name
  ([runtime roles and commands](docs/concepts/runtime-roles-and-commands.md)).
- **A schema per module.** Every module that stores data owns `rain_<module>` with its own migrations and history, so a
  module can join a database with years of application history ([schema ownership](docs/concepts/schema-ownership.md)).
- **problem+json.** Every refusal, from a controller or from a filter before MVC, is the same RFC 9457 body with a
  registered code ([errors](docs/concepts/errors.md)).
- **Bounded queries.** Every read over data that grows is indexed and paged by key, every bulk write is batched, and
  integration tests prove it from the query plan ([conventions](docs/conventions.md)).
- **No heuristic decisions.** An outcome comes from a declared rule; missing input is an explicit not-evaluated state or
  a typed error.

## Building

rain builds with its Gradle wrapper; the Java 25 toolchain is provisioned by Gradle.

```sh
./gradlew test
./gradlew check
```

- `./gradlew test` runs the unit tier. No test in it needs Docker. Compiling rain-access, rain-audit, rain-jobs and rain-llm
  generates their jOOQ code from their migrations on a throwaway PostgreSQL container, so a clean build needs a Docker
  daemon — or `JOOQ_CODEGEN_SERVER_URL`, `JOOQ_CODEGEN_SERVER_USER` and `JOOQ_CODEGEN_SERVER_PASSWORD` naming a PostgreSQL server.
- `./gradlew check` runs both tiers (the integration tier is `@Tag("integration")`), Kover's verification, the formatting
  checks, the module graph and the tool-version parity, and needs Docker.

## Documentation

- [Getting started](docs/getting-started.md)
- [Conventions](docs/conventions.md)
- Concepts: [configuration](docs/concepts/configuration.md),
  [runtime roles and commands](docs/concepts/runtime-roles-and-commands.md), [errors](docs/concepts/errors.md),
  [schema ownership](docs/concepts/schema-ownership.md), [transport and health](docs/concepts/transport-and-health.md)
- Decisions: [ADR 0001 — schema per module](docs/adr/0001-schema-per-module.md),
  [ADR 0002 — no Spring Data inside rain](docs/adr/0002-no-spring-data-inside-rain.md),
  [ADR 0003 — roles, not profiles](docs/adr/0003-roles-not-profiles.md)
- [Dependency notes](docs/dependency-notes.md)
- `samples/rain-sample-minimal` — the smallest rain HTTP application, with its tests
