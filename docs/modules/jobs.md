# rain-jobs

The durable job queue. db-scheduler core polls and delivers; rain's own ledger in schema `rain_jobs` owns the attempt
lease, the fence for effects, deduplicating reservations, retries into dead letters, redrive, cancellation by subject
and retention.

Add it when work has to survive the request that ordered it: work that is slow, that calls a dependency that may be
down, or that runs on a schedule.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-jobs")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
```

It brings [rain-persistence](persistence.md), [rain-observability](observability.md) and db-scheduler. Building
rain-jobs itself generates its jOOQ code from its migration.

## Concepts

| Declared by the application as a bean | What it is |
|---|---|
| `JobProfile` | a service class: attempt timeout, step timeout, backoff, retries, deferrals, retention; one db-scheduler scheduler per profile |
| `JobDefinition<P>` | one kind of work: a unique name, its profile and its payload type |
| `JobHandler<P>` | the implementation of exactly one definition |
| `RecurringWork` | work owned by the cluster, run by exactly one worker per interval |

rain ships no profile and no definition. The client side — `WorkQueue`, `JobAdministration`, the catalogue and its
checks — exists in every role, so a process with only `api` enqueues. The worker side — schedulers, the lease renewer,
the reaper, retention and recurring work — exists only in the `worker` role.

## What it contributes

`RainJobsAutoConfiguration` — after Boot's jOOQ and transaction-manager auto-configuration and
`RainPersistenceAutoConfiguration`, when a `DataSource`, a `DSLContext` and a `PlatformTransactionManager` exist.

Every role:

| Bean | Condition | What it is |
|---|---|---|
| `jobCatalog` | — | the declared profiles and definitions |
| `jobCatalogCheck` | — | the catalogue is consistent and every definition has exactly one worker ceiling |
| `jobPayloadCodec` | no other `JobPayloadCodec` bean | `JacksonJobPayloadCodec`: Jackson 3 with the Kotlin module and Jackson's defaults |
| `jobsErrorCodes` | — | `JobsErrorCodes` |
| `jobsFaultTranslator` | — | `JobsFaultTranslator` |
| `workQueue` | no other `WorkQueue` bean | enqueues through rain's ledger and db-scheduler's client |
| `jobAdministration` | no other `JobAdministration` bean | dead letters, redrive, cancellation, definitions |

Role `worker`:

| Bean | What it is |
|---|---|
| `attemptStatementTimeout` | a transaction listener that bounds every transaction an attempt opens |
| `jobWorkerCheck` | one handler per definition; recurring work agrees with `rain.jobs.required-recurring` |
| `connectionDemandCheck` | the worker's connection demand fits the pool |
| `jobsHealthCheck` | the `jobs` health check |
| `jobsWorker` | the one lifecycle of the lease renewer and every scheduler; starts after the web server and stops before it |

## Configuration

`rain.jobs` is required whenever rain-jobs is on the classpath.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.jobs.workers.<definition>` | required for every declared definition | how many attempts of the definition one process runs at once; the profile's scheduler has the sum as threads | at least 1; a key naming no declared definition is `unknown_key`; a definition without a key is `required` |
| `rain.jobs.required-recurring` | required; an empty list is a statement | the recurring work this deployment runs, the application's and its modules' | names match `^[a-z][a-z0-9.-]{0,127}$`, none twice; in the worker role it equals the names of the `RecurringWork` beans, a module's included |
| `rain.jobs.drain-grace` | required | how long a stopping worker waits for running attempts, all schedulers together | positive |
| `rain.jobs.reserved-connections` | required | pool connections kept for everything that is not a job scheduler | not negative |
| `rain.jobs.lease.ttl` | `60s` | how long an attempt's lease lasts without renewal | positive |
| `rain.jobs.lease.renew-interval` | `15s` | how often the renewer extends every held lease | positive; at most a third of `lease.ttl`, so a lease survives two missed renewals |
| `rain.jobs.scheduler.poll-interval` | `1s` | db-scheduler's poll for due work | positive |
| `rain.jobs.scheduler.heartbeat-interval` | `15s` | db-scheduler's heartbeat | positive |
| `rain.jobs.scheduler.missed-heartbeats` | `4` | missed heartbeats before db-scheduler treats an execution as dead | at least 1 |
| `rain.jobs.reaper.interval` | `15s` | how often lapsed leases are reaped | positive |
| `rain.jobs.reaper.batch` | `100` | rows one reaper pass takes | at least 1 |
| `rain.jobs.retention.interval` | `1h` | how often retention runs | positive |
| `rain.jobs.retention.batch` | `1000` | rows one delete statement removes | at least 1 |
| `rain.jobs.retention.run-budget` | `30s` | a pass starts no new batch after this long | positive |
| `rain.jobs.health.max-poll-age` | `30s` | a started scheduler that has not finished a poll for longer is failing | more than `scheduler.poll-interval` |

In the worker role `spring.datasource.hikari.maximum-pool-size` has to be stated, and has to cover the worker's demand:

| Term | Connections |
|---|---|
| profile threads | Σ `rain.jobs.workers.*` |
| recurring threads | 2 (rain's reaper and retention) + the application's `RecurringWork` beans |
| db-scheduler | 4 per scheduler (its due poller and three housekeeper threads); one scheduler per profile, plus one for recurring work |
| lease renewer | 1 |
| everything else | `rain.jobs.reserved-connections` |

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 40
rain:
  jobs:
    workers:
      ticket.summarize: 4
    required-recurring: [ticket.escalation-sweep]
    drain-grace: 30s
    reserved-connections: 10
  health:
    checks:
      jobs: degrading
```

Here one profile needs 4 + 3 + 2 × 4 + 1 + 10 = 26 connections. A refusal names every term.

Bean-time problems:

| Path | Code | When |
|---|---|---|
| `jobs:profile:<id>` | `contradicts` | a profile id declared twice |
| `jobs:definition:<name>` | `contradicts` | a definition name declared twice, or answered by two handlers |
| `jobs:definition:<name>` | `invalid` | a definition naming an undeclared profile, or a handler answering for a different definition object with that name |
| `jobs:definition:<name>` | `required` | a definition without a handler (worker role) |
| `rain.jobs.workers.<name>` | `required` / `unknown_key` / `invalid` | see the table above |
| `jobs:recurring:<name>` | `invalid` | a malformed name, or an interval that is not positive |
| `jobs:recurring:<name>` | `contradicts` | a name declared twice, or taking the name of rain's own recurring work or of a job definition |
| `rain.jobs.required-recurring` | `contradicts` | a name no bean contributes, or a bean not named |
| `spring.datasource.hikari.maximum-pool-size` | `required` / `contradicts` | not stated, or below the demand |
| `rain.jobs.connection-demand` | `not_evaluated` | the declarations have problems, so the demand is not known |

## API

### Declaring work

```kotlin
data class SummarizeTicket(
    val ticketId: UUID,
)

@Configuration(proxyBeanMethods = false)
class TicketJobs {
    @Bean
    fun standardProfile(): JobProfile =
        JobProfile(
            id = "standard",
            attemptTimeout = Duration.ofMinutes(5),
            stepTimeout = Duration.ofSeconds(30),
            backoff = BackoffLadder(initial = Duration.ofSeconds(5), maximum = Duration.ofMinutes(10)),
            retries = 5,
            deferrals = 50,
            retention = Duration.ofDays(14),
        )

    @Bean
    fun summarizeTicket(): JobDefinition<SummarizeTicket> = JobDefinition.of("ticket.summarize", "standard")

    @Bean
    fun summarizeTicketHandler(
        definition: JobDefinition<SummarizeTicket>,
        summaries: TicketSummaries,
    ): JobHandler<SummarizeTicket> = SummarizeTicketHandler(definition, summaries)

    @Bean
    fun escalationSweep(escalation: TicketEscalation): RecurringWork =
        object : RecurringWork {
            override val name = "ticket.escalation-sweep"
            override val interval: Duration = Duration.ofMinutes(5)

            override fun run() {
                escalation.escalateOverdue()
            }
        }
}

class SummarizeTicketHandler(
    override val definition: JobDefinition<SummarizeTicket>,
    private val summaries: TicketSummaries,
) : JobHandler<SummarizeTicket> {
    override fun handle(
        payload: SummarizeTicket,
        attempt: Attempt,
    ) {
        val draft = attempt.step(Duration.ofMinutes(2)) { summaries.draft(payload.ticketId) }
        attempt.fenced(listOf(Exclusively(keyOf("ticket", payload.ticketId.toString())))) {
            summaries.store(payload.ticketId, draft)
        }
    }
}
```

| Type | Rules |
|---|---|
| `JobProfile` | `id` matches `^[a-z][a-z0-9-]{0,63}$`; timeouts and retention positive; `stepTimeout` ≤ `attemptTimeout`; `retries` and `deferrals` not negative; an invocation has `retries + 1` attempts |
| `BackoffLadder(initial, maximum)` | before retry `spent + 1` the ceiling is `initial · 2^spent` capped at `maximum`; the delay is drawn uniformly from `[initial, ceiling]` (full jitter with the first rung as floor) |
| `JobDefinition<P>(name, profile, payloadType)` | `name` matches `^[a-z][a-z0-9.-]{0,127}$`; it is the db-scheduler task name, `job_invocation.definition` and the key under `rain.jobs.workers`; `JobDefinition.of<P>(name, profile)` |
| `JobHandler<P>` | `definition` and `handle(payload, attempt)`; runs on a virtual thread of its own |
| `RecurringWork` | `name` (same pattern, shares the task-name space with definitions), `interval`, `run()` |

### Ordering work

```kotlin
queue.enqueue(
    summarizeTicket,
    SummarizeTicket(ticket.id),
    EnqueueOptions(
        dedupe = Dedupe.Unique("ticket:${ticket.id}"),
        priority = JobPriority(10),
        subjectKey = SubjectKey("ticket:${ticket.id}"),
    ),
)
```

| Type | Rules |
|---|---|
| `WorkQueue.enqueue(definition, payload, options)` | joins the caller's transaction when there is one — the order, its reservation and its db-scheduler execution commit or roll back with the caller's change — and otherwise commits on its own; answers `EnqueueOutcome.Scheduled(invocation)` or `Deduplicated(invocation)` |
| `EnqueueOptions(dedupe, priority, after, subjectKey)` | `dedupe` and `priority` are always stated; `after` delays eligibility and is not negative |
| `Dedupe.None`, `Dedupe.Unique(key)`, `Dedupe.Collapse(key)` | a key is 1 to 512 non-blank characters |
| `JobPriority(value)` | fits a `SMALLINT`; a higher value runs first |
| `SubjectKey(value)` | 1 to 256 non-blank characters |

A definition that is not the declared bean with that name is refused with `UnknownJobDefinitionException`. The payload is
stored as JSON in `job_invocation.payload`, through `JobPayloadCodec`.

**Deduplication.** A `Unique` reservation is held until the invocation reaches a terminal state: an order made while the
first is queued or running is absorbed into it. A `Collapse` reservation is released when a worker claims the
invocation, before the handler reads anything: it absorbs only orders made while the job had not started looking. The
reservation is taken first, with `INSERT … ON CONFLICT DO NOTHING` on a partial unique index, so a concurrent duplicate
never raises `23505` into the caller's transaction. An absorbed order writes no invocation row; it increments the
holder's `absorbed_count`. A key released between the conflicting insert and the absorption is placed again; after three
such rounds the order is refused with `IntentConflictException`, which renders as `503 job_intent_conflict`.

### The attempt

`Attempt.meta` is `AttemptMeta`: invocation, definition, profile, attempt number (1 for the first), retries spent and
allowed, deferrals, the attempt's deadline, the subject key, and `lastChargedAttempt`.

| Method | Behaviour |
|---|---|
| `step(budget) { }` | every transaction opened inside is bounded by `min(budget, deadline − now)`; refuses to start when the attempt is revoked (`LeaseLostException`, `AttemptInterruptedException`) or has no time left (`AttemptTimeoutException`) |
| `fenced(guards) { }` | a new transaction, its statements bounded, the advisory locks taken, then the invocation row locked and checked — same lease token, still running, lease unexpired — and only then the effect; an effect never commits under a lease another attempt holds |

Outside an explicit step, every transaction an attempt opens is bounded by the profile's `stepTimeout`, capped by what is
left of the attempt, and never less than 1 ms (PostgreSQL reads 0 as no limit). A work queue called inside `fenced` joins
the fence's transaction, so ordering the next step commits with the effect that finished this one.

How an attempt ends:

| The handler | Result | Retry charged |
|---|---|---|
| returns | `succeeded` | — |
| throws `JobPermanentException` | `failed`, code `permanent` | no |
| receives a payload the codec cannot read | `failed`, code `payload_unreadable` | no |
| throws `JobDeferredException(after)` | queued again after `after`; once `deferrals` deferrals are spent, `dead` with `deferrals_exhausted` | no |
| throws anything else | queued again after the backoff, code `failed`; with no retry left, `dead` | yes |
| does not finish by the deadline | the body is interrupted; as above with code `attempt_timeout` | yes |
| opens a transaction whose bound cannot be set | the transaction cannot commit; as above with code `statement_bound_failed` | yes |
| is interrupted because the process stops | queued again, due at once | no |
| has lost its lease | nothing is written by this attempt | no |

A handler is idempotent: an attempt whose effect committed and whose completion write did not runs again. Deferral is
the answer for "not now" — a dependency that is down — so an outage does not spend the retries of every invocation
waiting for it; a refusal retrying cannot fix is permanent.

A handler and a `RecurringWork` are adapters. The work lives in a class called directly, with its own tests and no queue
or clock; the adapter reads the payload, calls it, and maps its outcome onto the table above.

### Leases and the reaper

A claim is one statement: the queued, eligible invocation of the execution's generation becomes `running` with a fresh
lease token and an expiry of `lease.ttl`, and a collapse reservation is released. Every terminal or requeue write is
guarded by the token; a write that finds another owner writes nothing, and the db-scheduler execution is removed or
rescheduled from where the invocation is.

One lease renewer per worker process extends every held lease in one statement every `lease.renew-interval`, including
in the middle of a long step:

- a lease the database no longer knows (token replaced, no longer running) is revoked at once; the attempt is
  interrupted and ends uncharged;
- a lease whose row another transaction has locked — a fenced effect in flight — is renewed next round;
- a round that gets no answer revokes only the leases whose last renewal plus `lease.ttl` has passed.

The reaper is rain's recurring work `rain.jobs.reaper`. Each pass locks up to `reaper.batch` running invocations whose
lease lapsed, `FOR UPDATE SKIP LOCKED`: one of a definition this application does not declare is `dead` with
`unknown_definition` and never retried under an assumed profile; one with no retry left is `dead` with `lease_expired`;
the rest are queued again after the backoff, one retry charged.

### Shutdown

`JobsWorker` starts the renewer, then each scheduler; a scheduler that fails to start stops the ones already started.
It stops every scheduler at once, each on a thread of its own, together bounded by `drain-grace`, and the renewer last,
so draining attempts keep their leases. An attempt interrupted by the stop is released uncharged.

### Recurring work

Each `RecurringWork` is one db-scheduler recurring task with a fixed delay of its `interval`, so exactly one worker in
the cluster runs it per interval. All recurring work shares one scheduler, `recurring`, with one thread per task. rain's
own are `rain.jobs.reaper` and `rain.jobs.retention`.

Those two are never named in `rain.jobs.required-recurring`; every `RecurringWork` bean is, whichever module contributes
it. [rain-access](access.md#recurring-work-commands) contributes `access.session-retention` to every worker, and
`access.revocation-replay` when `rain.access.revocation.store` is `redis`, so the worker of an application with rain-access
and a Redis revocation list states them beside its own, as `samples/rain-sample` does:

```yaml
rain:
  jobs:
    required-recurring: [ticket.escalation-sweep, access.session-retention, access.revocation-replay]
```

### Retention

`rain.jobs.retention` deletes, per declared profile, terminal invocations and released reservations older than the
profile's `retention`, in batches of `retention.batch` rows per statement, and starts no batch once `retention.run-budget`
is spent; the next pass continues. Rows of a profile the application no longer declares have no declared retention and
are kept, never silently: each pass reports and logs every such profile, one index seek per distinct profile. Declaring a
`JobProfile` with that id makes its rows expire again.

### Administration

| Method | Behaviour |
|---|---|
| `deadLetters(definition, after, limit)` | one keyset page of `failed` and `dead` invocations, newest first by `(finished_at, id)`, optionally of one definition; `limit` 1 to 500; no total |
| `redrive(invocation)` | in one transaction: resolves the definition, takes the stored reservation again, requeues with a fresh retry and deferral budget under the next generation, and schedules an execution of that generation; answers `Redriven`, `NotFound`, `NotTerminal(state)`, `UnknownDefinition(definition)` or `Deduplicated(holder)` — the last three write nothing |
| `cancelBySubject(subject)` | cancels every queued or running invocation about the subject, 500 per statement, releasing their reservations; a running attempt is fenced out of its next effect and loses its lease at the next renewal; answers `CancelOutcome(cancelled, batches)` |
| `definitions()` | every declared definition with its profile, payload type and worker ceiling |

`redrive` and `cancelBySubject` join the caller's transaction when there is one. A generation is carried in the
db-scheduler instance id, so a stale execution of an earlier generation is refused instead of racing the redriven one.

### States and failure codes

| `JobState` | Stored | Terminal |
|---|---|---|
| `QUEUED` | `queued` | no |
| `RUNNING` | `running` | no |
| `SUCCEEDED` | `succeeded` | yes |
| `FAILED` | `failed` — a permanent refusal | yes |
| `DEAD` | `dead` — a budget ran out, or the definition is unknown | yes |
| `CANCELLED` | `cancelled` | yes |

`FailureCode`: `failed`, `permanent`, `attempt_timeout`, `deferrals_exhausted`, `lease_expired`, `unknown_definition`,
`statement_bound_failed`, `payload_unreadable`. `failure_message` is cut at 2000 characters; the full failure is in the log.

## Error codes

`JobsErrorCodes` (owner `rain-jobs`):

| Code | Default message | Rendered for |
|---|---|---|
| `job_intent_conflict` | the work could not be placed because its reservation kept changing hands; try again | `IntentConflictException`, `503` |

## Health checks

| Check | Code | Runs in | Fails when |
|---|---|---|---|
| `jobs` | `jobs` | `worker` | the worker is not running; a scheduler is not started, is shutting down, has never polled, or last finished a poll longer ago than `health.max-poll-age`; the renewer is not running, has not run a round, or last ran longer ago than `lease.ttl` |

Each reason is named in the failure message. Attempts whose scheduler thread stopped waiting while their body still runs
are not a readiness failure; they are counted in the gauge `rain.jobs.attempts.wedged` (tag `profile`) when a
`MeterRegistry` bean exists, and db-scheduler's own Micrometer statistics are registered then too.

## Schema

`rain_jobs`, migrated from `classpath:db/rain/jobs`. Three tables, each with one owner:

| Table | Owner | Holds |
|---|---|---|
| `scheduled_tasks` | db-scheduler | executions: task name, instance id (`<invocation>/<generation>`), execution time, priority; no rain statement names it except through db-scheduler's client |
| `job_invocation` | rain | one order and its life: definition, profile, state, priority, payload, dedupe mode and key, subject key, generation, attempts, retries spent and allowed, deferrals, lease token and expiry, timestamps, failure, absorbed count, version |
| `job_intent` | rain | deduplication reservations: definition, profile, key, mode, holding invocation, reserved and released instants; no foreign key, so a reservation's history outlives its order |

Every timestamp in rain's tables is written from the application's clock. Constraints tie the columns together: a
dedupe key exactly when the mode is not `none`, a lease token and expiry together, `running` exactly when leased, a
finish instant exactly in a terminal state.

## Scale guarantees

| Statement | Access path | Bound |
|---|---|---|
| claim, finish, retry, defer, release | primary key and lease token | one row, its reservations through `uq_job_intent_held_invocation` |
| lease renewal | primary keys of this process's leases, `FOR UPDATE SKIP LOCKED` | the attempts this process runs |
| reaper page | `ix_job_invocation_lease`, `FOR UPDATE SKIP LOCKED` | `reaper.batch` rows |
| dead-letter page | `ix_job_invocation_dead_letters` or `ix_job_invocation_dead_letters_definition` | `limit + 1` rows, keyset |
| cancel by subject | `ix_job_invocation_subject` | 500 rows per statement |
| retention delete | `ix_job_invocation_retention`, `ix_job_intent_retention` | `retention.batch` rows per statement, `retention.run-budget` per pass |
| undeclared-profile walk | the same retention indexes | one row per seek |
| reservation | `uq_job_intent_held` | one row |
| db-scheduler due poll | `priority_execution_time_idx` | db-scheduler's own batch |

`RetentionUsesIndexIT`, `DeadLetterKeysetIT`, `ReaperIT`, `CancelBySubjectIT` and `HeldReservationIsKeyedByInvocationIT`
prove these plans bounded under rain-test's plan criterion v3 (`QueryPlan.boundedScan`): the named index under a `Limit`
with every condition an index condition, and the reservations released through lookups of the unique
`uq_job_intent_held_invocation`. No statement counts invocations. A definition at its worker ceiling is rescheduled after
one poll interval instead of parking a pooled thread.

## What it does not do

- It does not deliver exactly once; handlers are idempotent.
- It runs no handler, renewer, reaper or recurring work in a process without the `worker` role.
- It never retries an invocation of an unknown definition under an assumed profile, and never deletes rows of an
  undeclared profile.
- It counts no dead letters and stores no payload in `scheduled_tasks`.
- It lets db-scheduler delete no execution it cannot resolve: every scheduler sees the other schedulers' task names as
  unresolved and leaves them alone.
