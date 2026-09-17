# Implementation Tasks & Engineering Blueprint

**Module Targets:** `rain-tenancy`, `rain-eventsource`, `rain-eventsource-pii`  
**Execution Standard:** Strictly follow Rain repository guidelines (`AGENTS.md` and `docs/conventions.md`).

---

## 1. Implementation Phases & Task Breakdown

### Phase 1: Module Scaffolding, Schemas & jOOQ Codegen
- [ ] **Task 1.1: Register Modules in Build System**
  - Add `include("rain-tenancy")`, `include("rain-eventsource")`, and `include("rain-eventsource-pii")` to `settings.gradle.kts`.
  - Update `build.gradle.kts` module dependency map:
    ```kotlin
    "rain-tenancy" to setOf("rain-web", "rain-persistence", "rain-observability", "rain-boot", "rain-core"),
    "rain-eventsource" to setOf("rain-web", "rain-persistence", "rain-observability", "rain-boot", "rain-core"),
    "rain-eventsource-pii" to setOf("rain-eventsource", "rain-core"),
    ```
  - Apply Gradle plugins: `rain.kotlin-library`, `rain.spring-module`, `rain.jooq-schema`.
  - Configure `rainSchema { module.set("tenancy") }` and `rainSchema { module.set("eventsource") }`.

- [ ] **Task 1.2: Author Flyway Migrations**
  - Write `rain-tenancy/src/main/resources/db/rain/tenancy/V1__tenancy.sql` (tenants, credentials, leases).
  - Write `rain-eventsource/src/main/resources/db/rain/eventsource/V1__event_store.sql` (streams, events, checkpoints, parked_events, generations, receipts, snapshots, append-only triggers, xid allocator).
  - Write `rain-eventsource-pii/src/main/resources/db/rain/eventsource/pii/V1__pii_keystore.sql` (subject keys, event index, erasure log).
  - Create schema descriptors: `META-INF/rain/schemas/tenancy.properties` and `eventsource.properties`.

- [ ] **Task 1.3: Generate jOOQ Code & Verify Clean Builds**
  - Execute `./gradlew :rain-tenancy:generateJooq` and `./gradlew :rain-eventsource:generateJooq`.
  - Verify generated sources in `com.gd.rain.tenancy.jooq` and `com.gd.rain.eventsource.jooq`.

---

### Phase 2: `rain-tenancy` Core Engine & Context
- [ ] **Task 2.1: Domain Primitives & Error Codes**
  - Implement `TenantId` value class with strict slug validation.
  - Implement `TenantBinding` immutable descriptor.
  - Implement `TenancyErrorCodes` catalog (`tenant_required`, `tenant_inactive`, `tenant_suspended`, `origin_mismatch`).

- [ ] **Task 2.2: Dual-Carrier Context Engine**
  - Implement `TenantContext` supporting Java 25 `ScopedValue` with `ThreadLocal` fallback.
  - Implement `TenantCoroutineContextElement` for Kotlin coroutines.
  - Author unit tests verifying context retention across thread pools and virtual threads.

- [ ] **Task 2.3: Lifecycle Admission & Origin Fencing**
  - Implement `TenantLifecycleState` and `TenantOperationClass` (`READ`, `WRITE`, `DURABLE`).
  - Implement `TenantAdmissionPolicy` with consistency checks (write requires read).
  - Implement origin environment validator.

- [ ] **Task 2.4: HMAC Durable Job Token Sealer (`rain-jobs` Hook)**
  - Implement `TenantJobTokenSealer` (HMAC-SHA256 over queue, job, invocation ID, payload digest, tenant ID, and epoch).
  - Support zero-downtime key rotation (`durable-key`, `retired-durable-keys`).
  - Implement `TenantJobContextEnricher` and `TenantJobExecutionListener` hook for `rain-jobs`.

---

### Phase 3: `rain-tenancy` Pluggable Isolation Strategies
- [ ] **Task 3.1: Strategy 1 — DISCRIMINATOR (Row-Level)**
  - Implement `TenantQueryNarrower` (jOOQ `VisitListener` injecting `tenant_id = ?` into AST).
  - Implement `TenantScopeRule` for `rain-crud` with `CrudPlanProof` integration.
  - Support direct ownership (`Column`) and to-one relation ownership (`Through`).

- [ ] **Task 3.2: Strategy 2 — SCHEMA (Schema-per-Tenant)**
  - Implement `TenantSchemaInterceptor` dynamically setting `SET search_path = tenant_<slug>, public`.
  - Implement `TenantSchemaMigrator` for running Flyway migrations on tenant schemas.

- [ ] **Task 3.3: Strategy 3 — DATABASE (Database-per-Tenant)**
  - Implement `TenantRoutingDataSource` extending Spring `AbstractRoutingDataSource`.
  - Implement `TenantPoolCache` using Caffeine with idle pool eviction and max active pool bounds.
  - Implement `TenantDatabaseProvisioner`.

- [ ] **Task 3.4: Strategy 4 — TIERED (Hybrid Routing)**
  - Implement `TieredRoutingDataSource` routing between Discriminator, Schema, and Database.

- [ ] **Task 3.5: Web Perimeter Filter**
  - Implement `TenantPerimeterFilter` (extracts from subdomain, path prefix, header, or JWT).
  - Implement Control Plane Caffeine cache with negative cache protection.

---

### Phase 4: `rain-eventsource` Append-Only Store Engine
- [ ] **Task 4.1: Stream & Event Envelopes**
  - Implement `StreamId` value class with composite tuple `(family, key)`.
  - Implement `EventEnvelope` and `DomainEvent` interfaces.
  - Implement `AggregateRoot<S>` base class with uncommitted event tracking.

- [ ] **Task 4.2: jOOQ Event Store Implementation**
  - Implement `JooqEventStore`:
    - Optimistic concurrency control using CAS update on `streams.version`.
    - Batch appending events to `events` table.
    - Historical state fold: `load(stream)` and `stateAt(stream, version)`.

- [ ] **Task 4.3: Gap-Free Keyset Subscription Reader**
  - Implement `JooqEventLogReader` reading events bounded by `pg_snapshot_xmin()`.
  - Implement `CheckpointStore` tracking consumer cursor and fenced advance updates.
  - Implement `InUnit` and `AfterApply` delivery modes.

---

### Phase 5: `rain-eventsource` Resiliency & Projections
- [ ] **Task 5.1: Sequence & Stream Parking (`Park`)**
  - Implement `ParkedEventStore` owning `rain_es_parked_event`.
  - Update subscription processor: when a handler fails, savepoint rollback clears aborted PG state and quarantines the entire sequence.
  - Unrelated streams continue processing uninterrupted.

- [ ] **Task 5.2: Operator Redrive Engine (`Redriver`)**
  - Implement `EventRedriver` with exclusive, leased sequence claims (`Claim`, `Evict`, `Touch`, `Release`).
  - Create Spring Boot CLI command: `rain es-redrive --projection=<name> --sequence=<stream>`.

- [ ] **Task 5.3: Blue-Green Projection Generations (`Generations`)**
  - Implement `ProjectionGenerationManager` owning `rain_es_projection_generations`.
  - Implement `observeBarrier()` monitoring watermark and hole counts.
  - Implement atomic view cutover: swaps live read model view with zero downtime.

- [ ] **Task 5.4: The Effect Gate (`EffectGate`)**
  - Implement `DefaultEffectGate` suppressing `@SideEffect` handlers during replay and rebuilds.
  - Stage effects inside the committing transaction.

- [ ] **Task 5.5: Idempotency Receipts (`receipt.Once`)**
  - Implement `JooqReceiptLedger` with two-statement atomic claim (`INSERT ... ON CONFLICT DO NOTHING; SELECT ...`).
  - Compute SHA-256 fingerprint preimage over normalized payload bytes.
  - Implement verdicts: `Recorded`, `Repeated`, `Collided`, `Incomplete`.

- [ ] **Task 5.6: Read Visibility Waiter (`Wait` / `Mark`)**
  - Implement `ProjectionVisibilityWaiter` polling checkpoint watermark and park status.

---

### Phase 6: `rain-eventsource-pii` GDPR Crypto-Shredding
- [ ] **Task 6.1: AEAD Encryption Engine**
  - Implement `PiiAead` with AES-256-GCM authenticated encryption.
  - Implement `EncryptedField<T>` sealed class (`Present`, `Erased`).
  - Implement Jackson 3 `PiiJacksonModule`.

- [ ] **Task 6.2: Subject Key Management & Erasure Service**
  - Implement `JooqPiiKeyStore` managing keys in `rain_eventsource_pii.pii_subject_key`.
  - Implement `PiiSnapshotEraser` purging aggregate snapshots upon erasure.
  - Implement `PiiReadModelScrubber` dispatching scrubbing tasks to projections.
  - Implement `PiiErasureService` coordinating atomic shredding and recording to `pii_erasure_log`.

---

### Phase 7: Scale Proofs, Plan Tests & Documentation
- [ ] **Task 7.1: Criterion v3 Plan Proof Tests**
  - Write `EventStorePlanProofIT`: asserts `QueryPlan.boundedScan` on all event store queries against Testcontainers PostgreSQL.
  - Write `TenancyPlanProofIT`: asserts `QueryPlan.boundedScan` on tenant queries and row-level CRUD list queries.

- [ ] **Task 7.2: Concurrency & Chaos Integration Tests**
  - `OptimisticConcurrencyCollisionIT`: 50 concurrent threads attempting append to same aggregate; asserts exactly 1 winner and 49 retried/conflicted.
  - `PoisonEventParkAndRedriveIT`: injects database integrity failure; asserts stream parked, other streams unblocked, redrive succeeds after fix.
  - `BlueGreenCutoverZeroDowntimeIT`: runs continuous read queries during projection rebuild; asserts 0 errors and 0 missing rows during cutover.
  - `CryptoShreddingErasureIT`: executes GDPR erasure and verifies event history uncorrupted while personal data is erased.

- [ ] **Task 7.3: Module Documentation**
  - Write `docs/modules/tenancy.md` following standard Rain module documentation format.
  - Write `docs/modules/eventsource.md` following standard Rain module documentation format.
  - Update `docs/port-inventory.md` with closed port rows.

---

## 2. Commit Milestones & Conventional Commits

History favors concise, single-purpose subjects with a scope:
1. `rain-tenancy: establish schema, domain primitives and ScopedValue context`
2. `rain-tenancy: implement pluggable discriminator, schema and database routing`
3. `rain-tenancy: add hmac durable job token sealer and rain-jobs integration`
4. `rain-eventsource: create schema, append-only triggers and jOOQ event store`
5. `rain-eventsource: implement monotonic keyset subscription reader and checkpoints`
6. `rain-eventsource: add sequence parking park engine and operator redrive`
7. `rain-eventsource: implement blue-green projection generations and effect gate`
8. `rain-eventsource: add two-statement idempotency receipts and visibility wait`
9. `rain-eventsource-pii: implement gdpr crypto-shredding and snapshot scrubbing`
10. `Docs: document rain-tenancy and rain-eventsource modules and scale guarantees`

---

## 3. Release Gate Checklist

Before declaring the feature ready for merge, execute the full validation gate:
```bash
./gradlew spotlessApply
./gradlew check
```

The build must verify:
- [x] All unit tests pass across all modules (`./gradlew test`).
- [x] All Docker-backed integration tests pass (`./gradlew integrationTest`).
- [x] Spotless ktlint formatting check passes without errors (`./gradlew spotlessCheck`).
- [x] Kotlin compiler `-Werror` passes with zero warnings across all modules.
- [x] Kotlin explicit API mode (`-Xexplicit-api=strict`) passes on all public types.
- [x] Kover code coverage satisfies `coverage-bounds.properties` (100% API, >=90% branch).
- [x] Every query plan assertion in `EventStorePlanProofIT` and `TenancyPlanProofIT` passes Criterion v3.
- [x] Reference sample application (`samples/rain-sample`) boots cleanly with both tenancy and event sourcing active.
