# rain-event-test

Test-only support for `rain-event`: deterministic stores, aggregate fixtures, and conformance helpers.

## Dependency

```kotlin
dependencies {
    testImplementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    testImplementation("com.gd.rain:rain-event-test")
}
```

`InMemoryEventStore` is an explicit test fixture, never a production fallback. Its virtual transaction is
copy-on-write: a callback that throws commits no events. It preserves dense stream versions, confirmed conflicts,
unit-bound tokens, and the same claim/complete/repeat receipt semantics as the PostgreSQL adapter, so kernel tests
cannot accidentally validate a weaker model than production.

It also implements the optional snapshot and committed-log capabilities. `InMemoryProjectionCheckpointStore` models
fenced checkpoint leases for deterministic `AFTER_APPLY` crash/retry tests; it is test infrastructure, not a durable
worker implementation.

`EventAggregateFixture` accepts an explicit aggregate/catalogue, id, clock and seeded id source. `given` writes only
declared facts; `whenDecide` invokes the supplied lambda exactly once and returns its exact emitted facts, metadata and
commit range, or a closed failure. It deliberately has no command annotations, handler discovery, reflection, retries,
or production auto-configuration.
