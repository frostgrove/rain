# Formal Invariants & Concepts from `vv/framework` (Golang)

**Source Reference:** `../../vv/framework` (`event`, `event/eventpg`, `event/projection`, `event/receipt`, `tenancy`, `tenancy/tenancyrow`, `tenancy/tenancyjobs`, `tenancy/tenancydb`)  
**Purpose:** Extract load-bearing architectural contracts, mathematical invariants, database triggers, and proven algorithms from the Go reference framework and translate them into the Kotlin/Rain architecture.

---

## 1. Architectural Philosophy of `vv/framework`

The `vv` framework represents a rigorous, formally verified approach to backend enterprise software in Go. Its architecture is built on six foundational axioms:
1. **Decisions Come From Declared Rules:** No guesses, no similarity thresholds, no heuristics. Input that is insufficient or invalid produces an explicit typed refusal (`Fault`), never a silent fallback.
2. **Design for Ten Million Rows:** No extensible table is ever scanned sequentially. Every query is bounded by an index scan, keyset pagination, and explicit `LIMIT`. No `COUNT(*)` totals are ever queried.
3. **Unforgeable Security Scope:** Security credentials and tenant scopes cannot be constructed or forged by application code. They can only be minted by a verified `Authority`.
4. **Append-Only History by Database Invariant:** Event history is immutable not because application code promises not to mutate it, but because the PostgreSQL engine physically rejects `UPDATE`, `DELETE`, and `TRUNCATE` via statement triggers.
5. **Causal Quarantine over Head-of-Line Blocking:** A poisoned event in a projection must never cause an entire subscription to halt, nor may it be skipped to corrupt downstream read models. Instead, the specific causal stream is parked (`Park`), while unrelated streams continue uninterrupted.
6. **Zero-Downtime Rebuilds with Blue-Green Generations:** A projection rebuild never wipes or resets a live read model. It builds a parallel generation (`orders@2`) and executes an atomic cutover once caught up.

---

## 2. Multi-Tenancy Invariants (`vv/framework/tenancy`)

### 2.1 The Unforgeable Scope & Authority
In `vv`, application code cannot instantiate a `Scope`:
```go
// From vv/framework/tenancy/authority.go
type Resolver interface {
    Resolve(ctx context.Context) (Resolution, error)
    Lookup(ctx context.Context, reference Reference) (Resolution, error)
}

type Resolution struct {
    Reference Reference
    Lifecycle Lifecycle
    Epoch     Epoch
}
```
- `Resolve` returns raw data (`Resolution`), never a `Scope`.
- `Scope` has an unexported constructor: only `Authority.Bind(ctx, class)` can mint a verified scope.
- `Scope` carries:
  - `Reference`: Opaque tenant identifier.
  - `Lifecycle`: Current operational state (`Active`, `Suspended`, `Deleting`, etc.).
  - `Epoch`: Generation counter incremented on significant tenant lifecycle events.
  - `Origin`: Cryptographic environment fingerprint (`Spec.Origin`), ensuring a token minted in development or staging cannot be presented to production.

### 2.2 Lifecycle Admission Whitelist per Operation Class
Tenancy operations are classified into three orthogonal classes:
```go
// From vv/framework/tenancy/lifecycle.go
type Class uint8
const (
    ClassRead Class = iota
    ClassWrite
    ClassDurable
)
```
- A deployment declares its admission policy at startup:
  ```go
  tenancy.Spec{
      Resolver: controlPlane,
      Admission: tenancy.Admit(tenancy.ClassRead, tenancy.Active, tenancy.Suspended).
          Merge(tenancy.Admit(tenancy.ClassWrite, tenancy.Active)).
          Merge(tenancy.Admit(tenancy.ClassDurable, tenancy.Active)),
  }
  ```
- **The Consistency Invariant:**
  A state admitted for `ClassWrite` MUST also be admitted for `ClassRead`. Any mutation verb resolves its narrowing predicate for the read class first; admitting write without read is an inconsistent policy that is rejected fail-closed at startup.
- `ClassDurable` (background jobs) is exempt from this floor: a tenant undergoing migration can be permitted to run durable maintenance tasks while both user-facing reads and writes are blocked.

### 2.3 Mid-Request Revalidation (`Revalidate`)
By default, a scope is verified at `Bind` (request entry). However, for high-security environments where immediate suspension/deletion is mandatory:
```go
tenancy.Spec{Resolver: controlPlane, Revalidate: true}
```
When `Revalidate = true`, every database statement or domain action re-checks the current tenant lifecycle and generation against the control plane cache, throwing `ErrStale` or `ErrInactive` immediately if the tenant was suspended mid-request.

### 2.4 Row-Level Ownership & Query Narrowing (`tenancyrow`)
`vv` rejects magic ORM filters in favor of explicit, provable ownership strategies:
```go
// From vv/framework/tenancy/tenancyrow/row.go
type Ownership[M any] interface {
    Narrow(scope Scope) (crud.Predicate, error)
    Relations() []Relation
    NarrowsRelations() bool
    Apply(scope Scope, model *M) error
    Frozen() []string
}
```
1. **Direct Column Ownership (`Column[M]`):**
   - Narrows via `tenant_id = ?`.
   - Creation modes:
     - `Derive`: Automatically stamps `tenant_id` if empty; rejects foreign owner.
     - `Validate`: Requires the caller to have supplied the exact matching tenant ID.
   - Column freezing: The ownership column cannot be modified in an update.
2. **Relation Ownership (`Through[M]`):**
   - For child tables that do not have their own `tenant_id` column, ownership is verified through a **to-one** relation to a parent table.
   - Compiles to a correlated `EXISTS` subquery:
     ```sql
     WHERE EXISTS (
         SELECT 1 FROM parent_table p 
          WHERE p.id = child_table.parent_id AND p.tenant_id = ?
     )
     ```
   - **The To-One Invariant:** A to-many path is strictly refused at wiring! A to-many path compiles to "at least one child is mine", meaning a shared parent would answer to multiple tenants simultaneously, creating a critical security leak.
3. **Declared Preload Relations:**
   - In ORMs, a preload/relation fetch executes as a secondary statement (`SELECT * FROM child WHERE parent_id IN (...)`).
   - The root query's `tenant_id = ?` does NOT reach the child table automatically!
   - `tenancyrow` mandates that every tenant-owned relation is explicitly declared: `Relation{Path: "Lines", Field: "TenantID"}`. Undeclared relations are rejected during query construction.

### 2.5 HMAC-Sealed Durable Job Identities (`tenancyjobs`)
When an asynchronous job is enqueued, passing a bare tenant ID string is insecure because any process with queue access could forge jobs for another tenant.
- `vv` uses cryptographic HMAC sealing:
  ```go
  // From vv/framework/tenancy/seal.go
  type Sealer interface {
      Seal(scope Scope, queue, definition string) (Token, error)
      Unseal(token Token, queue, definition string) (Reference, Epoch, error)
  }
  ```
- **The Sealed Payload Invariant:**
  The HMAC token binds:
  1. Queue name.
  2. Job definition name.
  3. Unique invocation identifier.
  4. SHA-256 digest of the job payload.
  5. Tenant reference and generation epoch.
- **Why this matters:** Copying a valid token from one job to another, or tampering with the job payload, immediately breaks HMAC verification.
- **Worker Verification:** When the worker unseals the token, it verifies the HMAC using `Spec.DurableKey` (supporting zero-downtime rotation via `RetiredDurableKeys`), and then disbelieves the record anyway: it asks the control plane what is true *now* before executing the job.

---

## 3. Event Sourcing Invariants (`vv/framework/event` & `eventpg`)

### 3.1 Composite Stream Identity
Streams are identified by a composite tuple: `(family, key)`.
```go
// From vv/framework/event/identity.go
func Compose(parts ...string) Key
```
- `Compose` uses an unambiguous byte-for-byte delimiter encoding. Delimiters in user input are escaped, preventing canonical key collision attacks (e.g. distinguishing `("a/b", "c")` from `("a", "b/c")`).

### 3.2 Database-Enforced Immutability & XID Allocation (`eventpg/schema.go`)
PostgreSQL tables for the event store are hardened with database triggers:
```sql
-- Append-only trigger
CREATE OR REPLACE FUNCTION events_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'eventpg: % on an event row: history is append-only', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER events_append_only_row
    BEFORE UPDATE OR DELETE ON frostgrove_events.events
    FOR EACH ROW EXECUTE FUNCTION events_append_only();

CREATE TRIGGER events_append_only_truncate
    BEFORE TRUNCATE ON frostgrove_events.events
    FOR EACH STATEMENT EXECUTE FUNCTION events_append_only();

-- Transaction allocation trigger
CREATE OR REPLACE FUNCTION writer_xid() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_current_xact_id();
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER events_position_needs_xid
    BEFORE INSERT ON frostgrove_events.events
    FOR EACH STATEMENT EXECUTE FUNCTION writer_xid();
```
- **Why `PERFORM pg_current_xact_id()` is critical:**
  PostgreSQL only assigns a transaction ID (`xid`) when a transaction first performs a mutating operation. If an event insert relies on `xmin` / `pg_snapshot` for gap-free keyset reads, ensuring an xid is allocated before insertion guarantees that concurrent transactions can be accurately tracked by subscribers.

### 3.3 Historical State Reads Without Token (`StateAt`)
```go
// From vv/framework/event/repo.go
func (r *Repo[S, ID]) StateAt(ctx context.Context, id ID, version uint64) (S, error)
```
- `Repo.Load(ctx, id)` returns `(State, At[S], error)`. The `At[S]` token carries the stream version and is strictly required to invoke `Repo.Append(...)`.
- `Repo.StateAt(...)` returns `(State, error)` — **no token is returned!**
- **The Design Rationale:** If `StateAt` returned an append token, developers would be tempted to execute `StateAt(v5) -> Decide -> Append`, appending new decisions based on historical state that ignored subsequent events (v6, v7). Correcting the past must always be done by loading the *present* state and issuing a compensating domain command.

---

## 4. Projection Engine & Sequence Parking (`event/projection`)

### 4.1 InUnit vs. AfterApply Delivery
A projection runner supports two delivery modes:
1. `InUnit`: Handler writes and checkpoint updates execute within the **same database transaction**.
   - The framework verifies transaction identity: `Destination` (the read model) and `Checkpoints` must share the same active transaction authority. If they point to different transactions, the pass halts with `ErrSpec`.
   - Exactly-once processing is achieved for relational read models inside that database.
2. `AfterApply`: For external destinations (Elasticsearch, Kafka, Webhooks).
   - Apply first, advance checkpoint second.
   - At-least-once semantics. Handlers must be idempotent (using event `(Stream, Version)`).

### 4.2 Sequence Parking (`Park`)
When an event handler fails permanently on an event:
```go
// From vv/framework/event/projection/park.go
type Park interface {
    Sequences(ctx context.Context, of Identity) (uint64, error)
    Holds(ctx context.Context, of Identity, sequence string) (bool, error)
    Park(ctx context.Context, letter Letter) error
    Holes(ctx context.Context, of Identity) (uint64, error)
}
```
1. **The Sequence Invariant:**
   - A sequence is derived by a `Sequencer` (default: `ByStream()`).
   - If an event on stream `Order-123` fails, `Park` marks sequence `Order-123` as quarantined.
   - That event and all subsequent events on `Order-123` are routed to the `Letter` quarantine queue.
   - **Crucially:** The projection loop does NOT halt, and other sequences (`Order-124`, `User-55`) continue streaming without delay!
2. **The Operator Redrive Engine (`Redriver`):**
   ```go
   // From vv/framework/event/projection/redrive.go
   type Redriver interface {
       Claim(ctx context.Context, of Identity, sequence string) (Claim, bool, error)
       Sequence(ctx context.Context, claim Claim) ([]Letter, error)
       Evict(ctx context.Context, claim Claim, letter Letter) error
       Touch(ctx context.Context, claim Claim, cause error) error
       Release(ctx context.Context, claim Claim) error
   }
   ```
   - An operator or automated task initiates a redrive of a parked sequence.
   - `Claim` acquires an exclusive, time-bounded lease on the sequence.
   - Letters are re-applied one-by-one through the projection handler inside a unit of work.
   - On success: `Evict(claim, letter)` removes the letter from the park.
   - On failure: `Touch(claim, cause)` records the new error and increments the attempt counter.

### 4.3 Blue-Green Projection Generations (`Generations`)
Rebuilding read models without downtime:
```go
// From vv/framework/event/projection/generation.go
type Generations interface {
    Active(ctx context.Context, projection string) (Generation, error)
    Activate(ctx context.Context, projection string, from, to Generation) error
}
```
1. Live traffic reads generation 1 (`orders` at `Ungenerated`).
2. Rebuild starts as `orders@2` (Generation 2). It initializes its own checkpoints and park tables.
3. `Observe(ctx, checkpoints, of, over)` monitors the lowest `Highest` watermark across all partitions of Generation 2.
4. When Generation 2 reaches the barrier of Generation 1 and `Park.Holes == 0`:
5. `Cutover(ctx, spec)` performs an atomic, fenced update of the active generation pointer in a single transaction.
6. Generation 1 is retired safely.

### 4.4 The Effect Gate (`EffectGate`)
```go
// From vv/framework/event/projection/effect.go
type Effects interface {
    Stage(ctx context.Context, effects []Effect) error
}
```
- Side effects (external emails, payment captures, external messaging) are declared as `Effects`.
- An effect is staged **inside the transaction that commits the advance**.
- During a projection rebuild (`Generation > 1` catching up) or an historical replay, `Spec.Effects` is set to `nil` or suppressed by the `EffectGate`.
- No emails or external calls are ever triggered during replays!

### 4.5 Read Visibility Waiting (`Wait` / `Mark`)
In asynchronous event-driven architectures, user requests often write a command and immediately navigate to a read screen. If the read-model projection hasn't applied the event, the user sees stale data ("read-your-writes" hazard).
- `vv` solves this with a polling visibility wait:
  ```go
  // From vv/framework/event/projection/wait.go
  func Wait(ctx context.Context, spec WaitSpec) (Visibility, error)
  ```
  - `WaitSpec.Committed(ctx, store, commit)` returns a `Mark` (the store positions committed).
  - The API handler calls `Wait(ctx, waitSpec)` which polls the projection's checkpoint and park until:
    1. The mark's position is reached (`Highest >= mark.At`).
    2. The sequence has zero parked holes (`Holds(sequence) == false`).
  - If reached -> returns fresh read model immediately (typically < 15ms).
  - If parked -> returns `ErrParked` (client renders "processing delayed" banner).
  - If timeout -> returns `ErrNotVisible` (client falls back to rendering from command result).

---

## 5. Command Idempotency Receipts (`event/receipt`)

### 5.1 The Two-Statement Atomic Claim
Instead of checking a key after the fact, `vv` claims the idempotency key *before* domain logic executes, using two ordered SQL statements inside the caller's transaction:
```sql
-- Statement 1: Speculative insertion (blocks concurrent writers)
INSERT INTO receipts (key, fingerprint, family, stream_key, recorded_at)
VALUES ($1, $2, $3, $4, statement_timestamp())
ON CONFLICT (key) DO NOTHING;

-- Statement 2: Read outcome in a fresh snapshot
SELECT key, fingerprint, family, stream_key, first_version, last_version, complete, recorded_at
  FROM receipts WHERE key = $1;
```
- If affected rows of Statement 1 == 1: We won the claim (`Recorded`).
- If affected rows == 0: Another transaction claimed this key. Statement 2 reads their row:
  - If `complete == true` AND `fingerprint == ourFingerprint`: Exact repeat! Return cached receipt (`Repeated`).
  - If `fingerprint != ourFingerprint`: Conflict! The key was spent on a different request (`Collided`).
  - If `complete == false`: Another transaction is still executing or died incomplete (`Incomplete`).

### 5.2 The Fingerprint Preimage
The payload fingerprint is computed using SHA-256 over a deterministic, length-prefixed encoding:
```text
[stream_family_len][stream_family][stream_key_len][stream_key]
[event_count]
FOR EACH EVENT:
  [type_len][type][revision][payload_bytes_len][payload_bytes]
```
- This preimage format is completely frozen.
- It covers the stream and the event changes, but deliberately does NOT cover the expected stream version (because a retry that arrives after the stream has moved must still match the fingerprint!).

---

## 6. Summary of Architectural Adoptions for Rain

| `vv/framework` Concept | Target in `rain` |
|---|---|
| `Authority.Bind` & `Scope` | `TenantContext` + `TenantBinding` (ScopedValue / Coroutines / ThreadLocal) |
| Operation classes (`Read`, `Write`, `Durable`) | `TenantOperationClass` with fail-closed consistency checks |
| `tenancyrow.Column` & `Through` | `TenantQueryNarrower` (jOOQ) + `TenantScopeRule` (`rain-crud`) |
| Sealed HMAC job tokens | `TenantJobToken` in `rain-jobs` with secret rotation |
| DB append-only triggers | PostgreSQL triggers in `V1__event_store.sql` (`rain_eventsource`) |
| `PERFORM pg_current_xact_id()` | PostgreSQL trigger on event insert for gap-free reading |
| `receipt.Once` + SHA-256 fingerprint | `IdempotencyClaimService` in `rain-eventsource` |
| Sequence Parking (`Park`) | `ParkedEventStore` + `rain_es_parked_event` in `rain-eventsource` |
| Operator `Redrive` | `EventRedriver` + Spring Boot command `es-redrive` |
| Blue-Green `Generations` | `ProjectionGenerationManager` + zero-downtime cutover |
| `EffectGate` | `EffectGate` bean silences `@SideEffect` handlers on replay |
| Visibility `Wait` / `Mark` | `ProjectionVisibilityWaiter` for read-your-writes guarantees |
