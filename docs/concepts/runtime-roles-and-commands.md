# Runtime roles and commands

A rain process is started **either** with roles **or** as one command. Exactly one of the two is stated, and there
is no default: a process that guessed it was a worker is the failure this exists to prevent.

```yaml
rain:
  deployment:
    stage: prod          # dev | test | prod — required, independent of Spring profiles
  runtime:
    roles: [api, worker] # or: command: migrate
spring:
  application:
    name: helpdesk       # required
```

## Roles

| Role | What it activates |
|---|---|
| `api` | request serving: routes, transport filters, listeners that feed responses |
| `worker` | background consumption: job schedulers, lease renewal, housekeeping, recurring work |
| `seeder` | the seeders (normally activated by the `seed` command) |

The set is closed. An unknown role, an empty list, a role named twice, or roles stated together with a command is a
startup refusal that names the problem. Role names are matched exactly (`API` is not `api`).

Contributions are gated with `@ConditionalOnRainRole(RuntimeRole.API)` on configuration classes or bean methods.
The condition never quietly fails to match an invalid selection: it fails the context with the problems that make
the selection invalid.

What a role gates is **consumption**, not existence: a process with only `api` can still enqueue jobs (the queue
client exists in every role); it simply runs no scheduler.

## Commands

A command is a one-shot process: no web server, one piece of work, then exit with that work's exit code.

| Command | Module | Roles it activates | What it does |
|---|---|---|---|
| `config-check` | rain-boot | none | starts the application (which runs every configuration and bean-time check) and exits 0 |
| `seed` | rain-boot | `seeder` | runs every `Seeder` by `order`, then `name`, and exits 0 |
| `migrate` | rain-persistence | none | enables Flyway for this process, applies module and application migrations, reports them, exits 0 |

A command is declared in `META-INF/spring.factories` under `com.gd.rain.boot.runtime.CommandDeclaration` (so it is
known before any bean exists) and answered by exactly one `RainCommand` bean with the same name:

```kotlin
class ReportDeclaration : CommandDeclaration {
    override val name = "ticket-report"
    override val description = "print a page of tickets and exit"
    override val roles = emptySet<RuntimeRole>()
    override val properties = emptyMap<String, String>()
}

@Bean
@ConditionalOnRainCommand("ticket-report")
fun ticketReport(tickets: TicketQueries): RainCommand = object : RainCommand {
    override val name = "ticket-report"
    override fun run(arguments: ApplicationArguments, output: CommandOutput): Int {
        tickets.firstPage().forEach { output.out.println(it) }
        return 0
    }
}
```

The properties a declaration lists take precedence over the deployment's: `migrate` sets `spring.flyway.enabled=true`
for itself, so serving processes can keep migration off. Every command also runs with
`spring.main.web-application-type=none`.

A declared command answered by no bean, or by two, is a startup refusal. A command name that is not declared is
refused with the list of declared names.

## Entry point

```kotlin
@SpringBootApplication
class HelpdeskApplication

fun main(args: Array<String>) {
    runRain<HelpdeskApplication>(args)
}
```

Started with roles, `runRain` returns the running context. Started as a command, it closes the context after the
command ran (so pools and log exporters flush) and exits with the command's code.
