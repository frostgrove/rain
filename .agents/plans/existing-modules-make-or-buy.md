# План: make-or-buy аудит уже реализованных Rain modules

Статус: первичный аудит завершён; никаких удалений или миграций не начато

Дата: 2026-09-17

Связанные планы: [vv-framework-make-or-buy.md](vv-framework-make-or-buy.md),
[storage.md](storage.md)

## 1. Честный итог

Rain не целиком переписал Spring Boot, но несколько подсистем уже перешли границу между
«тонкая opinionated policy» и «собственный инфраструктурный продукт».

Наиболее проблемные зоны:

1. `rain-access` — собственный embedded identity/session/token server поверх Spring Security.
2. `rain-event` — собственная PostgreSQL event-sourcing и projection platform.
3. i18n family — собственные grammar/compiler/artifact/release/persistence/jobs/tenancy/TMS layers.
4. `rain-tenancy` — control plane и сразу несколько data-plane/runtime продуктов без repository
   consumer-а.

`rain-jobs` находится посередине. Это не бессмысленная копия db-scheduler: Rain добавляет
transactional enqueue, effect fencing, durable context, deduplication и subject cancellation. Но
для этого рядом с db-scheduler построена вторая полная execution state machine. Она оправдана только
если перечисленные гарантии нужны реальному consumer-у.

Нельзя делать массовый rewrite только по результату этого аудита. Сначала нужно восстановить
реальные требования из consumers, затем сравнить маленькие spikes с текущими executable tests.
Однако новые функции в красных зонах до этого решения следует заморозить.

## 2. Масштаб и evidence внутри репозитория

На дату аудита `rain-*` содержит приблизительно 57 848 строк production Kotlin и 1 293 строки SQL.
Крупнейшие области:

| Область | Production Kotlin | Что уже построено |
|---|---:|---|
| `rain-access` | 8 441 | credentials, passwords, sessions, JWT, refresh rotation, Redis revocation/attempts, grants, admin/web surface |
| `rain-tenancy` | 6 938 | authority, web resolution, control plane, provisioning, fleet, RLS and database data planes, pools, settings/cache/storage/outbox |
| `rain-event` + `rain-event-test` | 8 198 | event store, receipts, snapshots, global log, projections, topology, leases, park/redrive, generations, jobs/realtime adapters |
| all i18n modules | about 14 187 | grammar/compiler, snapshots, formatting, toolchain, persistence, releases, jobs, tenancy, integration SPI, web and metrics |
| `rain-jobs` | 4 837 | second ledger/state machine over db-scheduler, leases, fences, dedupe, retries, admin, retention |
| `rain-crud` | 3 543 | query language, row policy, stores, web mapping and exhaustive plan proof |
| `rain-web` | 2 538 | problem format, filters, deadlines, CORS/cross-site/body policy, probes and throttle |

Repository-local consumer evidence is much smaller:

- `samples/rain-sample` exercises access, CRUD, audit, jobs, LLM, realtime and only
  `rain-i18n` + `rain-i18n-web` through one static preview;
- sample does not depend on `rain-event`, `rain-event-test`, `rain-tenancy`,
  `rain-tenancy-event`, i18n persistence/jobs/integration/tool/tenancy/observability modules;
- no production application exists in this repository, so internal tests prove implementation
  contracts, not that a product needs those contracts.

This is the main architectural risk: several sophisticated solutions were completed before a
repository consumer established their minimum useful scope.

## 3. Triage

Legend:

- **Green** — keep; the module is small or owns a clear Rain guarantee not supplied by Spring.
- **Yellow** — useful custom value exists, but the module overlaps ready infrastructure and should
  be reduced or re-admitted from consumer requirements.
- **Red** — freeze feature growth and run an explicit replace/shrink decision before treating it as
  stable platform API.

| Module/area | Verdict | Immediate decision |
|---|---|---|
| `rain-core` | Green | Keep the Spring-free values and stable domain errors |
| `rain-boot` | Green/Yellow | Keep strict aggregate config and explicit roles; avoid growing another general runtime/CLI |
| `rain-persistence` | Green | Keep thin jOOQ/Flyway/PostgreSQL policy; it already delegates retry to Spring Framework |
| `rain-audit` | Green | Keep same-transaction typed business evidence |
| `rain-data-jdbc` | Green | Keep small integration only while a consumer uses Spring Data JDBC |
| `rain-test` / architecture | Green | Keep plan/conformance rules that catch real regressions |
| `rain-realtime` | Green/Yellow | Keep for PostgreSQL-only ephemeral hints; do not promote it to durable messaging |
| `rain-resilience` | Yellow | Retain only the one-probe admission policy that Resilience4j lacks; shrink duplicate registry/health surface |
| `rain-observability` | Yellow | Converge on Actuator health groups and ApplicationAvailability; retain only proven timeout/degrading policy |
| `rain-web` | Yellow | Shrink around Rain error/validation policy; delegate standard security, problem and rate-limit mechanics |
| `rain-jobs` | Yellow | Freeze scope; prove fences/durable context against a direct db-scheduler implementation |
| `rain-crud` | Yellow | Keep only if several real resources need the exact query/policy/plan contract |
| `rain-llm` | Yellow | Keep cluster slots only for a measured shared model-server ceiling |
| `rain-access` authentication/session half | Red | Replace with external OIDC/Spring standard session or make a separately justified identity product |
| `rain-access` authorization catalogue | Yellow | Potentially keep, rebuilt as/behind Spring Security `AuthorizationManager` |
| `rain-event` | Red | Decide outbox/domain events versus true event sourcing before further work |
| i18n family | Red | Reduce default to MessageSource/ICU/typed keys; re-admit durable release features separately |
| `rain-tenancy` | Red | Pick one concrete tenancy topology and consumer; freeze the rest |

## 4. `rain-jobs`

### Что уже даёт engine

Используемый db-scheduler уже умеет:

- persistent one-time и recurring tasks;
- cluster-friendly claim/execution;
- heartbeat и detection/revival dead executions;
- failure handlers и retry/reschedule policy;
- task data и stateful recurring tasks;
- opt-in priority ordering;
- graceful start/stop и Micrometer statistics.

То есть poller, heartbeat, recurring scheduling, basic retry и dead-worker recovery писать поверх
него второй раз не нужно.

### Что действительно добавляет Rain

- enqueue в одной transaction с business write;
- `Unique`/`Collapse` intent semantics;
- cancellation by domain subject;
- durable context fragments for tenancy/i18n;
- attempt and step deadlines;
- fenced database effects, которые stale lease не может commit;
- bounded dead-letter administration and retention.

Ни db-scheduler, ни JobRunr OSS не дают весь этот набор. JobRunr закрывает persistence, distributed
execution, retries, recurring jobs and dashboard, но priority queues, rate limits, advanced timeout,
workflows и часть administration находятся в commercial tiers. Он также не заменяет Rain effect
fence автоматически.

### Verdict

`rain-jobs` — **не явный провал**, если effect fencing, transactional enqueue и durable context были
исходными обязательными требованиями. Но сейчас существуют два state authorities:
`scheduled_tasks` у db-scheduler и `job_invocation`/`job_intent` у Rain, плюс Rain lease renewer и
reaper рядом с heartbeat/dead-execution механизмом engine-а. Это высокая цена поддержки.

До новых функций:

1. Взять один реальный sample workflow и реализовать spike напрямую на db-scheduler.
2. Составить requirement matrix: atomic enqueue, dedupe, priority, cancel, timeout, dead letter,
   redrive, context, fence.
3. Всё, что direct engine закрывает, удалить из Rain design; сохранить только недостающие guarantees.
4. JobRunr рассматривать только вместе с OSS/Pro cost matrix; это не автоматическая бесплатная
   замена.

## 5. `rain-access`

### Готовое вокруг него

Spring Security уже предоставляет authentication/authorization pipeline, request/method
`AuthorizationManager`, password encoders including Argon2, CSRF, CORS, headers, session controls,
OAuth2 resource server and clients. Spring Session хранит sessions в Redis или JDBC. Spring
Authorization Server реализует OAuth2/OIDC protocol endpoints; Keycloak и другие IdP добавляют user
management, federation, MFA, recovery, sessions, roles and operations UI.

### Что Rain написал сам

Rain владеет password enrolment/sign-in, custom HS256 token protocol, refresh credentials and replay
detection, session database, Redis revocation, replay worker, attempt limiter, roles/grants,
credential/admin HTTP endpoints and security surface verification.

При этом documented gaps включают password reset, MFA, external identity providers and API keys.
То есть Rain уже несёт security-critical стоимость identity provider-а, не закрывая стандартный
identity product целиком.

### Verdict

Это самая опасная уже реализованная зона. Разделить два решения:

1. **Authentication/credential plane** — по умолчанию external OIDC provider + Spring Resource
   Server. Для server-side cookie приложения допустим Spring Session. Self-hosted protocol — Spring
   Authorization Server, только если отдельный auth product действительно нужен.
2. **Application authorization** — Rain grants/catalogue, actor mapping and explicit route policy
   могут остаться, но должны работать через стандартный Spring Security `Authentication` and
   `AuthorizationManager` boundary.

До решения не добавлять новые credential types, MFA, recovery или federation в `rain-access`.

## 6. `rain-event`

Текущий module — это не маленький event port. Он реализует PostgreSQL event store, optimistic
append, receipts, snapshots, committed global log, projection leases/checkpoints, topology splits,
park/redrive, rebuild generations, effect gates, jobs scheduling and realtime hints.

Ready alternatives зависят от настоящего требования:

- reliable application/domain events и transactional outbox — Spring Modulith Event Publication
  Registry/externalization;
- broker outbox — Spring Modulith, Eventuate Tram или explicit application outbox;
- настоящий event-sourced domain — Axon Framework либо purpose-built KurrentDB/EventStoreDB with
  its Java client and persistent subscriptions.

### Verdict

При отсутствии repository consumer-а продолжать собственную ES/projection platform нельзя.

Нужно выбрать ровно один сценарий:

1. Если нужны domain events/outbox — сделать Spring Modulith spike и архивировать большую часть
   `rain-event`.
2. Если event sourcing является продуктовой основой — оформить `rain-event` как отдельный product
   decision с нагрузочными, upgrade, backup/restore и operational compatibility gates. Сравнить с
   Axon/KurrentDB.
3. Не сохранять полный custom event store только как «optional framework feature».

Отдельный structural smell: `rain-event` API-зависит от `rain-jobs` и `rain-realtime`, хотя docs
называют PostgreSQL/projection/operational adapters optional. Kernel фактически тянет operational
stack; эту границу следует исправлять только после решения keep/replace, а не созданием ещё пяти
speculative modules.

## 7. I18n family

Для обычного Spring application уже есть Boot `MessageSource` auto-configuration, cached resource
bundles, explicit locale resolvers and ICU4J formatting. Translation authoring/review/release обычно
принадлежит TMS, а deployable application получает versioned artifact/bundle.

Rain построил собственные typed messages, `rain-mf2/v1` grammar/compiler, content-addressed
snapshots, review identities, overlays, runtime identity, rich parts, generator/tool, persistence,
release heads, pins, jobs, tenancy and vendor integration SPI. При этом ICU MessageFormat 2 Java
implementation всё ещё документирован как tech preview, что повышает стоимость собственного stable
grammar fork.

### Verdict

Текущий repository consumer доказывает только static in-memory catalog + web rendering. Для этого
около 14k строк и девять modules несоразмерны.

Минимальный target:

- Spring `MessageSource` или маленький immutable bundle adapter;
- ICU4J для plural/date/number formatting;
- generated typed keys/arguments, если compile-time safety действительно ценна;
- explicit locale/zone at asynchronous boundaries.

Durable releases, pins, tenant overlays, jobs, persistence and TMS SPI остаются incubating и не
считаются stable platform до отдельных consumers. Не добавлять новые i18n integrations до этого
разделения.

## 8. `rain-tenancy`

Готовые primitives существуют, но единого готового продукта с текущими гарантиями нет:

- PostgreSQL RLS обеспечивает shared-row enforcement;
- Spring `AbstractRoutingDataSource` и Hibernate multi-tenancy закрывают routing/separate database
  mechanics;
- external IdP/organization support закрывает identity membership, но не Rain data-plane lifecycle.

Поэтому проблема `rain-tenancy` не в том, что Spring имеет один drop-in replacement. Проблема в
scope: один module одновременно содержит authority/scopes, servlet resolution, lifecycle control
plane, provisioning workflow, fleet operations, shared-row RLS, database-per-tenant pool cache,
tenant job outbox, cache generation, settings and object-storage port — без sample consumer-а.

### Verdict

Сохранить можно маленькое authority/scope kernel. Затем выбрать **один** deployment topology,
который нужен первому consumer-у:

- shared-row + PostgreSQL RLS; или
- database-per-tenant routing.

Control plane, provisioning, fleet, cache/settings/storage and cross-context outboxes проходят
отдельный consumer gate. Пока topology не выбран, feature growth заморожен.

Как и event, `rain-tenancy` прямо API-зависит от web/audit/jobs/persistence/observability, поэтому
заявленная optional composition не соответствует artifact boundary.

## 9. `rain-web` и `rain-observability`

### Web

Spring Framework уже рендерит RFC 9457 `ProblemDetail`/`ErrorResponse`. Spring Security владеет
headers, CORS and CSRF. Boot/Micrometer владеют HTTP observations. Bucket4j даёт production token
bucket implementations including distributed backends.

Rain-owned value здесь уже:

- stable domain error codes and safe violation mapping;
- consistent pre-MVC/MVC error body, если стандартный handler не покрывает выбранный contract;
- request-id/privacy policy;
- explicit surface declarations, если команда действительно хочет startup completeness proof.

Кандидаты на удаление/замену: собственный generic Problem renderer, duplicate CORS/security header
configuration, in-memory throttle and custom probes. `RequestBudget` через interrupt serving thread
нужно отдельно пересмотреть: interrupt не является универсальной отменой JDBC/HTTP work и создаёт
новый lifecycle поверх server/framework timeouts.

### Observability/health

Boot уже даёт `HealthContributor`, health groups, custom statuses/aggregators,
ApplicationAvailability and liveness/readiness endpoints. Rain добавляет полезные, но более узкие
semantics: `required/degrading/informational`, concurrent absolute check budgets and one shared
evaluation per freshness window.

Цель — выразить эти semantics как Actuator extensions, не держать параллельные `HealthRegistry`,
custom probe controller and Actuator bridge. Если concurrency/timeout не нужны измеренному
deployment-у, оставить чистый Actuator.

## 10. `rain-resilience`

Resilience4j уже владеет breaker state machine, atomic permissions, half-open call ceiling,
open-wait transitions, events, health indicator and metrics.

Уникальная часть Rain — queue-aware rule «не claim work, когда breaker held» и максимум один probe
на cooldown. Она может быть полезна для jobs. Остальной `BreakerRegistry`/state/health/config wrapper
нужно сравнить с прямым `CircuitBreaker.tryAcquirePermission`, event publisher and standard
Resilience4j health/metrics.

Target — маленький `AdmissionGate` decorator для конкретного dispatcher-а, а не второй general
circuit-breaker API.

## 11. `rain-crud`

Spring Data REST умеет repository CRUD, paging, sorting, filtering/projections, validation and
conditional operations. jOOQ уже умеет seek/keyset pagination. Они не дают Rain contract целиком:
exact allow-listed query shapes, row scope on every DML, bounded count and executable query-plan
proof являются собственными решениями.

Поэтому `rain-crud` не является бессмысленной копией Spring Data REST, но пока это framework built
ahead of real applications. Его цена оправдана только если несколько production resources реально
используют один query dialect and plan proof.

До такого evidence:

- не расширять query language;
- сравнить application-local jOOQ controller/store с `CrudResource` на двух реальных resources;
- оставить plan-proof test utility независимо от решения о generic runtime;
- не мигрировать на Spring Data REST, если это разрушает explicit SQL/row-policy guarantees.

## 12. `rain-llm`

Spring AI уже владеет `ChatModel`/`ChatClient`, provider integration, retries, observations and usage/
rate-limit metadata. Rain добавляет database-backed cluster slots, local token pre-budget and breaker
accounting.

Cluster slots оправданы только для shared/self-hosted model server с измеренным hard concurrency
ceiling. Для hosted provider обычно важнее provider RPM/TPM metadata, client retries/backoff and
provider-side quotas. Без такого deployment `rain-llm` следует заменить маленьким application
decorator-ом над Spring AI.

## 13. Модули, которые выглядят здоровее

- `rain-persistence`: небольшой PostgreSQL policy layer; использует Boot jOOQ/Flyway/JDBC и Spring
  Framework `RetryTemplate`, добавляя SQLSTATE classification, transaction placement, statement
  bounds, advisory locks and module migration policy.
- `rain-audit`: same-transaction typed business evidence, чего Actuator auditing не даёт.
- `rain-realtime`: JDBC driver требует polling and connection management для notifications; bounded
  subscription/gap wrapper здесь реальная integration work. Оставлять только как ephemeral hint.
- `rain-boot`: aggregate validation, explicit roles/commands and injected `Clock` являются opinionated
  policy, а не новым application container. Не разращивать.
- `rain-core`, `rain-data-jdbc`, `rain-test`, `rain-architecture`: относительно небольшие и имеют
  ясную boundary.

## 14. Coupling problems

Текущие artifact boundaries делают optional features тяжелее, чем утверждают docs:

- `rain-event` API-зависит от jobs, realtime, persistence and observability;
- `rain-tenancy` API-зависит от web, audit, jobs, persistence and observability;
- `rain-access` API-зависит от web, audit, jobs and resilience;
- многочисленные i18n bridges пока не имеют independent repository consumers.

Не следует немедленно лечить это созданием десятков новых modules. Сначала принимается keep/shrink/
replace решение. После него retained kernel должен зависеть только от минимального engine, а
operational integrations — подключаться consumer-ом явно.

## 15. План действий

### E0 — stop the line

- [ ] Не добавлять функции в access authentication, event, i18n persistence/release/bridges и
  tenancy control/data-plane expansion до consumer decision.
- [ ] Пометить эти области incubating/experimental в release expectations.
- [ ] Не начинать storage/cache/runtime modules поверх текущего graph.

### E1 — consumer inventory

- [ ] Для каждого module перечислить imports вне его tests и samples.
- [ ] Назвать production workflow, deployment count, SLA and failure invariant.
- [ ] Удалить из required scope всё, что подтверждает только synthetic sample.

### E2 — replacement spikes

- [ ] Access: external OIDC/Keycloak + Resource Server + current Rain authorization mapping.
- [ ] Access alternative: Spring Session for first-party cookie session without custom JWT/revocation.
- [ ] Jobs: direct db-scheduler implementation одного текущего fenced/deduped workflow.
- [ ] Event: Spring Modulith publication registry for the actual domain-event/outbox use case.
- [ ] Event sourcing, только если требуется: Axon and KurrentDB comparison.
- [ ] I18n: Boot MessageSource + ICU4J + generated typed keys for the current sample.
- [ ] Health: Actuator-only implementation of current required/degrading cases.
- [ ] Web: standard ProblemDetail/Security/Bucket4j comparison for current surface.

### E3 — decide, do not layer

Для каждой области выбрать ровно одно:

- **keep** — custom invariant действительно нужен; удалить overlap с engine;
- **shrink** — оставить Rain policy/adapter, готовый engine делает механику;
- **replace** — перенести consumer на готовый product/library, сохранить characterization tests;
- **archive** — нет production consumer-а.

Нельзя оставить старую реализацию и добавить рядом новую «на выбор» без подтверждённых двух
deployments: это удваивает maintenance.

### E4 — first recommended order

1. Access authentication decision — самый высокий security risk.
2. Event keep/replace decision — самый высокий correctness/operations risk.
3. I18n scope reduction — самый большой разрыв implementation/consumer.
4. Tenancy topology selection — иначе дальнейшая работа остаётся speculative.
5. Jobs simplification spike — сохранить только доказанные unique guarantees.
6. Web/health/resilience convergence — меньший риск, возможен постепенно.

## 16. Definition of done

Аудит закрыт не тогда, когда выбрана библиотека, а когда для каждой красной/жёлтой области есть:

- concrete production consumer;
- минимальный requirement matrix;
- spike готового варианта;
- keep/shrink/replace/archive decision;
- migration or archive plan;
- tests, доказывающие только выбранные guarantees.

## 17. Авторитетные источники

- db-scheduler features — <https://github.com/kagkarlsson/db-scheduler>
- JobRunr capabilities and OSS/Pro split — <https://www.jobrunr.io/en/documentation/>,
  <https://www.jobrunr.io/en/documentation/pro/>
- Spring Security authorization and password storage —
  <https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html>,
  <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>
- Spring Session Redis/JDBC repositories — <https://docs.spring.io/spring-session/reference/api.html>
- Spring Authorization Server — <https://docs.spring.io/spring-authorization-server/reference/index.html>
- Keycloak server administration — <https://www.keycloak.org/docs/latest/server_admin/>
- Spring Modulith event publication registry —
  <https://docs.spring.io/spring-modulith/reference/events.html>
- Axon Framework — <https://docs.axoniq.io/axon-framework-reference/main/>
- KurrentDB Java persistent subscriptions —
  <https://docs.kurrent.io/clients/java/v1.1/persistent-subscriptions>
- Spring Boot internationalization —
  <https://docs.spring.io/spring-boot/reference/features/internationalization.html>
- ICU MessageFormat and MF2 Java status —
  <https://unicode-org.github.io/icu/userguide/format_parse/messages/>,
  <https://messageformat.unicode.org/docs/integration/java/>
- Spring Boot availability and Actuator health —
  <https://docs.spring.io/spring-boot/reference/features/spring-application.html>,
  <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>
- Resilience4j CircuitBreaker — <https://resilience4j.readme.io/docs/circuitbreaker>
- Spring Framework ProblemDetail —
  <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html>
- Spring Data REST — <https://docs.spring.io/spring-data/rest/reference/index.html>
- jOOQ seek pagination —
  <https://www.jooq.org/doc/latest/manual/sql-building/sql-statements/select-statement/seek-clause/>
- PostgreSQL RLS — <https://www.postgresql.org/docs/current/ddl-rowsecurity.html>
- Spring JDBC routing —
  <https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html>
- Spring AI observability and provider rate metadata —
  <https://docs.spring.io/spring-ai/reference/observability/>,
  <https://docs.spring.io/spring-ai/reference/api/aimetadata.html>
- PostgreSQL JDBC notifications —
  <https://jdbc.postgresql.org/documentation/server-prepare/>
