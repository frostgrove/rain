# Transport and health

rain-web puts one transport policy in front of every servlet application, and rain-observability decides what the
probes answer. Every refusal the transport makes is a [problem format v1](errors.md) body.

## Required policy

```yaml
rain:
  web:
    body-limit: 10MB          # positive, at most 2147483647 bytes
    request-budget: 30s       # a request outliving it is 503 deadline_exceeded
    client-address: direct    # direct | forwarded
server:
  forward-headers-strategy: none   # must agree with client-address
spring:
  servlet:
    multipart:
      enabled: false               # or limits within rain.web.body-limit
```

None of the three has a default: each one is a door. `client-address: direct` requires
`server.forward-headers-strategy=none`; `forwarded` requires `native` or `framework`. An unstated strategy is a
contradiction, because Spring Boot would otherwise decide from the detected cloud platform whether `X-Forwarded-For`
from anyone is believed.

Multipart bodies are read by the servlet container itself, not through rain's counted stream. While Spring Boot's
multipart support is enabled, `spring.servlet.multipart.max-request-size` must be a size within `rain.web.body-limit` and
`max-file-size` within the request size — Spring Boot's own 10MB applies when they are not stated, and is refused above a
smaller body limit. With `spring.servlet.multipart.enabled=false` the body limit counts multipart bodies like any other.

## Filters, outermost first

| Order | Filter | What it does |
|---|---|---|
| 1 | security headers | sets `rain.web.security-headers` on every response, including refusals made before security runs; the default set is for an API that serves no page; stating any header replaces the whole map |
| 2 | request log | one line per request with `request_id` (the caller's `X-Request-ID` when it matches `^[A-Za-z0-9._:-]{1,64}$`, otherwise a fresh id); 5xx ERROR, 4xx WARN, successful probes DEBUG; no query string, no header values |
| 3 | request budget | a timer per request on one daemon thread; when it fires the deadline is marked expired and the serving thread interrupted; the answer is `503 deadline_exceeded` |
| 4 | probe only | only in a process **without** the `api` role: everything except the probes and the Actuator base path is `404` |
| 5 | CORS | Spring's `CorsFilter` over `rain.web.cors`; no allowed origins means no cross-origin caller; a refused CORS request is `403 cross_site` |
| 6 | body limit | a `Content-Length` above the limit is refused before the handler runs; a body without a length is counted while it is read and the read that crosses the limit fails; `413 too_large`; multipart bodies are bounded by `spring.servlet.multipart.*` instead |
| 7 | cross site | an unsafe method (not GET, HEAD, OPTIONS) passes only with an allowed `Origin`, or with no `Origin` and `Sec-Fetch-Site` absent, `same-origin` or `none`; otherwise `403 cross_site` |

In the `api` role rain-access adds two filters after these, on its credential routes only — a JSON-only filter and a
throttle per client address — and Spring Security's chain after those
([rain-access](../modules/access.md#the-credential-gate)).

## CORS

```yaml
rain:
  web:
    cors:
      allowed-origins: [https://app.example.com]
      allowed-methods: [GET, POST, PATCH, DELETE]
      allowed-headers: [Content-Type, X-Request-ID]
      max-age: 10m
      allow-credentials: true
```

Origins are `*` or `scheme://host[:port]`. Credentials together with `*` is a contradiction. Allowed origins without
allowed methods is a missing value, not "all methods".

An allowed origin lets a page drive unsafe requests as whoever is signed in, so a `prod` deployment refuses `*`, any
origin that is not `https`, and any origin naming this machine: a loopback IP literal, `localhost` or a `.localhost` name
(RFC 6761). The refusal names the entry, e.g. `rain.web.cors.allowed-origins[1]`. `dev` and `test` deployments may name
a workstation's browser.

## Declaring access

`@Access` is the one annotation a route wears ([rain-web](../modules/web.md#declaring-access)). It names permissions,
or says the route needs an authenticated caller, or says the route is public; the last two carry a mandatory `why`. With
[rain-access](../modules/access.md), a process with the `api` role verifies the whole surface once beans exist and
enforces each declaration before its handler runs:

- a request mapping that declares nothing refuses the start, whatever package or library its controller comes from; only
  a `SurfaceExemption` bean exempts a handler type;
- a controller whose routes are derived from a table implements `DeclaresItsOwnAccess`, and a declaration it makes for a
  route nothing mounts refuses the start;
- a functional `RouterFunction` route is declared by a `MountsItsOwnSurface` bean and enforced from that declaration, a
  `HEAD` request held to the `GET` declaration; a route whose predicate does not reduce to a method and a path refuses the
  start; rain-web declares the probes this way;
- a route another handler mapping serves (a WebSocket upgrade) is declared through `MountsItsOwnSurface` and checks what
  it declares itself.

An anonymous caller of a route that is not public is `401 unauthenticated`; a subject missing a named permission is
`403 forbidden`. A permission is one a `ModuleGrants` bean declares, or the start is refused.

## Throttling

`TokenBucketThrottle` is a continuously refilling bucket per caller key, over a `CallerTable` with a fixed capacity.
Only a bucket that has refilled to full may be forgotten, so a caller cannot reset its bucket by flooding the table
with new keys. A full table refuses **new** callers (counted), while callers it already holds keep being admitted.
Finding room costs one heap look; moving a bucket is O(log n).

## Probes

| Path | Answer |
|---|---|
| `rain.web.probes.live-path` (`/live`) | `{"status":"live"}`, always 200, asks no dependency |
| `rain.web.probes.ready-path` (`/ready`) | `{"status":"ready|degraded|not_ready|draining","failing":[codes]}`; 200 for ready and degraded, 503 otherwise |

Readiness is composed from health checks. Modules and applications offer `HealthCheck` beans; how much each one
matters is decided by the application, never by the checker, under `rain.health.checks`:

```yaml
rain:
  health:
    checks:
      database: required
      jobs: degrading
      realtime.listener: required
```


| Importance | Failing means |
|---|---|
| `required` | `not_ready`, 503 |
| `degrading` | `degraded`, still 200 |
| `informational` | listed in detail, not counted |
| `disabled` | registered, never probed |

A process that runs a check without a stated importance does not start. One configuration usually serves every role,
so an entry for a check this process does not run is reported `not_evaluated` and does not stop the start.

| Check | Offered by | Runs in | Fails when |
|---|---|---|---|
| `database` | rain-observability | any process with a `DataSource` | no pooled connection answers `isValid` within the budget |
| `jobs` | rain-jobs | `worker` | the worker is stopped, a scheduler is not started, is shutting down or has not polled within `rain.jobs.health.max-poll-age`, or the lease renewer has not run within `rain.jobs.lease.ttl` |
| `realtime.listener` | rain-realtime | `api` | the listener has no live session |
| `breaker.<name>` | rain-resilience | every process declaring the breaker | the breaker withholds calls (open, half-open or forced open) |
| `access.revocation` | rain-access | `api`, `worker`, when `rain.access.revocation.store` is `redis` | the revocation list's Redis server does not answer `PING` |
| `access.attempts` | rain-access | `api`, when `rain.access.attempts.store` is `redis` | the attempt counters' Redis server does not answer `PING` |

The checks of one evaluation run
concurrently on virtual threads, each against an absolute budget counted from the start of the evaluation (its own,
or `rain.health.check-timeout`, 2 s), and one evaluation is shared for `rain.health.freshness` (1 s). A check that
throws or does not answer in time is failing; a probe never takes the process down.
From the moment the application context starts closing, `/ready` answers `draining` (503) without asking any
dependency, so the load balancer drains the process before its pools close. `failing` lists the sorted public codes
of failing `required` and `degrading` checks (a check without a public code moves the status without being named);
the failure messages go to the log only.

Actuator's health endpoint reads the same registry through a bridge, so both surfaces agree.
