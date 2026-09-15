# ADR 0003 — Runtime roles and commands, not Spring profiles

Status: accepted

## Context

One application image runs as several processes: a request server, background workers, one-shot migration and
seeding. Selecting what a process does with Spring profiles (`@Profile("worker")`) has failure modes that are silent:

- a missing or misspelled profile activates nothing and the process starts, doing less than intended;
- profiles also select configuration files, so "which configuration" and "which work" are coupled;
- a default profile makes a process that was given no instruction behave as some role;
- a deployment stage inferred from profiles makes production behaviour depend on a naming convention.

## Decision

- `rain.runtime.roles` (a subset of `api`, `worker`, `seeder`) or `rain.runtime.command` (one declared command) is
  required; exactly one of them. Unknown names, an empty set or both are start-up refusals.
- Beans are gated with `@ConditionalOnRainRole` / `@ConditionalOnRainCommand`, which fail the context on an invalid
  selection instead of quietly not matching.
- Commands are declared in `spring.factories` (known before any bean exists), answered by exactly one `RainCommand`
  bean, run without a web server, and exit with their code.
- `rain.deployment.stage` (`dev`, `test`, `prod`) is required and independent of profiles.

Spring profiles stay available to applications for selecting configuration documents.

## Consequences

- A process never runs work nobody asked it to run, and never silently runs less.
- Tests that start a context state their roles; there is no implicit test role.
- Deployments state three properties they previously could leave out.
