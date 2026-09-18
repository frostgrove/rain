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

`InMemoryProjectionHoldStore` is the corresponding deterministic parking protocol reference. It models checkpoint and
redrive fences, checksum-verified immutable letters, bounded capacity refusal, live enqueue behind a held/redriving
sequence, head-only acknowledgement, attempt diagnostics and explicit operator holes. Its transaction placement has no
`TransactionAuthority`, deliberately: it certifies parking state-machine tests, not the PostgreSQL SAME_UNIT atomic
destination protocol. Use `PostgresProjectionHoldStore`, `PostgresSameUnitProjectionDestination`, and an integration
test to prove a real destination write, queue mutation and transaction commit/rollback are one database unit.

`EventAggregateFixture` accepts an explicit aggregate/catalogue, id, clock and seeded id source. `given` writes only
declared facts; `whenDecide` invokes the supplied lambda exactly once and returns its exact emitted facts, metadata and
commit range, or a closed failure. It deliberately has no command annotations, handler discovery, reflection, retries,
or production auto-configuration.

`ProjectionCheckpointConformance` is the reusable, bounded certification launcher for a checkpoint-store adapter. It
reports each stable protocol section as `PASSED`, `FAILED`, or `NOT_CERTIFIED`; `requireCertified()` rejects both a
failure and an unsupported section. The in-memory store is the deterministic protocol reference, while
`PostgresProjectionCheckpointConformanceIT` runs the same launcher against the PostgreSQL adapter. The public
`UnfencedProjectionCheckpointStore` is deliberately defective test-only evidence: it proves the launcher detects a
stale lease advancing a checkpoint rather than merely reporting a green reference implementation.

`ProjectionHoldConformance` applies the same reporting contract to the ParkSequence/redrive state machine: live
append behind an active redrive, fenced ownership, head-only acknowledgement, capacity refusal, and the two-step
operator-hole policy. A target supplies its caller-owned transaction boundary, so the exact same launcher runs against
memory and PostgreSQL. It deliberately does not certify a SAME_UNIT destination's transaction authority; that remains
an integration proof of the complete runner. `UnfencedProjectionHoldStore` is a defect fixture that lets a stale
redrive acknowledgement operate through a replacement lease and must fail only the redrive-fence section.
