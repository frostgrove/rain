# rain-observability

What a rain process answers liveness and readiness from: health checks probed concurrently against absolute budgets,
one shared evaluation per freshness window, draining from the moment the context closes, the same readings published
to Actuator, the `database` check, and the OpenTelemetry Logback bridge when OTLP log export is on.

rain-web depends on it and serves the probes. Add it directly only to an application without rain-web that needs the
`HealthRegistry`.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-observability")
}
```

It brings rain-boot and Boot's `spring-boot-health`. The OpenTelemetry Logback appender, the OpenTelemetry API and
Logback are optional: the application brings them when it exports logs.

## What it contributes

`RainHealthAutoConfiguration` — after `RainRuntimeAutoConfiguration` and Boot's `DataSourceAutoConfiguration`, in
every process:

| Bean | Condition | What it is |
|---|---|---|
| `healthRegistry` | no other `HealthRegistry` bean | `HealthRegistry` over every `HealthContribution` bean and every `HealthCheck` bean at its stated importance |
| `healthDrainingListener` | — | turns readiness to `draining` when this context starts closing |
| `healthCheckImportanceCheck` | — | every check this process runs has a stated importance |
| `databaseHealthCheck` | a `DataSource` bean | `DatabaseHealthCheck` |

`RainActuatorHealthAutoConfiguration` — when Actuator's `HealthContributorRegistry` is on the classpath and is a bean:
`actuatorHealthBridge` registers one indicator per contribution under the contribution's name.

`RainOpenTelemetryLoggingAutoConfiguration` — when the OpenTelemetry Logback appender, Logback and the OpenTelemetry
API are on the classpath and `management.logging.export.otlp.enabled=true`: `openTelemetryAppenderInstaller`.

## Configuration

`rain.health` is optional; `rain.health.checks.<name>` is required for every check the process runs.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.health.check-timeout` | `2s` | the budget of a check that states none, counted from the start of the evaluation | positive |
| `rain.health.freshness` | `1s` | how long one evaluation is served before the next caller evaluates again | positive |
| `rain.health.checks.<name>` | required for each check this process runs | `required`, `degrading`, `informational` or `disabled` | the name matches `^[a-z][a-z0-9._-]{0,127}$` |

Bean-time problems:

| Path | Code | When |
|---|---|---|
| `rain.health.checks.<name>` | `required` | the process runs the check and no importance is stated |
| `rain.health.checks.<name>` | `contradicts` | the entry names a `HealthContribution`, which states its own importance |
| `rain.health.checks.<name>` | `not_evaluated` | the entry names a check this process does not run; logged, not fatal, because one configuration serves every role |
| `health:<name>` | `invalid` / `contradicts` | a name or code that does not match its pattern, a name or code registered twice, a timeout that is not positive — all reported at once |

## Health checks

| Check | Code | Runs in | Fails when |
|---|---|---|---|
| `database` | `database` | any process with a `DataSource` bean | a borrowed connection does not answer `Connection.isValid` within the check timeout |

`isValid` takes whole seconds and treats 0 as "no timeout", so the driver is given the budget rounded up; the registry
still cuts the check at the budget itself. A validation query is not used: it answers a different question than
whether the pool can hand out a working connection.

The checks of other modules are listed in [transport and health](../concepts/transport-and-health.md#probes).

## API

| Type | What it is |
|---|---|
| `HealthCheck` | `name`, `code`, `timeout`, `probe()`: a check a module or an application offers without deciding how much it matters |
| `HealthContribution` | the same, carrying its own `importance`; an entry under `rain.health.checks` that names one is refused |
| `Importance` | `REQUIRED`, `DEGRADING`, `INFORMATIONAL`, `DISABLED` |
| `HealthRegistry` | `live()`, `ready()`, `inspect()`, `contributions()`, `isDraining`, `startDraining()` |
| `ReadinessReport(status, failing)` | the public answer: `ready`, `degraded`, `not_ready` or `draining`, and the sorted public codes of failing required and degrading checks |
| `HealthDetail`, `CheckDetail` | the private view of one evaluation, with each check's state, message and duration; `reading(name)` is a map lookup |
| `HealthCache(freshness, clock, evaluate)` | one evaluation per window; an evaluation in flight is joined, not repeated |
| `HealthChecks.contributions(checks, stated)` | turns checks into contributions at their stated importance |
| `HealthContributionIndicator` | the Actuator indicator of one contribution |
| `OpenTelemetryAppenderInstaller` | the Logback bridge |

`probe()` signals a failure by throwing. The message goes to the detail and the log, cut at 256 UTF-8 bytes on a
character boundary; only the `code` reaches the public report. A check with a null `code` moves the status without
being named.

```kotlin
@Bean
fun searchIndexHealthCheck(index: SearchIndex): HealthCheck =
    object : HealthCheck {
        override val name = "search.index"
        override val code = "search"
        override val timeout: Duration? = null

        override fun probe() {
            check(index.isOpen()) { "the search index is closed" }
        }
    }
```

```yaml
rain:
  health:
    checks:
      database: required
      search.index: degrading
```

### Why the application states importance

The same ping is required where every request needs the dependency and degrading where the dependency only makes
answers better; the checker cannot know which, and the application being assembled does. A degrading dependency
keeps the process in rotation on purpose: every replica shares the dependency, and taking replicas out for it would
remove the last ones still serving everything that does not need it.

### Evaluation

The checks of one evaluation run concurrently on virtual threads. Each is cut at an absolute deadline counted from
the start of the evaluation. A check that throws or does not answer in time is failing; a probe never takes the
process down. An interrupt of the evaluating thread does not cut the evaluation short, because other callers share
it. From the moment the context starts closing, `ready()` answers `draining` without asking any dependency, so a load
balancer stops sending traffic before pools close.

### Actuator

Actuator's health endpoint reads the same evaluation; an indicator never probes. Its status table:

| Reading | Actuator status |
|---|---|
| passing | `UP` |
| disabled | `UNKNOWN` |
| failing, required | `DOWN` |
| failing, degrading | `DEGRADED` |
| failing, informational | `UP` |

Each indicator carries the details `code`, `importance` and, when failing, `error`. How `DEGRADED` ranks and which HTTP
status it maps to is the application's Actuator configuration
(`management.endpoint.health.status.order`, `management.endpoint.health.status.http-mapping`).

## Logs

Spring Boot configures the OpenTelemetry SDK and the OTLP log exporter but installs no bridge from Logback into it;
without one, `management.logging.export.otlp.enabled=true` exports nothing the application logs. The installer adds an
`OpenTelemetryAppender` named `OTEL` to the root logger once, captures every MDC entry and every key-value pair of a log
event as attributes, and hands the appender the `OpenTelemetry` Boot built. SLF4J bound to anything but Logback while
export is enabled refuses the start.

A request's log lines are correlated by the MDC key `request_id` that rain-web's request log sets for the duration of
the request; with export on it travels as an attribute of every record.

## Service levels

A threshold is a number with its justification: measured on this code base, or a target that becomes measured once
the code that meets it exists — and each one names the check that verifies it. The signals rain provides to build
alerts on:

| Signal | Where |
|---|---|
| readiness status and `failing` codes | `/ready`, Actuator health |
| failing checks with their messages | the WARN line `a readiness check failed` rain-web logs while not ready or degraded |
| dead and failed jobs | `JobAdministration.deadLetters` ([rain-jobs](jobs.md)) |
| attempts whose scheduler thread stopped waiting while the body still runs | gauge `rain.jobs.attempts.wedged`, tag `profile`, when a `MeterRegistry` bean exists |
| a breaker withholding calls | the `breaker.<name>` check ([rain-resilience](resilience.md)) |

## Scale guarantees

- One evaluation per freshness window, whatever the number of probes, load balancers and dashboards reading it.
- Every check is bounded by its budget; an evaluation lasts at most its longest budget.
- An Actuator indicator finds its reading by name in a map; no reader passes over every check.

## Error codes, schema, commands

None.

## What it does not do

- It never decides a check's importance, and `/live` asks no dependency.
- It never serves a failure message to an unauthenticated caller.
- It does not configure the OpenTelemetry SDK, exporters, traces or metrics; those are Spring Boot's.
