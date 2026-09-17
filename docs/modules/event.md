# rain-event

The optional explicit event kernel. Applications declare aggregate families, fact wire names, readable/write revisions,
and fold functions; the module does not scan annotations or run command handlers.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-event")
}
```

## Core guarantees

`EventRepository` returns either `StreamLoad.Missing(CreateToken)` or `StreamLoad.Existing(state, AppendToken)`.
A historical state has no append token. Tokens are opaque, stream/backing/unit bound, and `append` never retries a
decision after a conflict. `EventStore` accepts only finite pages and an explicit caller transaction callback.

`EventCatalogue` validates unique stable aggregate/fact names and that every write revision is readable. `EventMetadata`,
payloads, keys, batches, and pages have hard byte/count limits; deployment configuration may narrow them.

`EventOperationReceipts` is an explicit optional capability of a store. A service claims an
`OperationKey` plus immutable `RequestFingerprint`, runs its decision only on `ReceiptClaim.Claimed`, appends, then
completes the same transaction with a verified range, append fingerprint and optional bounded response. A repeated
claim returns the original `CompletedReceipt`; a different request is a closed collision. Receipt APIs do not create a
command bus or retry a decision.

The PostgreSQL store, snapshots, projections, and operational wiring are optional adapters. An application that does
not activate event storage has no automatic event behaviour.

## Committed log and projections

`CommittedEventLog` is a separate read-only capability over the global immutable log. Its cursor is bound to the
durable schema log id, not a process-local data-source identity, so a checkpoint survives application restart while a
foreign log remains refused. The PostgreSQL reader captures an xmin settlement boundary and returns only records whose
writer transaction was already terminal before that boundary. Thus a later-committed record cannot make a reader skip
an earlier identity position still held by a long transaction; rollback gaps are harmless. An empty read still returns
an advanced settlement cursor for a projection checkpoint to persist.

`ProjectionSpec` has an application-declared contract revision, destination id, fact routes, sequence-key hasher, and
checked `ProjectionCover`. A cover proves exact ownership of the full hash space; topology changes only by replacing
one parent partition with its two children. It never remaps a live projection through `% N`. `ProjectionCatalogue`
validates every owned aggregate fact has an explicit `Handle` or `Ignore(why)` route at startup.

`AfterApplyProjectionRunner` is deliberately at-least-once: it obtains a durable fenced checkpoint lease, invokes a
read-only `ProjectionHandler`, then CAS-advances the cursor. A failure or lost lease after application causes delivery
again, so an external destination must be idempotent by position or stream/version. `PARK_SEQUENCE` and durable staged
effects are refused in this mode; they require the separately proven SAME_UNIT protocol. PostgreSQL checkpoints retain
the contract revision, topology fingerprint, log id, cursor settlement evidence and lease fence, so an incompatible
deployment cannot resume an old cursor silently.

`SameUnitProjectionRunner` takes that stronger path only with a caller-owned destination transaction. Before the
handler runs it proves that the destination and checkpoint adapters expose the same `BackingIdentity` and active
`TransactionAuthority`; it then claims the lane, invokes the handler once, and advances the checkpoint in that same
transaction. It does not start a transaction or retry a handler. Any exception escapes the unit, so handler writes and
checkpoint claim roll back together rather than committing attempt bookkeeping after a failed projection.

`PARK_SEQUENCE` is available only through that SAME_UNIT path. It requires a per-envelope handler and destination
savepoint: a classified permanent failure rolls back only that envelope's destination writes, durably appends its
opaque causal sequence to `projection_hold`/`projection_letter`, then advances the live checkpoint in the same caller
transaction. Later envelopes of the held sequence are queued without delivery; other sequences continue. Both queued
letter count and bytes are bounded. Reaching either bound durably halts the lane rather than discarding an envelope.

`ProjectionRedriveRunner` processes exactly one held queue head per caller-owned SAME_UNIT transaction. It proves the
destination and hold-store authority match, claims a distinct fenced redrive lease, applies the head behind a
savepoint, and acknowledges it only after that destination write succeeds. A failed handler rolls back to the
savepoint and records a bounded stable failure code and attempt count while retaining the head. If more letters remain
after success, the runner releases the lease so the next bounded pass can claim the next head; a stale lease raises and
rolls back destination work instead of reporting success. It has no transaction or retry loop. The operational decision
to skip a sequence writes an immutable `ProjectionHole`, and a separate acknowledgement is required before a generation
with that omission can become ready or cut over. See the [park/redrive runbook](../runbooks/projection-park-redrive.md).

Rebuild generations are registered immutably with a source cursor, barrier cursor, contract revision and topology
fingerprint. `PostgresProjectionGenerationStore` derives `READY` from every member of the checked cover reaching that
barrier with the matching checkpoint contract; it does not accept a boolean readiness claim. Cutover/rollback is a
compare-and-set over the durable active-generation pointer. A `STAGED_DURABLE` generation persists an immutable
`effect_after_position` equal to its barrier, so replay at or before the barrier cannot later be enabled for effects.
`ProjectionEffectGate` takes a locking read of that active pointer inside the caller's transaction: it admits staging
only for the active generation and positions strictly after the barrier. Jobs/outbox adapters consume an already
admitted durable effect; they do not decide whether a historical generation may emit one.

`ProjectionWaiter` is an asynchronous bounded read-your-writes convenience. It compares the requested `ProjectionMark`
with durable checkpoints of **every** member in the active checked cover and returns `Reached`, `TimedOut`,
`GenerationUnavailable`, or `ContractDrift`. It owns no transaction, connection or lease while waiting. A caller can
use its `recheck()` handle after an optional realtime hint; periodic durable polling still completes correctly if that
hint is lost.

## Snapshots

Snapshots are a disposable replay optimisation, never an authority for aggregate truth. `SnapshotSpec` declares the
codec identity, domain-separated codec fingerprint, readable snapshot version, and encode/decode functions for one
aggregate state. Supplying it to `EventRepository` enables loading from the latest compatible snapshot at or before
the requested event version, then folding the remaining immutable events.

`EventRepository.snapshot(transaction, namespace, id)` always rebuilds the state from the event source before writing;
it does not compound a previous snapshot. `EventSnapshotStore` is transaction-bound like `EventStore`: a snapshot is
accepted only when its source event version exists in that exact stream and transaction. A repeated identical write is
idempotent, whereas a different payload, codec, or fingerprint for the same stream version is a closed collision.

Unreadable, corrupt, fingerprint-mismatched, incompatible-version, or decoder-failing snapshots are ignored with a
bounded `SnapshotObserver` reason and replay resumes from the initial state. Callers can prune only a bounded number
of older snapshots while retaining a chosen minimum; pruning never deletes source events. `PostgresEventStore` and the
separately published `rain-event-test` in-memory reference store both implement this optional capability.

`PostgresEventStore` normally inspects a caller's Spring transaction manager. Its `EventTransactionPlacement` SPI is
the neutral alternative for a composition module that already proved a current `BackingIdentity` and
`TransactionAuthority`; it does not depend on tenancy or any other bounded context.
