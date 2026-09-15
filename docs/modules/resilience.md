# rain-resilience

Circuit breakers for the dependencies an application calls, on Resilience4j's Spring Boot 4 starter. The starter's
`CircuitBreakerRegistry` bean and its `resilience4j.circuitbreaker.instances.<name>` properties are the battery, used on
purpose; rain adds breaker declarations, atomic admission with one probe per cooldown, a readiness check per breaker,
and a bean-time check that every declared breaker is configured explicitly.

Add it when the application calls a dependency whose outage should cost one probe per cooldown rather than one failed
call per request or per job.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-resilience")
}
```

It brings [rain-observability](observability.md) and `resilience4j-spring-boot4`.

## What it contributes

`RainResilienceAutoConfiguration` — after the starter's `CircuitBreakerAutoConfiguration` and
`RainRuntimeAutoConfiguration`, in every role:

| Bean | Condition | What it is |
|---|---|---|
| `breakerRegistry` | no other `BreakerRegistry` bean | the declared breakers over the container's `CircuitBreakerRegistry` |
| `admissionGate` | no other `AdmissionGate` bean | `AdmissionGate` |
| `breakerConfigurationCheck` | — | the configuration rules below |
| `breakerHealthRegistrar` | — | registers one `BreakerHealthCheck` bean, named `<declaration bean>.health`, per `BreakerDeclaration` bean |

Nothing here builds a registry of its own, and nothing creates a breaker: a breaker is looked up, so a declared breaker
without an instance entry never falls back to the library's default configuration.

## Configuration

rain-resilience declares no `rain.*` section. Each declared breaker is configured under Resilience4j's own properties,
and its readiness importance under `rain.health.checks`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      payments:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
rain:
  health:
    checks:
      breaker.payments: degrading
```

The rules a declared breaker's configuration obeys (version 1), checked once beans exist:

| Path | Code | Rule |
|---|---|---|
| `breaker:<name>` | `contradicts` | a breaker is declared once |
| `resilience4j.circuitbreaker.instances.<name>` | `required` | an explicit instance entry exists |
| `resilience4j.circuitbreaker.instances.<name>.wait-duration-in-open-state` | `required` / `invalid` | the instance entry itself states it, at least 1 ms; Resilience4j decides the open wait from the instance's own value |
| `resilience4j.circuitbreaker.instances.<name>.enable-exponential-backoff` | `invalid` | not turned on |
| `resilience4j.circuitbreaker.instances.<name>.enable-randomized-wait` | `invalid` | not turned on |
| `resilience4j.circuitbreaker.instances.<name>` | `contradicts` | no `CircuitBreakerConfigCustomizer` names the breaker |

The open wait is fixed because a held caller is told one cooldown and the gate hands out one probe per cooldown; a
customizer would change the configuration where these rules cannot read it.

## API

| Type | What it is |
|---|---|
| `BreakerName(value)` | the key of the instance entry; matches `^[a-z][a-z0-9_.-]{0,63}$` |
| `BreakerDeclaration(name, healthCode)` | a breaker this application uses, as a bean; `healthCode` is the readiness code while it withholds calls, or null to keep it out of `failing` |
| `AdmissionGate.reserve(name)` | takes the right to make one call: `Reservation.Admitted`, `Reservation.Held(retryAfter)` or `Reservation.ForcedOpen` |
| `AdmissionGate.admission(name)` | what `reserve` would answer now, without taking anything: `Unrestricted`, `Probing`, `Held(retryAfter)` or `ForcedOpen` |
| `BreakerRegistry.succeeded(permit)` | the dependency answered the admitted call |
| `BreakerRegistry.failed(permit, cause)` | the admitted call failed in the dependency itself |
| `BreakerRegistry.state(name)` | `BreakerState(name, state, since, reason)`; `withholding` is true while open, half-open or forced open |
| `BreakerRegistry.openWait(name)` | the breaker's open wait |
| `BreakerRegistry.names()` | the declared breakers, by name |

```kotlin
object Payments {
    val BREAKER = BreakerName("payments")
}

@Bean
fun paymentsBreaker(): BreakerDeclaration = BreakerDeclaration(Payments.BREAKER, healthCode = "payments_unavailable")

class PaymentsClient(
    private val gate: AdmissionGate,
    private val breakers: BreakerRegistry,
    private val http: PaymentsHttp,
) {
    fun charge(order: Order): Receipt =
        when (val reservation = gate.reserve(Payments.BREAKER)) {
            is Reservation.Held -> throw Fault.retryable(retryAfter = reservation.retryAfter)
            Reservation.ForcedOpen -> throw Fault.retryable()
            is Reservation.Admitted ->
                reservation.use { permit ->
                    val receipt =
                        try {
                            http.charge(order)
                        } catch (failure: PaymentsUnreachable) {
                            breakers.failed(permit, failure)
                            throw Fault.retryable()
                        }
                    breakers.succeeded(permit)
                    receipt
                }
        }
}
```

A reservation is closed when the attempt ends (`use { }`). Closing one whose call has no outcome — the work was abandoned
before the dependency was asked — gives a probe's permission back and records nothing against the dependency. An outcome
is recorded at most once; an outcome after close is refused.

### What counts as a failure

Only a failure of the dependency itself: it did not answer, or answered that it cannot serve. A refusal the dependency
issued deliberately about the request — input it read and rejected — is an answer and is reported with `succeeded`:
it proves the dependency is up, and counting it would let one bad request open the breaker for everyone. Work abandoned
before the call is not reported.

### One probe per cooldown

A breaker alone does not stop a queue: workers polling a dead dependency would each spend an attempt per poll.
`AdmissionGate.reserve` takes its decision from the breaker's atomic `tryAcquirePermission`, so it never admits on a
reading made earlier, and then keeps the rule that makes an outage cheap:

- at most one probe of this process is in flight per breaker;
- a probe handed out at `t` is the only one until `t + openWait`, even when it was given back unused, so a probe that
  never reported holds the gate for no longer than one open wait.

A permission the gate refuses is given back to the breaker at once. A dispatcher reads `admission` to decide whether to
claim work at all, so work of a held kind is not taken and immediately put back.

Breaker state lives in the process's memory. Processes trip independently and none knows about another's failures.

## Health checks

| Check | Code | Runs in | Fails when |
|---|---|---|---|
| `breaker.<name>` | the declaration's `healthCode` | every process with the declaration bean | the breaker withholds calls; the message names the state, when the episode began and the last failure recorded through `failed` |

## Error codes, schema, commands

None. The caller decides the fault a held call becomes; `Held.retryAfter` is the rest of the open wait, or one full open
wait when the opening was not observed.

## What it does not do

- It builds no registry and creates no breaker; a declared breaker without its instance entry refuses the start.
- It does not retry, time out or wrap a call.
- It shares no breaker state across processes.
- It does not count a deliberate refusal, or waiting for capacity, as a dependency failure.
