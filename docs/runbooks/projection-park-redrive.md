# Projection ParkSequence and redrive runbook

This runbook is for a projection deliberately declared with `PARK_SEQUENCE`. It does not turn an
at-least-once destination into exactly-once delivery, retry an application handler automatically, or make a parked
sequence safe to ignore. The protocol is available only to `SAME_UNIT` projections whose destination, checkpoint and
hold adapters prove one active transaction authority.

## Triage

Read the bounded projection hold status for the affected lane. It exposes projection, generation, partition, first
position, stable failure code, state, count/byte totals and redacted sequence digest. It intentionally does not expose
stream keys, payloads, exception messages or tenant identifiers. Inspect the destination and source event through the
application's authorised operational tools; do not add raw envelope bodies to a metric, log or status endpoint.

`HELD` means a sequence may be claimed by redrive. `REDRIVING` means a fenced lease is active; another worker must
return its retry time rather than run the handler concurrently. A capacity failure halts the lane: do not repeatedly
kick the live worker, because it cannot safely advance past the retained causal queue.

## Retry

Fix the declared destination or data condition first, then schedule a bounded redrive pass for the exact lane. One
`ProjectionRedriveRunner` pass claims one sequence and applies only its queue head in the caller's existing SAME_UNIT
transaction. On success it writes the destination and removes that head atomically. If letters remain, it releases its
lease and a later pass claims the next head; it never drains an unbounded queue in one unit.

On failure, handler writes roll back to the savepoint, the queue head remains first, and its attempt count and stable
failure code are recorded. Transient classification uses the configured stable redrive-transient code; a classified
permanent code remains visible for an operator decision. A lease loss after a destination write is an exception that
rolls back the whole caller transaction, not a successful redrive outcome.

Do not manually delete `projection_hold` or `projection_letter`, edit a checkpoint cursor, or acknowledge a later
letter first. Those actions can violate causal order and make a generation appear complete when it has omitted data.

## Intentional omission

Use the hold-store's operator-only eviction operation only when the product has accepted that this projection will omit
the retained sequence range. It first writes an immutable `ProjectionHole` with the operator and bounded reason, then
releases the sequence; it is not a retry escape hatch. Record the incident, affected projection generation, redacted
sequence digest and rationale in the application's audit trail.

Acknowledging the hole is a second, explicit rebuild/cutover policy decision. It does not undo the omission and it does
not recreate destination data. Until that acknowledgement, generation readiness and cutover remain blocked; unresolved
holds, unacknowledged holes and halted lanes must all be clear before promotion.

## Recovery evidence

For an incident record, retain the lane identity, first/last affected positions, stable failure code history, hold count
and bytes before/after redrive, operator decisions, and the transaction-level verification that the destination write
and acknowledgement committed together. Alert on held age, repeated head attempts, capacity stops and holes. Use
low-cardinality projection/generation/partition dimensions only; sequence digests and event payloads are diagnostic data,
not metric labels.
