# Repository Guidelines

## Project Structure & Module Organization

Rain is a multi-module Gradle/Kotlin project. Production modules live at the root as `rain-*`; each uses `src/main/kotlin`, `src/test/kotlin`, and, where needed, `src/main/resources`. Database-owning modules keep Flyway migrations under `src/main/resources/db/rain/<module>/`. Shared Gradle convention plugins are in `build-logic/`. Architecture decisions, concepts, and module contracts belong in `docs/`; runnable examples live in `samples/rain-sample` and `samples/rain-sample-minimal`.

Respect the dependency graph declared in the root `build.gradle.kts`. Keep public APIs in `com.gd.rain.<module>`, implementation details `internal`, and Spring wiring in `.autoconfigure`.

## Build, Test, and Development Commands

- `./gradlew test` runs unit tests across all modules.
- `./gradlew :rain-crud:test` runs one module's unit tests; replace the project path as needed.
- `./gradlew :rain-crud:integrationTest` runs that module's Docker-backed integration tier.
- `./gradlew check` performs the full gate: unit and integration tests, Spotless, Kover coverage, architecture, dependency-graph, tool-parity, and documentation checks.
- `./gradlew spotlessApply` formats Kotlin and Gradle Kotlin files.
- `./gradlew :samples:rain-sample:bootJar` builds the reference application; follow its README for the ordered Docker Compose startup.

Gradle provisions Java 25. Docker is required for integration tests and for clean jOOQ generation unless the documented `JOOQ_CODEGEN_SERVER_*` variables point to PostgreSQL.

## Coding Style & Naming Conventions

Spotless enforces ktlint formatting; use four-space indentation and avoid manual alignment. Compiler warnings are errors, and library modules use Kotlin explicit API mode, so declare public visibility and return types deliberately. Name modules `rain-<name>`, packages `com.gd.rain.<name>`, schemas `rain_<name>`, and migrations like `V1__jobs.sql`. Prefer deterministic, bounded operations; use the injected `Clock` instead of `Instant.now()` in decisions.

## Testing Guidelines

Tests use JUnit Jupiter, AssertJ, MockK, and Testcontainers. Name unit classes `*Test`; name integration classes `*IT` and annotate them with `@Tag("integration")` (sample end-to-end tests use `*E2E`). Each module's `coverage-bounds.properties` defines mandatory line and branch coverage. Database queries over growing tables require bounded-plan assertions; CRUD resources require a `CrudPlanProof` test.

## Commit & Pull Request Guidelines

History favors concise, single-purpose subjects with a scope, for example `rain-web: enforce origin rules` or `Docs: describe transport health`. Preserve phase labels such as `P4d:` only when working within that series. Pull requests should explain behavior and design choices, list verification commands, link relevant issues, and update migrations, module docs, or sample configuration when contracts change. Run `./gradlew check` before requesting review and call out any Docker-dependent checks not run.
