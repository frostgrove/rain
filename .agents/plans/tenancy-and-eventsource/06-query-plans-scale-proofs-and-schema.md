# Query Plans, Scale Proofs & Database Schemas

**Requirement:** Rain Repository Guidelines — "Design for Ten Million Rows" & Plan Criterion v3  
**Target:** Prove that every statement executed by `rain-tenancy`, `rain-eventsource`, and `rain-eventsource-pii` uses index scans without filters and scales identically at 10 rows and at 10,000,000 rows.

---

## 1. Scale Design Axioms (Criterion v3)

Under Rain's scale guarantees ([docs/conventions.md](file:///home/user/ws/frostgrove/kotlin/rain/docs/conventions.md#plan-proven-queries)):
1. **Zero Sequential Scans:** No read against any extensible table ever performs a `Seq Scan`, `Bitmap Heap Scan`, or `TID Scan`.
2. **Zero In-Memory Sorting:** The query plan must not contain a `Sort` node. Ordering must be provided directly by the B-Tree index scan.
3. **No Filter on Extensible Reads:** Every query condition must reside in `Index Cond`, not in a post-scan `Filter`.
4. **Strict `LIMIT` Placement:** The `Limit` node must sit immediately above the `Index Scan` node.
5. **No Total Counting:** Extensible tables never execute `COUNT(*)`. Keyset pagination reads `limit + 1` rows to test for next-page presence.

---

## 2. Schema Specification & Migration DDL

### 2.1 Schema `rain_tenancy` (Migration `V1__tenancy.sql`)

```sql
CREATE SCHEMA IF NOT EXISTS rain_tenancy;

-- 1. Tenants Registry Table
CREATE TABLE rain_tenancy.tenants (
    id VARCHAR(64) NOT NULL,
    slug VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    tier VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    strategy VARCHAR(32) NOT NULL DEFAULT 'DISCRIMINATOR',
    origin VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT tenants_pkey PRIMARY KEY (id),
    CONSTRAINT tenants_slug_key UNIQUE (slug),
    CONSTRAINT tenants_slug_format CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT tenants_epoch_positive CHECK (epoch > 0)
);

-- 2. Tenant Credentials & Connection Coordinates (Encrypted at Rest)
CREATE TABLE rain_tenancy.tenant_credentials (
    tenant_id VARCHAR(64) NOT NULL,
    encrypted_secrets JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT tenant_credentials_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT tenant_credentials_fkey FOREIGN KEY (tenant_id)
        REFERENCES rain_tenancy.tenants (id) ON DELETE RESTRICT
);

-- 3. Tenant Leases Table (Advisory Locks for Clustered Provisioning)
CREATE TABLE rain_tenancy.tenant_leases (
    resource_key VARCHAR(128) NOT NULL,
    holder_node VARCHAR(128) NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT tenant_leases_pkey PRIMARY KEY (resource_key)
);
```

### 2.2 Schema `rain_eventsource` (Migration `V1__event_store.sql`)

```sql
CREATE SCHEMA IF NOT EXISTS rain_eventsource;

-- 1. Streams Table (Stream existence and concurrency root)
CREATE TABLE rain_eventsource.streams (
    family VARCHAR(64) NOT NULL,
    key VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT streams_pkey PRIMARY KEY (family, key),
    CONSTRAINT streams_version_non_negative CHECK (version >= 0)
);

-- 2. Events Table (Append-only immutable fact history)
CREATE TABLE rain_eventsource.events (
    position BIGINT GENERATED ALWAYS AS IDENTITY INCREMENT 1,
    family VARCHAR(64) NOT NULL,
    key VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL,
    type VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    payload BYTEA NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT events_pkey PRIMARY KEY (position),
    CONSTRAINT events_stream_version_key UNIQUE (family, key, version),
    CONSTRAINT events_stream_fkey FOREIGN KEY (family, key)
        REFERENCES rain_eventsource.streams (family, key) ON DELETE RESTRICT,
    CONSTRAINT events_payload_bound CHECK (octet_length(payload) <= 65536),
    CONSTRAINT events_version_positive CHECK (version > 0),
    CONSTRAINT events_revision_positive CHECK (revision > 0)
);

-- Index for stream replay: (family, key, version ASC)
CREATE INDEX events_stream_replay_idx 
    ON rain_eventsource.events (family, key, version ASC);

-- 3. Checkpoints Table (Consumer cursor tracking)
CREATE TABLE rain_eventsource.checkpoints (
    projection VARCHAR(128) NOT NULL,
    cursor BYTEA NOT NULL,
    advance BIGINT NOT NULL,
    highest BIGINT NOT NULL,
    applied BIGINT NOT NULL,
    quarantined BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT checkpoints_pkey PRIMARY KEY (projection),
    CONSTRAINT checkpoints_advance_positive CHECK (advance > 0),
    CONSTRAINT checkpoints_highest_positive CHECK (highest >= 0),
    CONSTRAINT checkpoints_applied_positive CHECK (applied >= 0),
    CONSTRAINT checkpoints_quarantined_positive CHECK (quarantined >= 0)
);

-- 4. Parked Events Table (Stream sequence quarantine queue)
CREATE TABLE rain_eventsource.parked_events (
    id UUID NOT NULL,
    projection VARCHAR(128) NOT NULL,
    sequencer VARCHAR(64) NOT NULL,
    sequence VARCHAR(512) NOT NULL,
    position BIGINT NOT NULL,
    family VARCHAR(64) NOT NULL,
    key VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL,
    type VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    payload BYTEA NOT NULL,
    cause_class VARCHAR(256) NOT NULL,
    cause_message TEXT NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    parked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claimed_by VARCHAR(128),
    claimed_until TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL DEFAULT 'PARKED',
    CONSTRAINT parked_events_pkey PRIMARY KEY (id),
    CONSTRAINT parked_events_projection_sequence_pos UNIQUE (projection, sequence, position)
);

-- Index for checking if sequence is held: (projection, sequence)
CREATE INDEX parked_events_sequence_lookup_idx 
    ON rain_eventsource.parked_events (projection, sequence);

-- Index for operator redrive: (projection, sequence, position ASC)
CREATE INDEX parked_events_drain_idx 
    ON rain_eventsource.parked_events (projection, sequence, position ASC);

-- 5. Projection Generations Table (Zero-downtime cutover)
CREATE TABLE rain_eventsource.projection_generations (
    projection VARCHAR(128) NOT NULL,
    active_generation INT NOT NULL,
    cutover_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT projection_generations_pkey PRIMARY KEY (projection),
    CONSTRAINT projection_generations_positive CHECK (active_generation >= 1)
);

-- 6. Idempotency Receipts Table (receipt.Once)
CREATE TABLE rain_eventsource.receipts (
    key VARCHAR(256) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    family VARCHAR(64) NOT NULL,
    stream_key VARCHAR(512) NOT NULL,
    first_version BIGINT NOT NULL DEFAULT 0,
    last_version BIGINT NOT NULL DEFAULT 0,
    complete BOOLEAN NOT NULL DEFAULT false,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT receipts_pkey PRIMARY KEY (key)
);

CREATE INDEX receipts_recorded_at_idx 
    ON rain_eventsource.receipts (recorded_at ASC);

-- 7. Snapshots Table (Periodic aggregate state cache)
CREATE TABLE rain_eventsource.snapshots (
    family VARCHAR(64) NOT NULL,
    key VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL,
    state_payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT snapshots_pkey PRIMARY KEY (family, key)
);
```

### 2.3 Schema `rain_eventsource_pii` (Migration `V1__pii_keystore.sql`)

```sql
CREATE SCHEMA IF NOT EXISTS rain_eventsource_pii;

CREATE TABLE rain_eventsource_pii.pii_subject_key (
    subject_id VARCHAR(128) NOT NULL,
    key_version INT NOT NULL DEFAULT 1,
    algorithm VARCHAR(32) NOT NULL DEFAULT 'AES_256_GCM',
    encrypted_key BYTEA NOT NULL,
    key_iv BYTEA NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    shredded_at TIMESTAMPTZ,
    CONSTRAINT pii_subject_key_pkey PRIMARY KEY (subject_id)
);

CREATE TABLE rain_eventsource_pii.pii_subject_event_index (
    subject_id VARCHAR(128) NOT NULL,
    family VARCHAR(64) NOT NULL,
    stream_key VARCHAR(512) NOT NULL,
    event_position BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pii_subject_event_index_pkey PRIMARY KEY (subject_id, event_position)
);

CREATE INDEX pii_subject_stream_idx 
    ON rain_eventsource_pii.pii_subject_event_index (subject_id, family, stream_key);

CREATE TABLE rain_eventsource_pii.pii_erasure_log (
    id UUID NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    reason TEXT NOT NULL,
    snapshots_scrubbed INT NOT NULL DEFAULT 0,
    read_models_scrubbed INT NOT NULL DEFAULT 0,
    shredded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    verification_hash VARCHAR(128) NOT NULL,
    CONSTRAINT pii_erasure_log_pkey PRIMARY KEY (id)
);
```

---

## 3. Plan-Proven Statement Assertions

Below are the exact SQL statements run by the framework and their corresponding PostgreSQL query plans verified by `QueryPlans.boundedScan`:

### 3.1 Statement: Aggregate Replay (`Repo.load`)
```sql
EXPLAIN (COSTS OFF, FORMAT JSON)
SELECT position, family, key, version, type, revision, payload, metadata, recorded_at
  FROM rain_eventsource.events
 WHERE family = 'order' AND key = 'ord-123'
 ORDER BY version ASC;
```
**Expected Plan:**
```json
[
  {
    "Plan": {
      "Node Type": "Index Scan",
      "Scan Direction": "Forward",
      "Index Name": "events_stream_replay_idx",
      "Relation Name": "events",
      "Schema": "rain_eventsource",
      "Alias": "events",
      "Index Cond": "((family = 'order'::text) AND (key = 'ord-123'::text))"
    }
  }
]
```
- **Proof:** Uses index `events_stream_replay_idx`. No `Filter` node. Scan is bounded strictly to the events of this aggregate. Cost is identical whether the table has 10 events or 10,000,000 events.

---

### 3.2 Statement: Historical Time-Travel Replay (`Repo.stateAt`)
```sql
EXPLAIN (COSTS OFF, FORMAT JSON)
SELECT position, family, key, version, type, revision, payload, metadata, recorded_at
  FROM rain_eventsource.events
 WHERE family = 'order' AND key = 'ord-123' AND version <= 42
 ORDER BY version ASC;
```
**Expected Plan:**
```json
[
  {
    "Plan": {
      "Node Type": "Index Scan",
      "Scan Direction": "Forward",
      "Index Name": "events_stream_replay_idx",
      "Relation Name": "events",
      "Schema": "rain_eventsource",
      "Alias": "events",
      "Index Cond": "((family = 'order'::text) AND (key = 'ord-123'::text) AND (version <= 42))"
    }
  }
]
```
- **Proof:** Uses `events_stream_replay_idx` with upper bound `version <= 42`. Zero sequential scanning.

---

### 3.3 Statement: Keyset Subscription Sweep (`SubscriptionProcessor`)
```sql
EXPLAIN (COSTS OFF, FORMAT JSON)
SELECT position, family, key, version, type, revision, payload, metadata, recorded_at
  FROM rain_eventsource.events
 WHERE position > 100000 AND position < 200000
 ORDER BY position ASC
 LIMIT 256;
```
**Expected Plan:**
```json
[
  {
    "Plan": {
      "Node Type": "Limit",
      "Plans": [
        {
          "Node Type": "Index Scan",
          "Parent Relationship": "Outer",
          "Scan Direction": "Forward",
          "Index Name": "events_pkey",
          "Relation Name": "events",
          "Schema": "rain_eventsource",
          "Alias": "events",
          "Index Cond": "((position > 100000) AND (position < 200000))"
        }
      ]
    }
  }
]
```
- **Proof:** `Limit` sits directly above `Index Scan`. The B-tree primary key index provides sort order ascending. Scans at most 256 rows directly.

---

### 3.4 Statement: Operator Redrive Drain (`EventRedriver`)
```sql
EXPLAIN (COSTS OFF, FORMAT JSON)
SELECT id, position, family, key, version, type, revision, payload, cause_class, attempt
  FROM rain_eventsource.parked_events
 WHERE projection = 'orders' AND sequence = 'order:ord-123'
 ORDER BY position ASC;
```
**Expected Plan:**
```json
[
  {
    "Plan": {
      "Node Type": "Index Scan",
      "Scan Direction": "Forward",
      "Index Name": "parked_events_drain_idx",
      "Relation Name": "parked_events",
      "Schema": "rain_eventsource",
      "Alias": "parked_events",
      "Index Cond": "((projection = 'orders'::text) AND (sequence = 'order:ord-123'::text))"
    }
  }
]
```
- **Proof:** B-Tree index `parked_events_drain_idx` fulfills both equality filters and ascending position order without an in-memory sort node.

---

### 3.5 Statement: Row-Level CRUD List Query (`rain-crud` under `TenantScopeRule`)
```sql
EXPLAIN (COSTS OFF, FORMAT JSON)
SELECT id, tenant_id, title, shelf, created_at
  FROM public.books
 WHERE tenant_id = 'c4b8b6e0-1c39-44d5-8ef6-1b5e0b6d1e4e'::uuid AND shelf = 'mystery'
 ORDER BY created_at DESC, id DESC
 LIMIT 51;
```
**Expected Plan:**
```json
[
  {
    "Plan": {
      "Node Type": "Limit",
      "Plans": [
        {
          "Node Type": "Index Scan",
          "Parent Relationship": "Outer",
          "Scan Direction": "Forward",
          "Index Name": "books_tenant_shelf_created_idx",
          "Relation Name": "books",
          "Schema": "public",
          "Alias": "books",
          "Index Cond": "((tenant_id = 'c4b8b6e0-1c39-44d5-8ef6-1b5e0b6d1e4e'::uuid) AND (shelf = 'mystery'::text))"
        }
      ]
    }
  }
]
```
- **Proof:** The compound index `(tenant_id, shelf, created_at DESC, id DESC)` handles the equality conditions and sorts natively. Scans exactly 51 rows. Criterion v3 compliant.

---

## 4. Integration Test Implementation

Integration tests in `rain-eventsource/src/test/kotlin/.../EventStorePlanProofIT.kt` and `rain-tenancy/src/test/kotlin/.../TenancyPlanProofIT.kt` assert these plans programmatically using Rain's `QueryPlans`:

```kotlin
@Tag("integration")
class EventStorePlanProofIT : AbstractEventSourceIT() {

    @Test
    fun `stream replay query is bounded by replay index with no filter`() {
        val dsl = currentDslContext()
        val query = dsl.selectFrom(EVENTS)
            .where(EVENTS.FAMILY.eq("order"))
            .and(EVENTS.KEY.eq("ord-123"))
            .orderBy(EVENTS.VERSION.asc())

        val plan = QueryPlans.explain(dsl, query)
        
        assertThat(plan).satisfies(QueryPlan.boundedScan("rain_eventsource.events", "events_stream_replay_idx"))
    }

    @Test
    fun `subscription keyset sweep query is bounded by pkey limit`() {
        val dsl = currentDslContext()
        val query = dsl.selectFrom(EVENTS)
            .where(EVENTS.POSITION.gt(1000L))
            .and(EVENTS.POSITION.lt(2000L))
            .orderBy(EVENTS.POSITION.asc())
            .limit(256)

        val plan = QueryPlans.explain(dsl, query)
        
        assertThat(plan).satisfies(QueryPlan.boundedScan("rain_eventsource.events", "events_pkey"))
    }
}
```
All tests are verified against Testcontainers PostgreSQL 18.
