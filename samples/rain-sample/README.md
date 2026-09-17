# rain-sample — the helpdesk

The reference application of rain: a helpdesk that uses every rain module the way a product would, through public API
only. Its integration tests start it for real; this page runs it by hand.

| Module | What the helpdesk does with it |
|---|---|
| rain-boot | roles `api` and `worker`, the commands `migrate`, `seed`, `config-check`, `ticket-report`, `smoke-llm`; its own `sample.tickets` and `sample.seed` sections, one rule reading `rain.web` |
| rain-web, rain-observability | problem+json refusals, transport filters, `/live` and `/ready` from checks whose importance `application.yml` states |
| rain-access | agents sign in (`/v1/auth/agent/login`); permissions `ticket.read`, `ticket.write`, `ticket.delete`, `product.read`; roles `administrator` (system), `supervisor`, `responder` |
| rain-crud | `/v1/tickets` with declared query shapes, cursor pages, a capped count, and one scope decided per request: every ticket for an agent holding `ticket.read`, only the tickets assigned to it for any other (`ScopeRule.EveryRowWhenHolding`) |
| catalogue demo | `/v1/products`, a read-only Rain CRUD catalogue seeded with products for `rain-web`'s admin demonstration; its sortable/filterable options are declared query shapes rather than ad-hoc SQL |
| rain-audit | every create, change, close and delete of a ticket, in its transaction |
| rain-jobs | `ticket.summarize` (a bounded drafting step, then a fenced write under the ticket's advisory lock), cancelled when its ticket is deleted; the recurring `ticket.escalation-sweep` |
| rain-llm, rain-resilience | the summary asked of an in-sample deterministic `ChatModel` through `LlmGateway`, behind the breaker `summarizer` |
| rain-realtime | `GET /v1/tickets/{id}/events`: a ticket's committed changes as server-sent events |
| rain-i18n, rain-i18n-web | `GET /v1/i18n/preview`: a static explicit catalog provider plus the request-scoped magic `I18nRuntime`; `Accept-Language` selects reviewed en/ru/kk rich output, keeps bidi isolates structural and writes the actual `Content-Language` |
| rain-persistence, rain-data-jdbc | the application's Flyway migration in `public`, jOOQ statements, and one Spring Data JDBC repository (agent profiles) |

## The stand

`compose.yaml` runs PostgreSQL 18, Redis 8 (`maxmemory-policy noeviction`) and one image as four processes:

| Service | Process | Environment |
|---|---|---|
| `sample-migrate` | one-shot, exits 0 | `RAIN_RUNTIME_COMMAND=migrate` |
| `sample-seed` | one-shot, exits 0 | `RAIN_RUNTIME_COMMAND=seed`, `SAMPLE_SEED_INITIALPASSWORD` |
| `sample-api` | serves `127.0.0.1:18080` | `RAIN_RUNTIME_ROLES=api` |
| `sample-worker` | serves only its probes, on `127.0.0.1:18081` | `RAIN_RUNTIME_ROLES=worker` |

Every process states `RAIN_DEPLOYMENT_STAGE=dev`, where PostgreSQL and Redis are, and a development signing key. The api
and the worker are healthy when `/ready` answers 200. PostgreSQL and Redis publish no host port; only the processes reach
them.

Commands below run from the repository root.

### Build the image

```sh
./gradlew :samples:rain-sample:bootJar
docker compose -f samples/rain-sample/compose.yaml build
```

### Migrate, seed, start

```sh
docker compose -f samples/rain-sample/compose.yaml up -d --wait postgres redis
docker compose -f samples/rain-sample/compose.yaml run --rm sample-migrate
docker compose -f samples/rain-sample/compose.yaml run --rm sample-seed
docker compose -f samples/rain-sample/compose.yaml up -d --wait sample-api sample-worker
```

`migrate` prints one line per schema on standard output — every rain module schema, then the application's; `seed`
prints its progress on standard error. Both exit 0. Everything a process logs goes to standard error, so a command's
standard output is only its result.

```sh
curl -s http://127.0.0.1:18080/live
curl -s http://127.0.0.1:18080/ready
curl -s http://127.0.0.1:18081/ready
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18081/v1/tickets   # 404: a worker serves only its probes
```

### Sign in

The seed enrolled one agent per role (`sample.seed.agents` in `application.yml`), each with the initial password.
`rain.access.web.delivery` is `both`, so a sign-in states where its credentials go: `body` answers the tokens, `cookies`
sets `__Host-rain-access` and `__Secure-rain-refresh`.

```sh
TOKEN=$(curl -s -X POST http://127.0.0.1:18080/v1/auth/agent/login \
  -H 'Content-Type: application/json' -H 'Rain-Auth-Delivery: body' \
  -d '{"identifier":"supervisor@helpdesk.example","password":"correct horse battery staple"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"
curl -s -H "$AUTH" http://127.0.0.1:18080/v1/auth/me
```

### Create and page tickets

```sh
for n in 1 2 3; do
  curl -s -X POST http://127.0.0.1:18080/v1/tickets -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"title\":\"Printer $n jams\",\"body\":\"Duplex jobs jam on floor $n.\",\"priority\":$((n * 30))}"
  echo
done

curl -s -G -H "$AUTH" http://127.0.0.1:18080/v1/tickets \
  --data-urlencode 'filter%5Bstatus%5D%5Beq%5D=open' --data-urlencode 'sort=-createdAt' --data-urlencode 'limit=2'
```

A parameter name of query dialect v1 holds `[` and `]`, which Tomcat refuses unencoded in a query string (with its own
HTML `400`, before the request reaches the application), so the names are sent percent-encoded; `--data-urlencode`
encodes only the value.

The page answers `page.next`; the next page is the same query with `--data-urlencode "cursor=<next>"`, and `page.prev`
leads back. A supervisor is served three shapes: `sort=-updatedAt`; `filter[status][eq]` with `sort=-createdAt`; and
`filter[assignee][eq]` with `filter[status][eq]` and `sort=-priority`. A responder, who reaches only its own tickets, is
served `sort=-updatedAt` and `filter[status][eq]` with `sort=-priority`. A count stops at
`sample.tickets.pages.count-cap`:

```sh
curl -s -G -H "$AUTH" http://127.0.0.1:18080/v1/tickets/count --data-urlencode 'filter%5Bstatus%5D%5Beq%5D=open'
```

A query the resource does not declare is `400 not_offered`:

```sh
curl -s -G -H "$AUTH" http://127.0.0.1:18080/v1/tickets --data-urlencode 'sort=title'
```

### Browse seeded products

The seed command also inserts a small, idempotent product catalogue. The administrator system role receives
`product.read`; listing it uses the same exact query dialect as the frontend's `rainTableFactory` adapter:

```sh
curl -s -G -H "$AUTH" http://127.0.0.1:18080/v1/products \
  --data-urlencode 'filter%5Bcategory%5D%5Beq%5D=Lighting' \
  --data-urlencode 'limit=25' --data-urlencode 'offset=0' --data-urlencode 'count=capped'
```

Point `rain-web` at `http://127.0.0.1:18080/v1` through `NEXT_PUBLIC_RAIN_API_BASE` to use this transport surface.

### Preview localization

The preview uses the servlet magic path, not a JVM default: the filter resolves the request locale
once, the request-scoped runtime renders a typed deferred message against that view, and the response
reports the actual template locale. The static `CatalogSnapshotProvider` in the sample is deliberately
the visible low-level bootstrap seam; a product can replace it with the durable release provider
without changing the controller or message contract.

```sh
curl -s -H "$AUTH" -H 'Accept-Language: ru, en;q=0.5' http://127.0.0.1:18080/v1/i18n/preview
curl -s -H "$AUTH" -H 'Accept-Language: kk' http://127.0.0.1:18080/v1/i18n/preview
```

The body contains `text`, `templateLocale` and safe `parts`; the Russian request answers
`Content-Language: ru`. `parts` retains the strong markup boundary and automatic bidi isolate around
the count. Jobs, tenancy and events remain separate product-level compositions: this endpoint adds no
three-way integration package.

### Watch a ticket, change it, summarize it

In a second terminal, with the same `AUTH` and a ticket's id as `TICKET`:

```sh
curl -sN -H "$AUTH" http://127.0.0.1:18080/v1/tickets/$TICKET/events
```

The stream answers `event: ticket` with the ticket as it is, then `event: change` for every committed change, and
`event: end` after `sample.tickets.events.stream-for`, when a client subscribes again. In the first terminal:

```sh
curl -s -X PATCH -H "$AUTH" -H 'Content-Type: application/json' -d '{"version":1,"priority":95}' \
  http://127.0.0.1:18080/v1/tickets/$TICKET
curl -s -X POST -H "$AUTH" http://127.0.0.1:18080/v1/tickets/$TICKET/summary
```

The summary order answers `202` with its invocation; the worker drafts the summary and stores it as a new version of the
ticket, which the stream shows as another `change`. Reading the ticket shows the summary:

```sh
curl -s -H "$AUTH" http://127.0.0.1:18080/v1/tickets/$TICKET
curl -s -X POST -H "$AUTH" http://127.0.0.1:18080/v1/tickets/$TICKET/close
curl -s -X POST -H "$AUTH" http://127.0.0.1:18080/v1/tickets/$TICKET/close   # 409 ticket_closed
```

### Report

```sh
docker compose -f samples/rain-sample/compose.yaml run --rm -e RAIN_RUNTIME_COMMAND=ticket-report sample-migrate
```

A one-shot service's command is replaced by the one stated; `sample-api` would state roles and a command at once, which is
refused. One tab-separated line per open ticket on standard output, oldest first; the count and `--after=<created-at>_<id>` of
the next page on standard error.

### A refused configuration

```sh
docker compose -f samples/rain-sample/compose.yaml run --rm -e RAIN_DEPLOYMENT_STAGE= sample-migrate
```

The process exits non-zero before it serves anything, naming every problem at once on standard error.

### Stop

```sh
docker compose -f samples/rain-sample/compose.yaml down -v
```

## Tests

```sh
./gradlew :samples:rain-sample:check
```

`check` runs the unit tier and the integration tier (Docker: PostgreSQL and Redis containers, and the bootJar started as
a subprocess). `TicketPlanProofIT` proves every statement of the ticket resource bounded by an index under both scopes;
`SampleArchitectureTest` keeps the application on rain's public API.
