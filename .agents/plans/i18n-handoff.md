# Handoff: i18n implementation

Дата: 2026-09-17  
Статус: реализация функционально завершена; финальная полная Gradle-проверка была запущена, но
текущий агент был остановлен до получения её результата. Не утверждать, что она зелёная, пока её
не перезапустят и не увидят `BUILD SUCCESSFUL`.

Основной план: [i18n.md](i18n.md). Все шесть фаз в нём отмечены `[x]`; верхний статус намеренно
говорит «финальная полная проверка выполняется» до подтверждения последнего release gate.

## Что уже сделано

- Реализован полный optional i18n bounded context: kernel, test fixtures, tool/K2 extraction and
  generated bindings, web bridge, persistence/release store, durable jobs, tenancy adapter и
  vendor-neutral integration SPI. Полный scope и все отмеченные фазы описаны в `i18n.md`.
- Зафиксирована линейная композиция (`I16`): bridge всегда между ровно двумя bounded contexts;
  никаких `rain-jobs-i18n-events`, `tenancy-i18n-jobs` или ambient N-way context.
- Добавлены ICU 78.3 exact golden tests для `en-US`/`ru`/`kk`: числа, суммы, ranges, plurals,
  даты/время, lists, relative/duration, display names, rich parts и bidi; pseudo проверки включают
  accent и RTL.
- Добавлен reference sample `GET /v1/i18n/preview`, который демонстрирует reviewed rich en/ru/kk,
  `Accept-Language`, `Content-Language`, bidi parts и request-scoped magic `I18nRuntime`, сохраняя
  explicit typed low-level `MessageDefinition` как escape hatch.
- Исправлен реальный web bug: `I18nRuntime.view` и `render` сделаны `open`, чтобы Spring request
  scope CGLIB proxy не получал `context=null`.
- Добавлен отдельный `rain-i18n-observability` — именно pairwise `i18n ↔ observability` bridge:
  `I18nMicrometerObserver` пишет один bounded counter `rain.i18n.operations`, а web/jobs magic
  paths подхватывают заменяемый `I18nObserver`. Kernel/web/jobs не получили metrics dependency.
- Добавлены документы:
  - `docs/modules/i18n-observability.md` — метрика, labels, alert/dashboard policy и low-level override;
  - `docs/concepts/i18n-operations.md` — author/review, release/rollback, overlays, pins, HTTP,
    cursor repair и capacity rehearsal;
  - обновлены README, i18n module docs и sample README.
- «10M» не реализован как ложная mutable in-memory цель: `I18nLimits` жёстко ограничивает source/
  artifact 256 MiB и catalog 1M сообщений; runbook описывает production-limit rehearsal и требует
  явно разделять независимые release domains для большего реального объёма.

## Последние изменённые зоны

- Новый module: `rain-i18n-observability/`.
- Build graph: `settings.gradle.kts`, root `build.gradle.kts`, `README.md`.
- Observer plumbing:
  - `rain-i18n-web/.../ServletI18n.kt`;
  - `rain-i18n-web/.../autoconfigure/RainI18nWebAutoConfiguration.kt`;
  - `rain-i18n-jobs/.../I18nDelivery.kt`;
  - `rain-i18n-jobs/.../autoconfigure/RainI18nJobsAutoConfiguration.kt`.
- New/expanded tests:
  - `rain-i18n-observability/.../I18nMicrometerObserverTest.kt`;
  - `rain-i18n-web/.../I18nRequestFilterTest.kt`;
  - `rain-i18n-jobs/.../I18nDeliveryViewsTest.kt`.

## Проверки, уже подтверждённые до остановки

Успешны:

```text
./gradlew :rain-i18n-observability:spotlessApply :rain-i18n-observability:check --no-daemon --console=plain
./gradlew :rain-i18n-observability:check :rain-i18n-web:check :rain-i18n-jobs:check --no-daemon --console=plain
```

Ранее в этой сессии также проходили focused golden/tool/web/sample integration проверки, в том
числе Docker-backed `I18nPreviewIT`.

## Что сейчас делать следующему агенту

1. Не перетирать dirty worktree: в репозитории есть много чужих/предшествующих незакоммиченных
   изменений и целые i18n directories отображаются как untracked.
2. Сначала проверить, что после остановки нет оставшегося Gradle процесса. Затем заново выполнить:

```text
./gradlew :rain-i18n:check :rain-i18n-test:check :rain-i18n-tool:check \
  :rain-i18n-observability:check :rain-i18n-web:check :rain-i18n-persistence:check \
  :rain-i18n-jobs:check :rain-i18n-integration:check :rain-tenancy-i18n:check \
  :samples:rain-sample:check --no-daemon --console=plain
```

3. Если он зелёный, запустить `git diff --check`, сменить верхний статус `i18n.md` с
   «финальная полная проверка выполняется» на «реализован; full check green» и только тогда
   считать release gate закрытым.
4. Если он падает, исправлять только failure, относящийся к i18n/sample или новому bridge; не
   откатывать посторонние dirty изменения.

## Важные архитектурные решения

- Magic first: Spring/Web/Jobs получают normal default beans; все они replaceable. Любая сложная
  ситуация может использовать `CatalogSnapshot`, `ViewSpec`, `I18nObserver`, release store,
  `MessageDefinition` и tool CLI напрямую.
- Новый metrics bridge не зависит от web/jobs/tenancy. Web и jobs зависят только от core
  `I18nObserver`; поэтому приложение может подключить observer без тройного module dependency.
- Метрика не содержит message keys, text, values, locale strings, snapshot/tenant/principal IDs.
  Только `operation`, `outcome`, `locale_reason`, `layer` из закрытых enum/`none`.
