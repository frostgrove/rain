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

## Проверки

- Passed: `./gradlew :rain-event:integrationTest --tests '*same-unit park rolls back*'`.
- Passed: `./gradlew :rain-event:integrationTest --tests '*same-unit redrive rolls back*'`.
- Passed: `./gradlew :rain-event:integrationTest --tests '*PostgresEventStoreIT'`.
- Passed: `./gradlew :rain-event:integrationTest --tests '*park queue capacity*' :rain-event-test:test`.
- Passed final scope gate:

  ```bash
  ./gradlew :rain-event:check :rain-event-test:check verifyModuleGraph verifyDocsCoverage
  ```

## Текущая точка и порядок продолжения

1. Реализовать durable live topology/split полностью: immutable exact-cover members, parent retirement,
   children with inherited cursor in one transaction, old parent refusal и runner admission only for live member.
   Учесть contract/generation topology fingerprint без ослабления existing drift/cutover invariants.
2. Добавить worker scheduling through `rain-jobs`, rebuild read pacing и optional realtime hint. Никаких
   private transaction/retry loops в projection adapters; polling остаётся correctness source.
3. Построить настоящий reusable projection conformance launcher и defect implementations; текущие memory tests
   являются reference protocol tests, не сертификатом PostgreSQL SAME_UNIT authority.
4. Продолжить Phase 6 generation/rebuild crash-window tests и затем Phase 7 tenancy-event composition. Не делать
   cross-product модулей: event/jobs/i18n/tenancy composition остаётся отдельными линейными пакетами.

## Важные границы

- `PARK_SEQUENCE` разрешён только SAME_UNIT; `AFTER_APPLY` сохраняет at-least-once semantics.
- Failed handler writes откатываются к savepoint до создания hold. Hold creation и checkpoint advance
  остаются в одном caller transaction.
- `projection_hole` не является automatic retry escape hatch: skip оставляет immutable hole, и
  отдельное acknowledgement требуется перед cutover.
- Не трогать широкие существующие изменения worktree — они принадлежат пользователю. Рабочая область
  уже была dirty до этих изменений.
