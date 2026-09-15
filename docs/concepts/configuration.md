# Configuration

rain validates the whole configuration **before any bean exists** and reports **every** problem in one start-up. A
configuration with three mistakes costs one restart, not three.

## What is always required

| Property | Why |
|---|---|
| `spring.application.name` | logs, metrics and probes are named after it |
| `rain.deployment.stage` | `dev`, `test` or `prod`; decides stage rules; never inferred from Spring profiles |
| `rain.runtime.roles` or `rain.runtime.command` | see [runtime roles and commands](runtime-roles-and-commands.md) |

Each module adds its own sections, e.g. `rain.persistence.statement-timeout` is required as soon as rain-persistence
is on the classpath.

## How a refusal reads

```
the configuration has 3 problems:
  - rain.deployment.stage [required]: no value is provided; state one of dev, test, prod
  - rain.web.body-limit [invalid]: is 0B; it has to be positive (from class path resource [application.yml] - 12:17)
  - rain.jobs.workers.ticket.sumarize [unknown_key]: no rain section declares this key
```

Problem codes:

| Code | Meaning | Fatal |
|---|---|---|
| `required` | a value the deployment has to state is missing | yes |
| `invalid` | a stated value is not acceptable | yes |
| `contradicts` | two stated values cannot both hold | yes |
| `unknown_key` | a key under `rain.` that no section declares | yes |
| `exclusive` | two settings of which exactly one may be stated | yes |
| `not_evaluated` | a rule reads a section this application does not have; logged, never silently skipped | no |

## Declaring a section

A module or an application contributes sections through `ConfigurationContributor`, registered in
`META-INF/spring.factories` under `com.gd.rain.boot.config.ConfigurationContributor`:

```kotlin
@ConfigurationProperties("sample.seed")
data class SeedProperties(
    val agents: List<SeedAgent>,
    val initialPassword: Secret,
)

data class Secret(
    @param:RequiredFromEnvironment(DeploymentStage.PROD)
    val value: String,
) : ConfigurationSection

class SampleConfigurationContributor : ConfigurationContributor {
    override val sections = listOf(
        SectionSpec("sample.seed", SeedProperties::class, Presence.OPTIONAL) { seed, _ ->
            problems {
                expect(seed.agents.isNotEmpty(), "sample.seed.agents") { "names no agent" }
            }
        },
    )
}
```

The validator, in one pass:

1. resolves the runtime selection and the stage;
2. binds every declared section with Spring Boot's binder; a section that cannot be bound reports each missing leaf
   by its property path (descending into members whose type implements `ConfigurationSection` or that are annotated
   `@NestedConfigurationProperty`), or the conversion failure with its source;
3. runs each bound section's own problems (only once the stage is known);
4. checks `@RequiredFromEnvironment` leaves: in the named stages the value must come from an environment variable;
5. runs cross-section rules (`CrossSectionRule`); a rule whose sections are not all bound is reported as
   `not_evaluated` and never run;
6. reports keys under `rain.` that no section declares. Keys from environment variables are not judged, because an
   environment variable name does not map back to one property path.

A section prefix declared twice is a contradiction.

## Problems that need beans

Some problems can only be found once beans exist — a job definition without a configured worker ceiling, a declared
command nobody answers. They are `ConfigurationCheck` beans, run once all singletons exist and before the application
serves, and reported through the same model and failure analyzer.

## Defaults

A declared default is part of the contract and documented with its section (for example
`rain.persistence.retry.attempts = 3`). Anything that has no universally sound value — timeouts that bound every
statement, the stage, the runtime selection, pool names — has no default and is required.
