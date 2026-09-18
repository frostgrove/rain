# Handoff: tenancy-and-eventsource-2

Статус на 2026-09-17: ParkSequence/redrive slice продолжен и проверен; основной план находится в
`tenancy-and-eventsource-2`; этот файл — только точка продолжения и не заменяет план.

## Уже сделано в этой сессии

- Ранее в ветке были реализованы snapshots, committed global log, projection declaration/checkpoints,
  `AFTER_APPLY`, `SAME_UNIT`, generation/barrier/cutover/effect gate и `ProjectionWaiter`.
  До текущего шага проходил:
  `./gradlew :rain-event:check :rain-event-test:check verifyModuleGraph verifyDocsCoverage`.
- Для ParkSequence добавлены:
  - `ProjectionFailure`, stable `ProjectionFailureCode`, redacted `ProjectionSequenceId`, bounded
    `ProjectionLetter` и byte accounting в `rain-event/.../projection/ProjectionFailure.kt`;
  - явный `SequenceKeyHasher.sequence(event)`. Это намеренно не выводится из 64-bit partition hash:
    hash collision не должен объединять причинные очереди. Все существующие test hashers обновлены;
  - public contracts `ProjectionHold`, letters, fenced redrive, operator holes и bounded status в
    `rain-event/.../projection/ProjectionHold.kt`;
  - PostgreSQL adapter `PostgresProjectionHoldStore`;
  - V6 migration `projection_hold`, `projection_letter`, `projection_halt`, `projection_hole`;
  - fenced durable halt в checkpoint contract и `PostgresProjectionCheckpointStore`;
  - generation readiness/cutover теперь отказываются при hold, unacknowledged hole или halted lane;
  - `SameUnitProjectionSavepoint` и PostgreSQL savepoint adapter;
  - `SameUnitProjectionRunner` получил отдельную ParkSequence ветку: per-envelope handler,
    savepoint rollback failed envelope, durable queue, continuation других sequence и durable halt
    на capacity. Потеря checkpoint lease после destination write теперь бросает exception, чтобы
    caller transaction откатилась, а не закоммитила partial SAME_UNIT work.

## Продолжение ParkSequence/redrive в этой сессии

- `same-unit park rolls back only the failed envelope, queues its sequence, and advances unrelated work` теперь
  подтверждён green.
- Добавлен `ProjectionRedriveRunner`. Он берёт строго один queue head в caller-owned SAME_UNIT transaction,
  проверяет hold/destination authority, откатывает failed handler к savepoint, acknowledge делает только после
  destination write, записывает stable attempt code при failure и releases lease после bounded successful pass с
  remaining letters. Потеря redrive lease бросает exception до commit, а не возвращает misleading success.
- Добавлен `ProjectionRedriveRelease` и его PostgreSQL implementation: без explicit release single-head runner
  оставлял бы remaining queue в `REDRIVING` до TTL.
- `ProjectionHoldStoreSupport` теперь mint'ит redrive leases так же, как checkpoint support mint'ит checkpoint
  leases; это делает тестовый adapter настоящим fenced reference без раскрытия internal lease constructor.
- Добавлены `InMemoryProjectionHoldStore` и deterministic protocol tests: checksum collision, capacity refusal,
  stale lease fencing, live queue behind redrive и two-step operator hole acknowledgement.
- Добавлены PostgreSQL proofs для redrive savepoint/attempt/release и durable same-unit capacity halt.
- Обновлены `docs/modules/event.md`, `docs/modules/event-test.md` и новый
  `docs/runbooks/projection-park-redrive.md`.

## Durable topology/split continuation

- Добавлены public topology declaration/store contracts, durable live/retired member lineage и bounded split
  blockers. Topology declaration связывает checked cover с contract fingerprint до первого SQL statement.
- V7 создаёт `projection_topology`, members и retirement records. Generation topology fingerprint остаётся
  immutable для обычных updates; его может изменить только transaction-local split protocol.
- `PostgresProjectionTopologyStore` не открывает transaction: он в caller-owned checkpoint/control transaction
  берёт generation/topology/parent locks, отказывает при live lease, hold, hole или halt, создаёт оба child
  checkpoint с exact settled cursor, fenced-retire/delete parent и меняет durable cover/generation fingerprint
  одним commit.
- `PostgresProjectionCheckpointStore` допускает только live durable member с matching topology contract. Старый
  parent получает `ProjectionClaim.Retired`; `AFTER_APPLY` и `SAME_UNIT` возвращают typed `Retired` без вызова
  handler. Повтор старой pre-split команды является `ContractDrift`, поскольку fingerprint поколения уже изменён.
- PostgreSQL integration proofs покрывают first и consecutive split с inherited cursor, refusal старого runner,
  live checkpoint lease и parked sequence blocker. Добавлен `docs/runbooks/projection-topology-split.md`.

## Projection worker scheduling continuation

- Добавлен explicit `ProjectionPassWorker`: JSON-safe request содержит lane и SHA-256 observed checkpoint digest;
  stale request не вызывает handler, а повторно ставит fresh durable pass. Он адаптирует `AFTER_APPLY` и `SAME_UNIT`
  runners, не создаёт private projection transaction/retry loop и превращает missing runner в permanent jobs refusal.
- Active generation выполняет максимум declared page budget одного job attempt. `BUILDING` всегда выполняет одну
  страницу и ставит successor только после `checkpoint.updated_at + rebuildReadPace`; pace — IO throttle, не замена
  checkpoint lease или worker concurrency.
- Successor использует `Dedupe.Collapse`: `Unique` удерживается до terminal jobs state и поэтому поглотил бы
  successor, созданный ещё во время active handler. Checkpoint lease остаётся единственной concurrency authority;
  collapse только coalesces queued kick.
- `ProjectionPassSweep` — bounded cluster recurring recovery с keyset cursor. `DurableProjectionPassLaneSource`
  читает только live members registered durable topology и bounded `BUILDING`/`ACTIVE` generations; V8 добавляет
  partial index для этого query.
- Optional `ProjectionPassHintPublisher`/consumer передают только lane identity через `rain-realtime`; malformed,
  duplicate или lost hint не влияют на correctness и всегда fallback'ятся на sweep/polling.
- Добавлены deterministic tests stale digest, active page bound, rebuild pace, hint и cyclic bounded sweep, а также
  PostgreSQL proof runnable generation query (`BUILDING` + `ACTIVE`, order и limit).

## Начало reusable conformance continuation

- `rain-event-test` теперь экспортирует bounded `ProjectionCheckpointConformance`: stable section report различает
  `PASSED`, `FAILED` и `NOT_CERTIFIED`, а `requireCertified()` не превращает unsupported contract в ложный успех.
  Проверяются lease exclusivity, stale fenced advance/release, contract drift и monotonic cursor.
- Один launcher запускается против deterministic `InMemoryProjectionCheckpointStore` и PostgreSQL adapter в отдельном
  integration test. Public `UnfencedProjectionCheckpointStore` — намеренно broken fixture: тест доказывает, что
  launcher отмечает только `projection.checkpoint.fenced-advance`, поэтому suite является defect-sensitive, а не
  набором self-fulfilling green checks.
- Добавлен отдельный `ProjectionHoldConformance` для ParkSequence/redrive state machine: live append behind active
  redrive, redrive fence, head-only acknowledgement, capacity refusal и two-step operator hole. Target сам задаёт
  caller-owned transaction boundary; один launcher green против memory и PostgreSQL. Он намеренно **не** объявляет
  SAME_UNIT destination authority certified — это свойство полного runner integration. Deliberately broken
  `UnfencedProjectionHoldStore` пропускает stale acknowledgement через replacement lease и падает ровно в
  `projection.hold.redrive-fence`.
- Добавлен PostgreSQL SAME_UNIT crash-window proof для первого, middle и last destination write в одном page. Любое
  падение откатывает все writes и ещё не созданный checkpoint; только внешний новый attempt получает исходный полный
  page, и handler вызывается один раз на каждый pass (без retry внутри `Unit`).
- Добавлен two-transaction PostgreSQL effect-gate race proof без sleep: active generation получает `ALLOWED` и
  записывает staged effect под locking read; concurrent cutover обязан ждать этого commit. После cutover effect
  остается committed, active pointer указывает на новую generation, а retired generation получает
  `INACTIVE_GENERATION`.

## Проверки

- Passed: `./gradlew :rain-event:integrationTest --tests '*same-unit park rolls back*'`.
- Passed: `./gradlew :rain-event:integrationTest --tests '*same-unit redrive rolls back*'`.
- Passed: `./gradlew :rain-event:integrationTest --tests '*PostgresEventStoreIT'`.
- Passed: `./gradlew :rain-event:integrationTest --tests '*park queue capacity*' :rain-event-test:test`.
- Passed topology focused integration: `./gradlew :rain-event:integrationTest --tests '*PostgresProjectionTopologyIT'`.
- Passed final topology scope gate:

  ```bash
  ./gradlew :rain-event:check :rain-event-test:check verifyModuleGraph verifyDocsCoverage
  ```

- Passed final scheduler scope gate:

  ```bash
  ./gradlew :rain-event:check :rain-event-test:check verifyModuleGraph verifyDocsCoverage
  ```

- Passed focused conformance tests (с исключением чужой broken `:rain-persistence:compileKotlin` task):

  ```bash
  ./gradlew :rain-event-test:test --tests '*ProjectionHoldConformanceTest' -x :rain-persistence:compileKotlin
  ./gradlew :rain-event:integrationTest --tests '*PostgresProjectionHoldConformanceIT' -x :rain-persistence:compileKotlin
  ./gradlew :rain-event:integrationTest --tests '*PostgresSameUnitProjectionCrashIT' -x :rain-persistence:compileKotlin
  ./gradlew :rain-event:integrationTest --tests '*PostgresProjectionEffectGateRaceIT' -x :rain-persistence:compileKotlin
  ```

- Full gate после conformance additions пока не green: несвязанная dirty правка
  `rain-persistence/.../DataAccessFaultTranslator.kt` использует inferred Checker Framework `@Nullable` на line 94,
  однако соответствующая annotation отсутствует в compile classpath. Не менять этот чужой production code в рамках
  event continuation; после её завершения повторить полный gate.

## Текущая точка и порядок продолжения

1. Продолжить reusable projection conformance: checkpoint и hold/redrive state-machine subsets уже certification
   against memory/PostgreSQL с defect implementations. SAME_UNIT atomicity и effect-gate race имеют PostgreSQL proofs,
   но ещё нет reusable launcher/defect sections для authority, topology, generation/effect и event-store. Текущая
   certification не является сертификатом PostgreSQL SAME_UNIT destination authority.
2. Продолжить Phase 6 generation/rebuild crash-window tests и затем Phase 7 tenancy-event composition. Не делать
   cross-product модулей: event/jobs/i18n/tenancy composition остаётся отдельными линейными пакетами.

## Важные границы

- `PARK_SEQUENCE` разрешён только SAME_UNIT; `AFTER_APPLY` сохраняет at-least-once semantics.
- Failed handler writes откатываются к savepoint до создания hold. Hold creation и checkpoint advance
  остаются в одном caller transaction.
- `projection_hole` не является automatic retry escape hatch: skip оставляет immutable hole, и
  отдельное acknowledgement требуется перед cutover.
- Не трогать широкие существующие изменения worktree — они принадлежат пользователю. Рабочая область
  уже была dirty до этих изменений.
