# Handoff: tenancy-and-eventsource-2

Статус на 2026-09-17: работа остановлена по запросу пользователя. Основной план находится в
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

## Проверки

- После базовых контрактов проходила компиляция:
  `./gradlew :rain-event:compileKotlin :rain-event-test:compileKotlin :rain-event:compileTestKotlin :rain-event-test:compileTestKotlin`.
- Проходил focused PostgreSQL integration test low-level hold/redrive/hole:
  `./gradlew :rain-event:integrationTest --tests '*projection parking queues one causal sequence*'`.
- Добавлен integration test `same-unit park rolls back only the failed envelope, queues its sequence,
  and advances unrelated work`. Его финальный запуск был прерван пользовательским `stop`, поэтому
  его результат **не считать подтверждённым**. Перед любой следующей работой выполнить:

  ```bash
  ./gradlew :rain-event:integrationTest --tests '*same-unit park rolls back*'
  ```

## Текущая точка и порядок продолжения

1. Получить явный результат последнего SAME_UNIT parking test; исправить только подтверждённые
   ошибки. Затем прогнать весь `PostgresEventStoreIT` и обычные `:rain-event:check` / `:rain-event-test:check`.
2. Добавить dedicated `ProjectionRedriveRunner`: он должен применять head letter в caller-owned
   SAME_UNIT transaction, ack only after destination write, и при failure записывать attempts/code
   без нарушения order. Не начинать новый transaction внутри adapter.
3. Добавить in-memory hold/redrive reference в `rain-event-test` и conformance tests (including
   lease race, checksum collision, capacity halt, redrive/live queue race, hole acknowledgement).
4. Обновить `docs/modules/event.md`, `docs/modules/event-test.md` и runbook park/redrive.
5. Продолжить оставшиеся части Phase 6: durable live topology/split, rebuild read pacing/jobs
   integration, realtime hint, projection conformance/defect suite. Не делать cross-product
   модулей: event/jobs/i18n/tenancy composition остаётся отдельными линейными пакетами.

## Важные границы

- `PARK_SEQUENCE` разрешён только SAME_UNIT; `AFTER_APPLY` сохраняет at-least-once semantics.
- Failed handler writes откатываются к savepoint до создания hold. Hold creation и checkpoint advance
  остаются в одном caller transaction.
- `projection_hole` не является automatic retry escape hatch: skip оставляет immutable hole, и
  отдельное acknowledgement требуется перед cutover.
- Не трогать широкие существующие изменения worktree — они принадлежат пользователю. Рабочая область
  уже была dirty до этих изменений.
