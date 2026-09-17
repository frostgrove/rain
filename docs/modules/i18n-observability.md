# rain-i18n-observability

Optional pairwise `i18n ↔ observability` bridge. It turns the kernel's already bounded
`I18nObservation` values into one Micrometer counter; the catalog kernel, web bridge, jobs bridge
and tenancy bridge do not acquire a metrics dependency.

```kotlin
dependencies {
    implementation("com.gd.rain:rain-i18n-observability")
}
```

When a `MeterRegistry` is present, auto-configuration supplies `I18nMicrometerObserver` unless the
application declares its own `I18nObserver`. `rain-i18n-web` and `rain-i18n-jobs` use that observer
in their default magic paths. A custom web view factory, a manual jobs delivery view, or a tenant
runtime task remains low-level and explicit: pass the observer in `ViewSpec` or replace it with a
product-specific sink.

The counter is `rain.i18n.operations` and has exactly four finite labels:

| Label | Values |
|---|---|
| `operation` | `locale_resolution`, `render` |
| `outcome` | `resolved`, `rendered`, `refused`, `failed` |
| `locale_reason` | closed `LocaleResolutionReason` values or `none` |
| `layer` | closed `RenderLayer` values or `none` |

Neither metric labels nor metric names contain message keys, rendered text, values, arbitrary locale
spelling, snapshot digest, tenant, request or principal. Build a dashboard from refusal/failure rate
and render/layer mix; alert on a sustained non-zero `failed` render rate, an unexpected increase in
`refused` locale resolutions, or a fallback/layer distribution that violates the product's locale
SLO. Thresholds and paging ownership are deployment policy, not framework defaults.

This is deliberately one bridge. A job that emits an event and renders an i18n message uses its
separate `jobs ↔ i18n`, `jobs ↔ event` and `i18n ↔ observability` contracts; it never gains a
three-way integration module or a shared ambient context.
