# rain-web

The universal servlet web layer: RFC 9457 problem responses for every refusal, the transport filters, the probes,
role gating of the web surface, and a bounded token-bucket throttle. What the filters do and in which order, and what
the probes answer, are described in [transport and health](../concepts/transport-and-health.md); the problem format is
in [errors](../concepts/errors.md).

Add it to every application that serves HTTP. Every process of such an application runs it, including workers, which
serve only the probes.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-web")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
```

rain-web brings rain-observability, Boot's Web MVC and Jackson modules. The servlet API is the container's, so the
application chooses one: `spring-boot-starter-webmvc` brings Tomcat.

## What it contributes

`RainWebErrorAutoConfiguration` — before Boot's `ErrorMvcAutoConfiguration` and `WebMvcAutoConfiguration`. Its first
beans exist in every process, so a command such as `config-check` refuses a broken error catalog too:

| Bean | Condition | What it is |
|---|---|---|
| `rainErrorCodes`, `rainWebErrorCodes` | — | the catalogs `RainErrorCodes` and `RainWebErrorCodes` |
| `errorCodeRegistry` | no other `ErrorCodeRegistry` bean | the registry built from every `ErrorCodeCatalog` bean |
| `statusTable` | no other `StatusTable` bean | `StatusTable.V1` |
| `problemRenderer` | no other `ProblemRenderer` bean | renders a `Fault` as problem format v1 |
| `problemWriter` | servlet application, no other bean | writes a problem onto a servlet response, for filters |
| `rainExceptionHandler` | servlet application, no other `ResponseEntityExceptionHandler` | `RainExceptionHandler`; extending `ResponseEntityExceptionHandler` makes Boot's problem-details handler back off |
| `rainErrorController` | servlet application, no other `ErrorController` | `RainErrorController` at `server.error.path` (`/error`) |

`RainWebFilterAutoConfiguration` — servlet applications:

| Bean | What it is |
|---|---|
| `securityHeadersFilterRegistration` | filter `rainSecurityHeadersFilter` |
| `requestLogFilterRegistration` | filter `rainRequestLogFilter` |
| `requestBudgetTimer` | the one daemon timer thread `rain-request-budget` (no other `RequestBudgetTimer` bean) |
| `requestBudgetFilterRegistration` | filter `rainRequestBudgetFilter` |
| `corsFilterRegistration` | filter `rainCorsFilter`: Spring's `CorsFilter` over `rain.web.cors`, rejections rendered as `403 cross_site` |
| `bodyLimitFilterRegistration` | filter `rainBodyLimitFilter` |
| `crossSiteFilterRegistration` | filter `rainCrossSiteFilter` |
| `forwardHeadersCheck` | `rain.web.client-address` agrees with `server.forward-headers-strategy` |

`RainProbeAutoConfiguration` — servlet applications:

| Bean | Condition | What it is |
|---|---|---|
| `probeController` | no other `ProbeController` bean | reads the `HealthRegistry` |
| `probeRoutes` | — | functional routes at `rain.web.probes.live-path` and `ready-path` |
| `probeSurface` | — | a `MountsItsOwnSurface` declaring `GET` on both probe paths public, so rain-access's surface verification finds the probes declared |
| `probeOnlyFilterRegistration` | the `api` role is not active | filter `rainProbeOnlyFilter`: everything except the probes and `management.endpoints.web.base-path` (`/actuator`) is `404 not_found` |

A process started as a command has no web server, so none of the servlet contributions exist in it — including
`forwardHeadersCheck`, which `config-check` therefore does not run.

## Configuration

`rain.web` is required whenever rain-web is on the classpath.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.web.body-limit` | required | the largest request body accepted | positive; at most 2147483647 bytes, the largest body a servlet container counts |
| `rain.web.request-budget` | required | how long a request may be served before it is answered `503 deadline_exceeded` | positive |
| `rain.web.client-address` | required | `direct`: the connection's peer; `forwarded`: a trusted proxy's forwarding headers, applied by the server | see the bean-time check below |
| `rain.web.cors.allowed-origins` | empty: no cross-origin caller | origins allowed to call cross-origin; also the origins the cross-site filter admits | each is `*` or `scheme://host[:port]` with no path; in `prod`, each is `https`, not `*`, and names no loopback host (`127.0.0.0/8`, `::1`, `localhost`, `*.localhost`) |
| `rain.web.cors.allowed-methods` | empty | methods an allowed origin may use | required when `allowed-origins` is not empty; each is `*` or an HTTP token |
| `rain.web.cors.allowed-headers` | empty | request headers an allowed origin may send | each is `*` or an HTTP token |
| `rain.web.cors.exposed-headers` | empty | response headers a page may read, e.g. `X-Request-ID` | each is `*` or an HTTP token |
| `rain.web.cors.max-age` | absent: no `Access-Control-Max-Age` | how long a preflight answer may be cached | not negative |
| `rain.web.cors.allow-credentials` | `false` | whether a credentialed cross-origin request is answered | not `true` while `allowed-origins` contains `*` (`contradicts`) |
| `rain.web.security-headers` | the five headers below | header name to value, set on every response; stating any entry replaces the whole map | each name is an HTTP token; each value is not blank and holds no CR, LF or NUL |
| `rain.web.probes.live-path` | `/live` | where liveness is served | matches `^/[A-Za-z0-9._~!$&'()+,;=:@/-]*$` |
| `rain.web.probes.ready-path` | `/ready` | where readiness is served | the same pattern; not the live path (`contradicts`) |

Bean-time problems, servlet processes only:

| Path | Code | When |
|---|---|---|
| `rain.web.client-address` | `contradicts` | `direct` while `server.forward-headers-strategy` is not stated as `none`; `forwarded` while it is not stated as `native` or `framework` |
| `server.forward-headers-strategy` | `invalid` | a value other than `native`, `framework` or `none` |
| `error-catalog:<owner>` | `contradicts` | two catalogs with one owner |
| `error-catalog:<class or owner>` | `invalid` | a catalog with a blank owner, or one whose codes cannot be constructed |
| `error-code:<value>` | `contradicts` | a code declared more than once, naming every owner |

An unstated forward-headers strategy is refused because Spring Boot would otherwise decide from the detected cloud
platform whether a forwarding header from anyone is believed, and a server that honours `X-Forwarded-For` from anyone
attributes requests to an address any client can write.

### Security headers

| Header | Default value |
|---|---|
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` |
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `no-referrer` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |

The defaults are for an API that serves no page: a JSON answer loads nothing and is embedded nowhere, so
`default-src 'none'` breaks nothing, and `frame-ancestors 'none'` with `X-Frame-Options` keeps an answer delivered with
cookies out of a foreign frame. `Strict-Transport-Security` is sent on every connection: a browser ignores it over
plain HTTP (RFC 6797), and behind a TLS terminator the server does not know the original scheme. An application that
serves pages states its own map.

The filter is the outermost rain filter and sets the headers before the chain runs; a refusal written by a filter
resets the body, never the headers. So a CORS rejection, a body over the limit, a cross-site write, a spent budget and a
worker's 404 carry them too.

## Filters in detail

### Request log

One line per request, message `http request served`, written after everything that could change the status has run:

| Key | Value |
|---|---|
| `request_id` | the correlation id |
| `method`, `path` | the request URI, without the query string |
| `status` | the status the client saw; 500 when a failure escaped the chain |
| `took` | ISO-8601 duration |
| `principal` | from the application's `RequestPrincipal` bean, when there is one and it names a principal |
| `error` | the message of the failure MVC handled or that escaped |

5xx is ERROR, 4xx is WARN, a probe that answered below 400 is DEBUG, everything else INFO — otherwise an orchestrator
probing every second would be all the log shows. No header value, body or query string is logged: `Authorization`,
cookies and `?token=` do not belong in a log store.

The correlation id is the caller's `X-Request-ID` when it is 1 to 64 characters of `A-Z a-z 0-9 . _ : -`, otherwise a
random UUID; a header is client input that lands in a log line, and a line break in it would write lines of its own. The
id is echoed in the `X-Request-ID` response header and put in the MDC under `request_id` for the duration of the
request. A browser page reads it only when `rain.web.cors.exposed-headers` names it.

### Request budget

The filter publishes a `RequestDeadline` as the request attribute `RequestDeadline.ATTRIBUTE` and, on the serving
thread, as `RequestDeadline.current()`, so code far from the servlet request can bound its own work with `remaining()`.
When the budget fires, the deadline is marked expired and the serving thread interrupted; whatever the interrupt turns
into, the exception handler answers `503 deadline_exceeded`. A request that started asynchronous processing is governed
by Spring MVC's async timeout from then on, which renders `503` through the status table. A timer cancelled because the
request finished leaves the queue at once.

### Cross site

CORS hides an answer from a foreign page but does not stop the write, and a browser attaches cookies to whoever asks.
For every method except GET, HEAD and OPTIONS:

| The request carries | Decision |
|---|---|
| `Origin` listed in `rain.web.cors.allowed-origins`, or the list is `*` (compared case-insensitively) | passes |
| `Origin` not listed | `403 cross_site` |
| no `Origin`; `Sec-Fetch-Site` is `same-origin` or `none` | passes |
| no `Origin`; `Sec-Fetch-Site` is `same-site` or `cross-site` | `403 cross_site` |
| neither header | passes |

The last row is what lets the rule be on at all: a request with neither header was not driven by a page (a CLI, a
server), so it carries no borrowed cookie, while every browser sends `Origin` on an unsafe method and a page cannot
remove it. Safe methods are not checked: a browser does not hand a cross-origin answer to a page without CORS headers.

### Body limit

A `Content-Length` above the limit is refused before the handler runs. A body without a length is counted while it is
read, and the read that crosses the limit fails and marks the request, so the exception handler answers `413 too_large`
whatever the reader wrapped the failure in.

Multipart bodies are read by the servlet container itself. While Spring Boot's multipart support is enabled they are not
counted here, and `MultipartLimitsCheck` refuses the start unless `spring.servlet.multipart.max-request-size` is within
`rain.web.body-limit` and `max-file-size` within the request size (Spring Boot's own 10MB request size counts when it is
not stated). With `spring.servlet.multipart.enabled=false` multipart bodies are counted like any other.

## Errors

`RainExceptionHandler` answers every failure that leaves a controller, in this order:

1. a refusal the transport already decided wins: an expired budget is `503 deadline_exceeded`, a body over the limit is
   `413 too_large`;
2. a `Fault` renders as it is;
3. `MethodArgumentNotValidException` is a validation fault with one violation per field/object error. Property paths
   are preserved (`items[0].email` becomes `/items/0/email`); standard Jakarta constraints map to `required`,
   `invalid_format`, `too_long`, `out_of_range` or the `check` fallback and carry bounded typed constraint parameters;
   ordered `ValidationViolationMapper` beans get the first chance to replace one mapping without replacing the
   exception handler;
4. Spring's own exceptions render with the status Spring chose, through the status table;
5. anything else is offered to every `FaultTranslator` bean in order, and the first answer renders;
6. otherwise the failure is `500 internal`.

`RainErrorController` renders what the container refused or what escaped a filter through the same renderer and table;
a dispatch that names no status is internal. `ProblemWriter` writes the same bytes, status and headers for a refusal made
before MVC. A response that is already committed cannot be refused; the attempt is logged.

## Probes

`ProbeController` sets the content type on the answer itself, so no `Accept` header can turn a probe into a 406. While
readiness is `not_ready` or `degraded`, each failing check is logged at WARN as `a readiness check failed` with its
name, importance and message, which the public body never carries.

## Throttling

`TokenBucketThrottle(perMinute, burst, callers, clock)` is a continuously refilling token bucket per caller key over a
`CallerTable` holding at most `callers` buckets. rain-web does not mount it: whoever mounts it decides the key — an
address, an account — and the route.

```kotlin
class SignInThrottle {
    private val throttle = TokenBucketThrottle(perMinute = 120, burst = 60, callers = 20_000)

    fun admit(request: HttpServletRequest) {
        if (throttle.allow(request.remoteAddr)) return
        throw Fault(FaultKind.TOO_MANY_REQUESTS, retryAfter = throttle.retryAfterSeconds()?.let { Duration.ofSeconds(it) })
    }
}
```

`request.remoteAddr` is the forwarded address only when `rain.web.client-address` is `forwarded` and the server applies
the proxy's headers; a key taken from a header anyone can write is a limit anyone can bypass.

| Member | Behaviour |
|---|---|
| `allow(caller)` | spends one token; false when the bucket is empty or a new caller finds the table full |
| `retryAfterSeconds()` | the gap between two tokens in whole seconds, rounded up; null for a throttle that never refills |
| `interval()` | the gap between two tokens |
| `tracked`, `refusedBecauseFull` | how many callers the table holds, and how many new callers a full table refused |

Only a bucket that has refilled to full may be forgotten, because a caller the table has never seen starts full: a
caller cannot reset its bucket by flooding the table with new keys. A full table refuses new callers while the callers
it holds keep being admitted.

## Declaring access

| Type | What it is |
|---|---|
| `@Access(permissions, authenticated, public, why)` | what a route needs: named permissions, an authenticated caller, or nothing; `why` is mandatory for the last two |
| `EndpointDeclaration` | one entry of the HTTP surface; `problems()` names every invalid combination, including a route that declares nothing |
| `DeclaresItsOwnAccess` | a controller whose routes are derived from a table and answers their declarations itself |
| `MountsItsOwnSurface` | routes mounted outside request mappings, with their declarations: a functional `RouterFunction` route is verified and enforced from its declaration; a route of any other handler mapping (a WebSocket upgrade) checks what it declares itself |
| `RequestPrincipal` | who a request authenticated as, for the request log |

rain-web declares these types so every module's controllers can use them, and declares its own probes through
`probeSurface`; it does not enforce `@Access` itself. [rain-access](access.md) verifies the surface and enforces every
declaration.

## Error codes

`RainWebErrorCodes` (owner `rain-web`):

| Code | Default message | Answered by |
|---|---|---|
| `cross_site` | this request was made from another site | the cross-site filter and CORS rejections, `403` |

rain-web also registers `RainErrorCodes` ([rain-core](core.md#error-codes)).

## Health checks, schema, commands

None of its own. It serves the readiness composed by [rain-observability](observability.md).

## Scale guarantees

- A caller table never holds more than its capacity; finding room for a new caller is one look at a heap root, and
  moving a bucket is O(log n), whatever the table's size.
- A request that finished in time holds no timer for the rest of its budget.
- A body without a length is counted as it is read; no body is buffered to be measured.

## What it does not do

- It does not authenticate or authorise; [rain-access](access.md) does.
- It does not mount a throttle on any route.
- It does not believe a forwarding header on its own; the server does, when the deployment says a proxy is trusted.
- It does not configure the container's connection timeouts.
