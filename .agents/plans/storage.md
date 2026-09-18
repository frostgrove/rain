# План: object storage для Rain — buy before build

Статус: пересмотрен после make-or-buy анализа; реализация не начата

Дата пересмотра: 2026-09-17

Связанный план: [vv-framework-make-or-buy.md](vv-framework-make-or-buy.md)

Исходник идей: `../../vv/framework/storage`

## 1. Решение

Предыдущий план был переинженерен. Он преждевременно проектировал шесть publishable modules,
собственный portable storage protocol, production filesystem format, multipart engine,
stage/claim/cleanup state machine, web proxy и отдельную observability-интеграцию. В Rain пока нет
ни одного подтверждённого application consumer такого масштаба.

Базовое решение теперь такое:

1. Для обычного S3 use case использовать готовые Spring Cloud AWS `S3Template`/`S3Resource`,
   `S3Presigner` и AWS SDK for Java 2.x.
2. Для больших либо заранее неизвестных по длине потоков использовать готовый
   `TransferManagerS3OutputStreamProvider` или multipart-enabled `S3AsyncClient`. Не писать свой
   multipart orchestration.
3. Если storage нужен только tenancy, реализовать S3-backed `TenantObjectBackend` в optional
   pairwise module. Не создавать общий `rain-storage` ради одного consumer.
4. Общий Rain contract допускается только после consumer gate из раздела 5. Его размер должен быть
   существенно меньше API provider-а и содержать только Rain-owned policy.
5. Stage/promote/claims, filesystem backend, download proxy и generic middleware не входят в v1.

Иными словами, первая реализация, скорее всего, состоит из одного небольшого adapter-а над готовым
S3 engine. Возможно, она вообще остаётся application-local до появления второго независимого
consumer.

## 2. Что уже готово

### 2.1 Spring Cloud AWS

`S3Template` и `S3Resource` уже дают стандартные операции загрузки, чтения, удаления, metadata и
presigned GET/PUT. Spring Boot/Spring Cloud AWS берут на себя credentials chain, region, endpoint,
clients и lifecycle. `TransferManagerS3OutputStreamProvider` даёт `OutputStream`, пишущий через
AWS `S3TransferManager`.

Это покрывает большую часть прежних `rain-storage-s3`, `rain-storage-web` и configuration work.
Используются стандартные AWS/Spring Cloud AWS properties; параллельное дерево
`rain.storage.s3.*` не создаётся без доказанной необходимости.

### 2.2 AWS SDK for Java 2.x

Начиная с AWS SDK 2.27.5 обычный Java-based `S3AsyncClient` умеет automatic multipart upload и
download. При `multipartEnabled(true)` он эффективно принимает поток неизвестной длины через
`AsyncRequestBody.fromInputStream(..., null, executor)`. `S3TransferManager` даёт high-level
parallel transfers, progress и multipart copy/download.

Следовательно, Rain не должен реализовывать:

- ручное разбиение на parts, completion и abort;
- whole-stream buffering ради определения длины;
- собственный transfer executor/progress protocol;
- собственный общий presigning engine;
- retries/credentials/endpoint resolution поверх AWS SDK.

### 2.3 Что не стоит брать как основу

| Вариант | Решение | Причина |
|---|---|---|
| Spring Cloud AWS + AWS SDK v2 | **Выбран** | Живой Spring-native stack; закрывает S3 operations, resources, presigning и multipart |
| AWS SDK v2 напрямую | **Разрешён узко** | Нужен для provider-specific preconditions или функций, которых нет в `S3Template`; не повод оборачивать весь SDK |
| Apache jclouds `BlobStore` | **Не брать** | Проект retired; portable API покупается ценой устаревшего stack и weakest-common-denominator semantics |
| Spring Content | **Не брать** | Проект архивирован 2026-02-26; ориентирован на content repositories/entity association, а не на нужные Rain invariants |
| MinIO Java SDK | **Не брать по умолчанию** | Ещё один S3 client без выигрыша для AWS/Spring приложения; рассматривать только при доказанной несовместимости целевого S3 service с AWS SDK |
| Собственная portable abstraction | **Отложить** | Нет второго provider-а и двух независимых consumers; сейчас это speculative portability |
| Production filesystem backend | **Отложить** | Не нужен для production target; для dev/IT точнее использовать реальный S3-compatible container |

Для локальной разработки и integration tests использовать Testcontainers с целевым S3-compatible
service либо LocalStack/MinIO согласно production compatibility target. In-memory fake допустим
только для быстрых unit tests, но не доказывает S3 semantics.

## 3. Что из VV всё-таки полезно

Из `../../vv/framework/storage` переносится не framework, а небольшой набор требований:

- ключи и signed URL не попадают в обычные логи и telemetry;
- tenant/application code не видит credentials, bucket и provider requests;
- input/output streaming ownership описан явно;
- object size, media type, TTL и key length ограничены;
- delete идемпотентен там, где этого ожидает domain workflow;
- create-only/replace выбираются явно, если consumer действительно нуждается в защите от overwrite;
- ошибки provider-а переводятся только в небольшой стабильный набор Rain failures на boundary;
- startup не создаёт и не изменяет buckets; provisioning остаётся в IaC;
- real-provider tests доказывают заявленные preconditions и presigned links.

Не переносить Go-specific API, MinIO-specific workarounds и сложный protocol только потому, что они
уже написаны в VV.

## 4. Что уже есть в Rain

В `rain-tenancy` уже существует полезный domain layer:

- `TenantObjectName` проверяет logical name;
- `HmacTenantObjectStoreFactory` выводит opaque address из scope и epoch;
- READ/WRITE admission перепроверяется на каждой операции;
- signed URL bounded по TTL и redacted;
- backend получает opaque `TenantObjectAddress`, а не raw tenant id.

Это и есть Rain-owned value. Не нужно сначала переносить его в generic storage abstraction, а потом
оборачивать обратно tenancy adapter-ом.

На момент пересмотра в репозитории не найдено:

- production implementation `TenantObjectBackend`;
- второго non-tenancy storage consumer;
- требования к list/range/CAS/staging;
- workflow, которому нужен upload-before-database-commit;
- production requirement одновременно поддерживать S3 и filesystem/Azure/GCS.

Поэтому текущая точка расширения уже достаточно мала. Если первый consumer tenant-scoped, наиболее
прямой следующий шаг — optional `rain-tenancy-storage-s3` или аналогично названный pairwise adapter
с реализацией `TenantObjectBackend` через готовый S3 stack.

## 5. Consumer gate

До создания любого нового module владелец первого workflow фиксирует короткий ADR/issue с ответами:

1. Какой concrete workflow и какой module являются consumer-ом?
2. Почему прямой `S3Template`/`S3Resource` либо текущий `TenantObjectBackend` недостаточен?
3. Максимальный размер объекта, известна ли длина заранее и сколько concurrent transfers ожидается?
4. Нужны ли signed GET, signed PUT, range, create-only, overwrite либо revision CAS?
5. Как согласуются database commit и object write; что происходит при crash между ними?
6. Кто удаляет orphan objects и по какому bounded index, а не через полный bucket scan?
7. Нужен ли реально второй provider в обозримом deployment, и какой именно?
8. Какие tenant, retention, encryption и audit requirements принадлежат application domain?

Если ответы описывают один S3 workflow, решение остаётся local/pairwise adapter-ом. Общая платформа
не создаётся.

## 6. Минимальный вариант реализации

### 6.1 Вариант A — предпочтительный для tenancy-only consumer

Добавить один optional module, который:

- зависит от `rain-tenancy` и Spring Cloud AWS S3 support;
- реализует только `TenantObjectBackend`;
- делегирует put/open/delete/presign готовым APIs;
- сохраняет streaming и exact metadata mapping;
- переводит только ожидаемые provider failures;
- содержит Docker-backed integration tests против выбранного provider profile.

Новых generic `ObjectStore`, registry, middleware, capability discovery и filesystem classes в этом
варианте нет.

### 6.2 Вариант B — общий contract, только после gate

Если появились как минимум два независимых consumers, которым нужна одна и та же policy, допустимы:

- `rain-storage` — небольшой provider-neutral port без SDK dependency;
- `rain-storage-s3` — Spring Cloud AWS/AWS SDK adapter;
- `rain-tenancy-storage` — только если он реально заменяет текущий duplicate port.

Начальный contract не должен быть шире следующего:

```kotlin
public interface ObjectStorage {
    public fun put(key: ObjectKey, content: ObjectContent, mode: WriteMode): StoredObject
    public fun open(key: ObjectKey): ObjectBody?
    public fun head(key: ObjectKey): StoredObject?
    public fun delete(key: ObjectKey): Unit
    public fun signedReadUrl(key: ObjectKey, ttl: Duration): SignedReadUrl?
}
```

Точный Kotlin API проектируется только после consumer examples. `WriteMode` не имеет неявного
default: caller выбирает `CREATE_ONLY` или `REPLACE`. Revision CAS добавляется отдельно, только если
есть workflow и provider test, доказывающие его атомарность.

### 6.3 Provider-specific operations

Обычный путь делегируется `S3Template`/`S3Resource`. Если consumer требует conditional write,
разрешён небольшой прямой вызов AWS SDK с `If-None-Match`/`If-Match`; он остаётся внутри S3 adapter-а.
Это не оправдывает собственный universal backend protocol.

Для большого unknown-length conditional write v1 выбирает одно из трёх честных решений:

1. immutable unique object keys, после чего overwrite невозможен по construction;
2. documented size/operation restriction;
3. отдельный staging ADR после подтверждённого workflow.

Собственный claim state machine не является default.

## 7. Явно отложено

| Возможность из старого плана | Когда пересматривать |
|---|---|
| `rain-storage-test` как publishable artifact | Когда conformance suite используют два production provider-а |
| production filesystem backend/private file format | Когда появится production deployment без object service |
| stage/promote/abort и claim/lease protocol | Когда есть upload-before-commit workflow и описанная crash matrix |
| background cleanup | Когда есть durable orphan index и owner scheduling policy |
| generic signed-download MVC proxy | Когда provider не умеет native presign либо требуется application authorization на каждый byte request |
| range reads | Когда concrete media/download consumer требует Range |
| arbitrary user metadata/checksums | Когда consumer владеет их schema и limits |
| revision CAS | Когда есть реальный concurrent update workflow и provider conformance proof |
| presigned upload | Когда threat model, object validation и orphan cleanup согласованы |
| capability negotiation | Когда одновременно работают два provider-а с различающейся semantics |
| storage middleware chain | Когда два независимых cross-cutting decorators нельзя выразить Spring composition/Observation |
| `rain-storage-observability` | Когда generic storage module существует и generic signals действительно нужны |
| storage-owned threads/retry/cleanup loops | Не добавлять; использовать SDK, Spring lifecycle и `rain-jobs` на application boundary |
| bucket creation/versioning/lifecycle | Не добавлять в runtime; это IaC/operator responsibility |

## 8. Observability и ошибки

Не создавать отдельный telemetry subsystem. Если adapter нуждается в application-level signal,
использовать Micrometer `ObservationRegistry`. AWS SDK/HTTP instrumentation остаётся provider-level
telemetry.

Допустимые low-cardinality поля: operation, outcome и provider profile. Недопустимые labels/log
fields: bucket, object key/address, tenant, signed URL, endpoint, ETag, metadata и raw exception
response.

Provider exceptions не должны полностью копироваться в публичный contract. Минимально полезны:

- not found как nullable result там, где это часть обычного lookup;
- conflict/precondition failed для явно запрошенного conditional write;
- unavailable/deadline для внешнего failure;
- invalid/source error для нарушенного caller contract.

Остальные детали остаются cause для controlled diagnostics.

## 9. Проверки перед принятием adapter-а

- unit tests на key/TTL/size validation, redaction и stream ownership;
- integration tests на put/open/head/delete и media type;
- unknown-length stream, превышающий multipart threshold, без whole-body buffering;
- presigned GET с фактическим expiry;
- create-only race test, только если capability заявлена;
- retry/lost-response test доверяется SDK там, где SDK владеет retry; Rain не дублирует его;
- Testcontainers/provider profile совпадает с production target;
- startup test подтверждает отсутствие bucket mutations;
- telemetry/log capture подтверждает отсутствие addresses и signed URLs.

In-memory fake не считается доказательством S3 atomicity, multipart или signing.

## 10. Фазы

### Фаза S0 — доказать consumer

- [ ] Заполнить consumer gate.
- [ ] Выбрать production provider/profile и test container.
- [ ] Зафиксировать database/object failure ordering.
- [ ] Решить, достаточно ли application-local implementation.

### Фаза S1 — spike готового stack

- [ ] Проверить совместимую с текущим Spring Boot линию Spring Cloud AWS.
- [ ] Реализовать spike put/open/delete/presign через `S3Template`/`S3Resource`.
- [ ] Проверить unknown-length multipart через Transfer Manager или multipart-enabled async client.
- [ ] Проверить endpoint/path-style behavior на целевом S3-compatible provider-е, если это не AWS.

### Фаза S2 — минимальный production adapter

- [ ] Добавить только выбранный в S0 module/adapter.
- [ ] Сохранить standard AWS properties и credentials chain.
- [ ] Добавить bounded config validation без custom secret loader.
- [ ] Пройти integration и privacy tests из раздела 9.

### Фаза S3 — расширять только по evidence

- [ ] Для каждой новой операции записать concrete consumer и отсутствующую гарантию готового stack.
- [ ] Для portability сначала добавить второй production adapter и conformance tests, затем расширять API.
- [ ] Для staging сначала описать crash/recovery matrix и ownership orphan index.

## 11. Definition of done для v1

V1 завершён, когда один реальный workflow хранит и читает объекты через готовый S3 engine, имеет
Docker-backed proof для production profile, не раскрывает tenant/key/signed URL, не делает bucket
mutations на startup и не содержит собственного multipart, filesystem, scheduler, retry или cleanup
framework.

## 12. Авторитетные источники

- Spring Cloud AWS 4.1.1 API: `S3Template`, `S3Resource`, `S3OutputStreamProvider`,
  `TransferManagerS3OutputStreamProvider` — <https://docs.awspring.io/spring-cloud-aws/docs/4.1.1/apidocs/>
- AWS SDK v2 S3 Transfer Manager —
  <https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/transfer-manager.html>
- AWS SDK v2 automatic multipart and unknown-length streams —
  <https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-async-client-multipart.html>
- AWS stream upload guidance —
  <https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/best-practices-s3-uploads.html>
- AWS conditional writes —
  <https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html>
- Apache jclouds retirement notice — <https://jclouds.apache.org/>
- Spring Content archive — <https://github.com/paulcwarren/spring-content>
