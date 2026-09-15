# rain-boot

What every rain application runs on: runtime roles and one-shot commands, the deployment stage, configuration
validation that reports every problem in one start-up, bean-time checks, seeding and the `runRain` entry point.

Every Spring module of rain depends on it, so an application gets it with the first module it adds. Name it
directly only in a Spring application that uses no other rain module.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-boot")
}
```

## What it contributes

Registered in `META-INF/spring.factories`, so they act before any bean exists:

| Contribution | What it does |
|---|---|
| `RainRuntimeEnvironmentPostProcessor` | runs after the configuration files are loaded; for a process started as a command it adds the property source `rain-command` first: `spring.main.web-application-type=none` and the properties the command declares |
| `RainConfigurationValidator` | validates the whole configuration on `ApplicationPreparedEvent` and throws one `ConfigurationProblemsException` with every fatal problem; problems that are not fatal are logged at WARN |
| `ConfigurationProblemsFailureAnalyzer` | prints the problems instead of a stack trace, with the action "State or correct the listed properties, then start the application again." |
| `CommandDeclaration`s | `config-check` and `seed` |

`RainRuntimeAutoConfiguration`, in every process:

| Bean | Condition | What it is |
|---|---|---|
| `clock` | no other `Clock` bean | `Clock.systemUTC()`; every rain module reads time from this bean |
| `deploymentStage` | — | the resolved `DeploymentStage` |
| `runtimeSelection` | — | the resolved `RuntimeSelection` |
| `rainBeanTimeValidator` | — | runs every `ConfigurationCheck` bean once all singletons exist |
| `commandOutput` | command mode, no other `CommandOutput` bean | `CommandOutput.SYSTEM` |
| `commandRunner` | command mode | runs the command's one `RainCommand` bean and hands its exit code to `SpringApplication.exit` |
| `commandHandlersCheck` | command mode | the command is answered by exactly one `RainCommand` bean |
| `configCheckCommand` | command `config-check` | see [commands](#commands) |
| `seedCommand` | command `seed` | see [commands](#commands) |
| `seedersCheck` | role `seeder` | seeder names are well formed and unique |

Roles, commands and why profiles are not used are described in
[runtime roles and commands](../concepts/runtime-roles-and-commands.md) and
[ADR 0003](../adr/0003-roles-not-profiles.md).

## Configuration

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `spring.application.name` | required | the name logs, metrics and probes carry | not blank |
| `rain.deployment.stage` | required | `dev`, `test` or `prod` | exactly one of the three names |
| `rain.runtime.roles` | exactly one of the two is required | the roles this process runs: a subset of `api`, `worker`, `seeder` | at least one role; every name known, matched exactly; no name twice; not together with `rain.runtime.command` |
| `rain.runtime.command` | | the one declared command this process runs | declared exactly once; not together with `rain.runtime.roles` |

Neither is ever inferred. A missing selection is reported at `rain.runtime`, naming every role and every declared
command. Per process they are usually stated as environment variables: `RAIN_DEPLOYMENT_STAGE`,
`RAIN_RUNTIME_ROLES` (comma separated) and `RAIN_RUNTIME_COMMAND`. In a configuration file the roles are one
comma-separated value (`roles: api,worker`); a YAML list is refused, because its elements (`rain.runtime.roles[0]`, …)
are keys no section declares.

Bean-time problems rain-boot reports:

| Path | Code | When |
|---|---|---|
| `rain.runtime.command` | `invalid` | a declared command no `RainCommand` bean answers, or a declaration whose name does not match `^[a-z][a-z0-9-]{0,63}$` |
| `rain.runtime.command` | `contradicts` | a command answered by two beans, or declared twice |
| `seeder:<name>` | `invalid` | a seeder name that does not match `^[a-z][a-z0-9.-]{0,127}$` |
| `seeder:<name>` | `contradicts` | two seeders with one name |

## Configuration validation

The order of the pass, the problem codes and how a refusal reads are in
[configuration](../concepts/configuration.md). What each rule is for:

- **Every problem in one start-up.** A start that names one problem at a time turns a correction into a series of
  restarts, and in a deployment every restart is a rollout. Independent checks are collected, never returned at
  the first failure.
- **A rule never reads what is already wrong.** A section's own problems and its file-borne secrets are checked
  only once the stage is known, and a cross-section rule runs only when every section it reads is bound. A rule
  that cannot run is reported `not_evaluated` and logged; it is not fatal, because an application that leaves a
  module out is not misconfigured.
- **A key nobody reads is refused.** A key under `rain.` that no section declares is a setting someone believes is in
  effect: a renamed member, a typo in indentation, a block copied from an older release. The start refuses it with
  the file and line it came from. Keys that come from environment variables are not judged, because an environment
  variable name does not map back to one property path. Under a map of scalars the whole remaining name is one key
  (`rain.jobs.workers.ticket.summarize` is key `ticket.summarize`); under a map of sections a key still has to
  name a member.
- **A production secret comes from the environment.** A configuration file travels — into images, into
  repositories, into support tickets. A leaf annotated `@RequiredFromEnvironment(DeploymentStage.PROD)` stated in a
  file in `prod` is refused, and the refusal never quotes the value. In the stages the annotation does not name, the
  file is accepted, so a workstation starts without exporting secrets.

### Declaring a section

```kotlin
@ConfigurationProperties("sample.attachments")
data class AttachmentProperties(
    val maxSize: DataSize,
    val scanner: ScannerProperties,
)

data class ScannerProperties(
    val url: String,
    @param:RequiredFromEnvironment(DeploymentStage.PROD)
    val token: String,
) : ConfigurationSection

class AttachmentConfigurationContributor : ConfigurationContributor {
    override val sections =
        listOf(
            SectionSpec("sample.attachments", AttachmentProperties::class, Presence.REQUIRED) { section, _ ->
                problems {
                    expect(section.maxSize.toBytes() > 0, "sample.attachments.max-size") {
                        "is ${section.maxSize.written()}; it has to be positive"
                    }
                }
            },
        )

    override val rules = listOf(AttachmentFitsTheBody())
}

class AttachmentFitsTheBody : CrossSectionRule {
    override val id = "sample.attachment-fits-the-body"
    override val reads = setOf("sample.attachments", "rain.web")

    override fun evaluate(
        sections: BoundSections,
        stage: DeploymentStage,
    ): RuleOutcome {
        val attachments = sections.get("sample.attachments", AttachmentProperties::class)
        val web = sections.get("rain.web", RainWebProperties::class)
        if (attachments.maxSize <= web.bodyLimit) return RuleOutcome.Satisfied
        return RuleOutcome.Violated(
            listOf(
                ConfigurationProblem(
                    "sample.attachments.max-size",
                    ProblemCode.CONTRADICTS,
                    "is ${attachments.maxSize.written()}, above rain.web.body-limit ${web.bodyLimit.written()}",
                ),
            ),
        )
    }
}
```

```properties
# src/main/resources/META-INF/spring.factories
com.gd.rain.boot.config.ConfigurationContributor=\
com.example.AttachmentConfigurationContributor
```

| Type | Role |
|---|---|
| `ConfigurationContributor` | `sections` and `rules`; read from `spring.factories`, sorted by class name |
| `SectionSpec(prefix, type, presence, validate)` | one `@ConfigurationProperties` type at `prefix` (`^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*$`); `validate` receives the bound value and the stage |
| `Presence.REQUIRED` / `Presence.OPTIONAL` | whether at least one key under the prefix has to be stated |
| `ConfigurationSection` | marks a member type whose members are leaves of the same section; the validator descends into it (or into a member annotated `@NestedConfigurationProperty`) |
| `RequiredFromEnvironment(vararg stages)` | on a constructor parameter: in those stages the value comes from an environment variable |
| `CrossSectionRule` | `id`, `reads` (section prefixes), `evaluate` → `RuleOutcome.Satisfied` or `RuleOutcome.Violated(problems)` |
| `BoundSections` | the bound values by prefix |
| `ConfigurationCheck` | a problem that needs beans; any bean of this type runs once all singletons exist |
| `RainConfigurationValidator.validate(environment, contributors, declarations)` | the pass as a function, returning `ValidationReport(problems)` with `fatal` and `notEvaluated`; for tests |
| `DataSize.written()`, `Duration.written()` | a value the way a configuration file states it: the largest binary unit that divides a size exactly (`16KB`, `0B`), ISO-8601 for a duration |

The `@ConfigurationProperties` type is still registered with Spring (`@EnableConfigurationProperties`) by the
application or module that uses it; a `SectionSpec` declares it to validation.

## Roles, stage and commands

| Type | Role |
|---|---|
| `RuntimeRole` | `API`, `WORKER`, `SEEDER`; `wire` is the configured name |
| `DeploymentStage` | `DEV`, `TEST`, `PROD`; `resolve(environment)` answers `Resolved` or `Invalid` |
| `RuntimeSelection` | `Roles`, `Command` or `Invalid`; `resolve(environment, declarations)` is a pure function, so the post-processor, the validator and every condition reach the same answer; `activeRoles(selection)` throws the problems of an invalid selection |
| `@ConditionalOnRainRole(vararg anyOf)` | matches when any named role is active — stated, or declared by the running command |
| `@ConditionalOnRainCommand(vararg names)` | matches when the process runs as one of the named commands, or as any command when none is named |
| `CommandDeclaration` | `name`, `description`, `roles`, `properties`; registered in `spring.factories` |
| `RainCommand` | `name` and `run(arguments, output): Int` |
| `CommandOutput(out, err)` | the result on `out`, progress and counts on `err` |
| `Seeder` | `name`, `order`, `seed()`; runs again on every deployment that runs `seed`, so it is idempotent |
| `Rain.run(application, args)` / `runRain<T>(args)` | the entry point |

Both conditions fail the context with the selection's problems instead of not matching an invalid selection.

```kotlin
@SpringBootApplication
class HelpdeskApplication

fun main(args: Array<String>) {
    runRain<HelpdeskApplication>(args)
}
```

Started with roles, `runRain` returns the running context. Started as a command, it closes the context after the
command ran and calls `exitProcess` with the command's exit code. A command returns 0 to 125; any other value fails
the command.

```kotlin
@Bean
fun agentsSeeder(agents: AgentDirectory): Seeder =
    object : Seeder {
        override val name = "helpdesk.agents"
        override val order = 10

        override fun seed() {
            agents.ensure("support")
        }
    }
```

## Commands

| Command | Roles | Declared properties | Output | Exit |
|---|---|---|---|---|
| `config-check` | none | none | `configuration: ok (stage=<stage>)` on `out` | 0; a refused configuration fails the start and never reaches the command |

`config-check` runs with no role and no web server, so the checks that exist only in a role or in a servlet application
do not run in it ([runtime roles and commands](../concepts/runtime-roles-and-commands.md#commands)).
| `seed` | `seeder` | none | `seeding: registered=<n>`, then `seeded: <name>` per seeder, then `seeding complete: ran=<n>`, on `err` | 0; a seeder that throws fails the command |

`seed` runs every `Seeder` bean by ascending `order`, then `name`.

## Error codes, health checks, schema

None.

## What it does not do

- It never reads Spring profiles to decide the stage, the roles or the command, and it never defaults them.
- It does not scan components; every rain bean comes from an auto-configuration listed in
  `AutoConfiguration.imports`.
- It does not judge keys that come from environment variables.
- It ships no Spring configuration metadata (`spring-configuration-metadata.json`); what is checked is what the
  sections declare to validation.
