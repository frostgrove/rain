# План: VV framework make-or-buy для Rain

Статус: принято как архитектурное руководство; implementation items возникают только после
consumer gate

Дата: 2026-09-17

Связанные планы: [storage.md](storage.md),
[existing-modules-make-or-buy.md](existing-modules-make-or-buy.md)

## 1. Итог

Переносить `../../vv/framework` в Rain module-for-module не нужно. На JVM большую часть
инфраструктурной механики уже дают Spring Boot, Spring Framework, Spring Security, Micrometer и
зрелые provider libraries. Rain должен писать только свою domain policy и гарантии, которых у
готового engine нет.

Главное правило:

> provider/framework владеет механизмом; Rain добавляет только bounded policy adapter вокруг
> concrete consumer invariant.

Новый generic module не принимается только потому, что аналог есть в VV. Сначала нужен concrete
consumer и точное описание отсутствующей гарантии. Если вопрос решается configuration, стандартным
extension point, decorator-ом или application-local adapter-ом, новый Rain framework не создаётся.

## 2. Admission gate для нового platform code

Перед переносом любой части VV ответить:

1. Кто concrete consumer и какой production workflow блокирован?
2. Какой готовый Spring/provider API уже решает задачу?
3. Какая одна наблюдаемая гарантия в нём отсутствует?
4. Можно ли добавить её bean-ом, validator-ом, `AuthenticationProvider`, `ObservationConvention`,
   `SmartLifecycle`, client interceptor-ом или другим штатным extension point?
5. Нужна ли portability сейчас, то есть существуют ли два production provider-а/consumer-а?
6. Contract Rain меньше provider API или просто прячет его под другим названием?
7. Есть ли real-provider/concurrency/crash test, который доказывает новую гарантию?
8. Кто владеет migration, cleanup, retries, threads и shutdown?

Если нет concrete consumer, точного gap и доказуемого test — решение **defer**, а не новый module.

## 3. Сводная матрица

| Область VV | Готовая основа | Что принадлежит Rain | Решение |
|---|---|---|---|
| `storage` | Spring Cloud AWS + AWS SDK v2 | tenant addressing, bounded policy конкретного workflow | Пересмотрен отдельно; generic framework пока не строить |
| `cache` | Spring Cache + Caffeine/Redis; при сложной L1/L2 policy рассмотреть JetCache | cache names/key namespace, domain invalidation, freshness SLA | Не портировать cache engine |
| `runtime` | `SmartLifecycle`, `Lifecycle`, `TaskExecutor`, `TaskScheduler`, ApplicationAvailability | только ordering/fencing конкретного Rain worker | Не создавать `rain-runtime` |
| `doctor` | Actuator health/info/conditions/configprops/beans/mappings/scheduledtasks/startup + `config-check` | offline Rain config rules и bounded domain probes | Не создавать module catalogue/doctor framework |
| `otel` | Micrometer Observation + Boot Actuator/OpenTelemetry bridge | low-cardinality conventions и privacy policy | Не портировать VV OTel layer |
| external JWT | Spring Security OAuth2 Resource Server | mapping claims в Rain actor/grants | Тонкий security adapter при реальном external issuer |
| API key | Spring Security filter + `AuthenticationProvider` | lookup/hash/rotation/audit policy | Тонкий provider, не auth framework |
| remote HTTP | `RestClient`, `WebClient`, HTTP Service interfaces | typed application client и error mapping | Не портировать remote repository abstraction |
| audit | Actuator security audit + существующий `rain-audit` | transactional business evidence | Сохранить Rain audit; не заменять Actuator-ом и не портировать VV alpha |
| errors | Spring `ProblemDetail`/`ErrorResponse` + текущие Rain Faults | stable domain codes и safe details | Доращивать существующий boundary, не второй error system |
| config | Boot binding/validation + текущий strict Rain validator | unknown-key/environment policy | Сохранить текущий Rain layer |
| health | Actuator + текущий Rain health bridge | bounded readiness semantics | Не второй health registry |
| jobs | Spring lifecycle + db-scheduler + `rain-jobs` | durable intents, leases, fences, ledger | Сохранить; готовый local scheduler не заменяет durable semantics |
| event/i18n/tenancy/access/crud | Уже реализованы в Rain | собственные domain contracts | Не заводить второй параллельный port из VV |
| probe/CRUD helpers | Spring Data/Actuator; Rain использует jOOQ и свой CRUD | только доказанный query/plan/security invariant | Defer до конкретного gap |
| `app` module catalogue | Boot auto-configuration, bean graph, profiles + текущий Rain runtime role | Rain role/command selection | Не портировать второй DI/module container |
| `cmd`/flags | текущий `rain-boot` command API и Boot property binding | конкретные offline commands | Не переносить generic CLI runtime |
| `port` | текущие `rain-crud`, `rain-web`, Spring MVC | Rain query/security/violation policy | Не портировать generic CRUD service/HTTP envelope |
| `vvdb`/`vvgoose` | `rain-persistence`, jOOQ, Flyway и build-logic codegen | plan bounds, transaction placement, module migrations | Не переносить database toolkit |
| `utils` | Kotlin/JDK/Spring Boot | только proven bounded value type | Не собирать grab-bag utility module |
| `test`/internal codegen | `rain-test`, JUnit/Testcontainers/ArchUnit, Gradle tasks | corpus/conformance cases и architecture rules | Брать test ideas, не Go harness/generator |

## 4. Cache

### Решение

Не переносить VV cache как отдельную платформу.

Базовый путь:

- Spring Cache для annotation/API boundary;
- Caffeine для bounded local cache;
- Spring Data Redis `RedisCacheManager` для shared cache;
- Boot auto-configuration и стандартные `spring.cache.*` properties;
- Boot/Micrometer cache instrumentation вместо своего telemetry module.

Caffeine уже даёт atomic `get(key, loader)`, `LoadingCache`, async loading, size/weight eviction и
`refreshAfterWrite` с deduplication refresh-а. Для простого method cache допустим
`@Cacheable(sync = true)`, но это provider hint, а не distributed single-flight guarantee.

Spring Cache специально не обещает multi-process stampede prevention и distributed consistency.
Если возникнет реальное требование к L1+L2, cross-node invalidation, distributed refresh или
penetration protection, сначала сделать spike JetCache: он уже поддерживает Caffeine+Redis,
two-level cache, sync local invalidation, auto refresh и penetration protection. Redisson имеет
Spring Cache integration, но near/local cache в его Spring Cache manager относится в основном к
PRO-линейке; выбирать его нужно только если Rain уже принимает Redisson как Redis client/platform.

### Что Rain всё ещё обязан решить

- стабильные cache names и versioned key encoding;
- запрет tenant leakage между key spaces;
- TTL/freshness SLA и negative caching policy;
- domain invalidation после commit/event;
- bounded local capacity;
- failure policy: cache miss/fail-open либо correctness-critical refusal;
- сериализация и rolling-upgrade compatibility для Redis values.

Это configuration/policy, а не причина писать новый cache engine.

## 5. Runtime, workers и scheduling

Для local background components использовать готовые Spring primitives:

- `SmartLifecycle` для phase ordering, start/stop и graceful callback;
- managed `TaskExecutor`/`TaskScheduler` вместо самодельного executor registry;
- Boot shutdown/await-termination properties;
- `ApplicationAvailability`, readiness/liveness events и Actuator probes;
- Actuator `scheduledtasks` для introspection.

`rain-jobs` остаётся оправданным: Spring scheduler не даёт durable intents, database leases,
reclaim fencing, dedupe, dead-letter/retention ledger и crash recovery. Не следует заменять его
Quartz/JobRunr/ShedLock либо VV runtime только ради другого scheduling API. Новый engine
рассматривается лишь при доказанном gap существующего `rain-jobs`/db-scheduler stack.

Новые components не создают thread/executor напрямую без причины. Но общий `rain-runtime` также не
нужен: Spring уже является runtime container.

## 6. Doctor, health и configuration diagnostics

Для запущенного приложения готовые Actuator endpoints уже показывают:

- health/readiness/liveness;
- auto-configuration conditions;
- bound configuration properties;
- beans и request mappings;
- scheduled tasks;
- startup steps при включённом startup recorder.

Rain уже имеет `rain-boot config-check`, strict configuration contributors и свой health bridge.
Сохраняется только то, чего Actuator принципиально не делает: offline check без полного runtime,
Rain-specific unknown-key policy и bounded domain invariants.

Не строить VV-style central module catalogue, dependency doctor и второй health model. Если нужен
единый support bundle, сначала сделать CLI/script, который собирает существующие Actuator outputs и
redacts secrets; не вводить новую runtime abstraction.

## 7. Observability

Новый code использует Micrometer `ObservationRegistry`, `ObservationConvention`, predicates,
filters и handlers. Boot связывает observations с metrics/traces; прямое построение параллельной
OpenTelemetry abstraction не нужно.

Правила Rain:

- low-cardinality tags могут попадать в metrics и traces;
- high-cardinality values — только в traces и лишь когда они безопасны;
- tenant/user/object/message IDs, tokens, URLs, SQL values и raw exception payloads не являются
  metric labels;
- instrumentation не меняет business outcome;
- для тестов использовать `TestObservationRegistry` и privacy/cardinality corpus;
- pairwise observability module создаётся только когда base bounded context не должен зависеть от
  Micrometer и есть реальный reusable integration.

Существующие прямые metrics можно постепенно нормализовать по Observation conventions при
изменении соответствующего module; отдельная большая миграция без consumer benefit не нужна.

## 8. Authentication и security

### External JWT

Spring Security Resource Server уже умеет issuer/JWK discovery, rotation, `exp`/`nbf`, issuer,
audience validators и standard bearer processing. VV JWT verifier не переносить.

Текущий `rain-access` содержит собственную session/token domain policy, revocation, refresh,
attempt limiting и audit; это не следует выбрасывать только потому, что Spring умеет проверять JWT.
Для доверенного внешнего issuer-а нужен отдельный тонкий bridge:

1. Resource Server валидирует token cryptographically.
2. Rain converter отображает уже validated claims в actor/subject/grants.
3. Rain admission продолжает владеть authorization policy.

### API keys и service credentials

Использовать Spring Security `AuthenticationProvider`/filter chain. Rain пишет только:

- безопасный parser канала credential;
- hashed lookup, status, expiry, rotation/revocation;
- scope/grant mapping;
- rate/attempt policy;
- audit evidence без raw key.

Если подходит OAuth2 client credentials с внешним authorization server, это предпочтительнее
собственной API-key подсистемы.

## 9. Outbound HTTP и remote APIs

Не переносить generic VV repository/client framework. Использовать:

- `RestClient` для blocking clients;
- `WebClient` для reactive/streaming use cases;
- HTTP Service interfaces (`@HttpExchange`) для declarative typed clients;
- standard request factories, interceptors, OAuth2 и Micrometer instrumentation.

На каждый external service создаётся явный application port/client с domain methods. Он владеет
timeouts, idempotency keys, safe error translation и response bounds. Generic `RemoteRepository<T>`
скрывает слишком много transport semantics и не принимается.

## 10. Audit

Actuator auditing полезен для Spring Security authentication success/failure/access denied, но его
in-memory repository предназначен только для development, а generic `AuditEventRepository` не даёт
атомарность с business transaction.

Существующий `rain-audit` остаётся правильным для typed business evidence в той же database
transaction, bounded detail schema и keyset reads. Его не заменять Actuator audit и не переписывать
по VV. При необходимости Spring Security events адаптируются в `rain-audit` на явной boundary.

Следующие улучшения принимаются только по evidence: retention/partitioning, export/outbox,
tamper-evidence и regulated access. Это отдельные operational requirements, а не повод импортировать
чужой audit module целиком.

## 11. Error model

Spring MVC уже имеет RFC 9457 `ProblemDetail`/`ErrorResponse`; Rain уже имеет `Fault`, stable codes и
web translation. Не создавать новый `vverrors` equivalent.

Правило boundary:

- domain/application code возвращает Rain typed failure;
- provider exception сохраняется cause, но не становится public schema;
- web bridge переводит failure в существующий problem format;
- validation violations структурируются в текущем Rain contract;
- stack trace, SQL/provider payload и secrets наружу не выходят.

## 12. Остальные каталоги VV

### `app`, `cmd` и module doctor

`app/module` решает проблему Go DI container-а: собирает constructors, routes, workers, seeders,
checks и фильтрует их по runtime role. В Rain этот слой уже образуют Spring bean graph,
auto-configuration conditions, `RuntimeRole`, seeders/commands и Actuator conditions/beans.
Второй catalogue создаст две истины о составе приложения. Не переносить.

`cmd/vv` и generic flag helpers также не нужны: offline operations добавляются в существующий
`rain-boot` command surface, а production configuration проходит Boot binding и Rain validation.
Если CLI разрастётся до отдельного продукта, тогда отдельно оценить Picocli/Spring Shell; сейчас
это лишняя dependency и abstraction.

### `port` и HTTP rendering

VV `port` — generic CRUD service, query/path mapping, violations и HTTP envelope. Rain уже имеет
`rain-crud`, `rain-web`, jOOQ-aware plan proof, свой Fault/validation contract и Spring MVC. Порт
создаст второй несовместимый CRUD/web stack. Брать можно отдельные adversarial cases: ambiguous
paths, bounded body/index, field-violation mapping и safe rendering, если текущие тесты обнаружат
gap.

### `vvdb`, `vvgoose` и database utilities

Rain уже выбрал jOOQ, Flyway, PostgreSQL, module-owned migrations и Gradle code generation.
`vvdb`/Goose/ORM adapters не переносить. Полезны только invariants: transaction placement, bounded
queries, qualified tables, replica/tenant separation и real-dialect tests — многие из них уже есть
в `rain-persistence`, CRUD и architecture gates.

### `utils`, `test` и generators

Go `optional` не нужен при Kotlin nullability/sealed values. `vvcfg` перекрыт Boot binding и
strict Rain validator. `vvflag` перекрыт command/property parsing. Не создавать общий `rain-utils`.

Из большого VV test corpus стоит переносить только missing scenarios в соответствующий Rain
module: cross-module composition, serialization compatibility, privacy corpus, race/crash tests и
provider matrices. Go fixtures, ORM matrices и reflection/code generators не переносятся. Для
Observation documentation сначала использовать Micrometer `ObservationDocumentation` и его docs
generator, а не портировать `vv-otel-gen`.

## 13. Что реально стоит взять из VV

Не кодовые подсистемы, а проверенные invariants и test ideas:

- bounded inputs/queries/scans/cleanup;
- explicit ownership stream/resource;
- redacted secret/bearer values;
- create-only/CAS без silent downgrade;
- no blind retry после uncertain write;
- crash/restart/race matrices для durable components;
- no startup infrastructure mutation;
- low-cardinality/privacy tests;
- tenant scope/epoch separation;
- real-provider conformance вместо mock-only уверенности;
- distinction между capability и authorization.

Эти идеи добавляются в конкретные Rain modules, где есть corresponding workflow. Они не требуют
общего VV compatibility layer.

## 14. Что не переносить

- Fx/container wiring и Go lifecycle mechanics;
- Go error/nil/context patterns;
- wrapper над Spring Boot только ради одинаковых имён с VV;
- собственные cache, HTTP client, JWT verification, scheduler, OTel или S3 transfer engines;
- generic registry/discovery/middleware без двух consumers;
- provider capability matrix до появления двух production providers;
- automatic retries/cleanup/background loops, скрытые внутри низкоуровневого adapter-а;
- filesystem backend только ради unit tests;
- модуль на каждую пару областей без реального dependency boundary.

## 15. План действий

### P0 — применить правило к новым предложениям

- [x] Провести inventory текущих Rain modules и VV candidates.
- [x] Проверить готовые Spring/provider alternatives.
- [x] Пересмотреть storage plan по buy-before-build.
- [ ] Для каждого следующего infrastructure proposal заполнять admission gate.

### P1 — cache только при первом consumer

- [ ] Зафиксировать consumer, freshness и invalidation requirements.
- [ ] Начать со Spring Cache + Caffeine или Redis.
- [ ] Проверить Boot cache metrics и bounded capacity.
- [ ] JetCache/Redisson spike делать только при доказанной distributed L1/L2 необходимости.
- [ ] Не публиковать `rain-cache`, пока нет reusable Rain-owned policy для двух consumers.

### P2 — security integrations по запросу

- [ ] Для external issuer использовать Resource Server spike, не новый verifier.
- [ ] Для API key сравнить OAuth2 client credentials и thin `AuthenticationProvider`.
- [ ] Оставить session/revocation/business authorization в `rain-access`.

### P3 — diagnostics без нового subsystem

- [ ] Документировать набор рекомендуемых Actuator endpoints и exposure policy, когда появится
  operational consumer.
- [ ] Расширять `config-check` только Rain-specific offline rules.
- [ ] Support bundle сначала реализовать как redacted composition существующих outputs.

### P4 — observability convergence

- [ ] Новый instrumentation писать через Observation API.
- [ ] Старые direct metrics менять только вместе с затронутым bounded context.
- [ ] Каждый bridge проверять на cardinality и sensitive data.

## 16. Definition of done

Этот план считается внедрённым как policy, когда новые infrastructure PR явно называют готовый
engine, точный gap и concrete consumer; speculative modules не появляются, а Rain-owned adapters
остаются маленькими и имеют real failure/concurrency tests соразмерно заявленной гарантии.

## 17. Авторитетные источники

- Spring Boot caching and supported providers —
  <https://docs.spring.io/spring-boot/reference/io/caching.html>
- Spring Cache abstraction limits —
  <https://docs.spring.io/spring-framework/reference/integration/cache/strategies.html>
- Caffeine population/refresh/eviction — <https://github.com/ben-manes/caffeine/wiki/Population>,
  <https://github.com/ben-manes/caffeine/wiki/Refresh>,
  <https://github.com/ben-manes/caffeine/wiki/Eviction>
- Spring Data Redis cache —
  <https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html>
- JetCache features — <https://github.com/alibaba/jetcache>
- Spring lifecycle and scheduling —
  <https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html>,
  <https://docs.spring.io/spring-framework/reference/integration/scheduling.html>
- Spring Boot availability and Actuator —
  <https://docs.spring.io/spring-boot/reference/features/spring-application.html>,
  <https://docs.spring.io/spring-boot/api/rest/actuator/>
- Spring Boot observability —
  <https://docs.spring.io/spring-boot/reference/actuator/observability.html>
- Micrometer Observation components —
  <https://docs.micrometer.io/micrometer/reference/observation/components.html>
- Spring Security JWT resource server —
  <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>
- Spring Security authentication architecture —
  <https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html>
- Spring REST clients and HTTP Service interfaces —
  <https://docs.spring.io/spring-framework/reference/integration/rest-clients.html>
- Spring Boot auditing — <https://docs.spring.io/spring-boot/reference/actuator/auditing.html>
