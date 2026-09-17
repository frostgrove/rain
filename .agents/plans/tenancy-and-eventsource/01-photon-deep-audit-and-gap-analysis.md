# Photon Architecture Deep Audit & Gap Analysis

**Subject:** Comprehensive audit of `./tmp/photon`  
**Scope:** All 12 modules in `./tmp/photon` (`platform-tenancy`, `platform-eventsourcing`, `platform-pii`, `platform-temporal`, `platform-audit`, `platform-cache`, `platform-observability`, `platform-storage`, `platform-web`, `photon-core`, `platform-samplekit`, `modules`).  
**Objective:** Evaluate architectural soundness, identify fatal correctness bugs, catalog missed enterprise use cases, and define required remediations for the `rain` framework.

---

## 1. Module Inventory of `./tmp/photon`

The `./tmp/photon` repository contains a multi-module Kotlin/Spring Boot prototype. Here is the structural breakdown of its platform modules:

```text
tmp/photon/
├── platform-tenancy/          # Database-per-tenant routing, Hikari cache, Control plane, Flyway migrator
├── platform-eventsourcing/    # Event store (xid8), AggregateRoot, Subscriptions, Snapshots, Upcasters, S3 Archive
├── platform-pii/              # AEAD crypto-shredding, Subject keys, Snapshot eraser, Read-model scrubber
├── platform-temporal/         # Workflow orchestration for multi-tenant fleet migrations and long-running tasks
├── platform-audit/            # Audit trail recording and web controller
├── platform-cache/            # Distributed Redis cache bindings
├── platform-observability/    # Micrometer metrics and OpenTelemetry tracing
├── platform-storage/          # S3 object storage abstraction
├── platform-web/              # Web perimeter, error problem+json rendering, CORS
├── photon-core/               # Shared domain primitives, value classes, and errors
└── modules/                   # Business domain modules (commerce, content, media, publication, etc.)
```

---

## 2. Exhaustive Use Case Evaluation Matrix (99% SaaS Coverage)

This matrix compares the coverage in `./tmp/photon` against standard enterprise requirements and specifies the architectural target for `rain`:

| Domain | Capability / Use Case | Photon Coverage | Architectural Evaluation in Photon | Target in Rain Framework |
|---|---|---|---|---|
| **Tenancy** | Database-per-tenant | ✅ Implemented | Functional Hikari pool cache; suffers from PG connection exhaustion at scale | Retained for Enterprise/Regulated tier |
| **Tenancy** | Schema-per-tenant | ❌ Missing | Completely absent; no dynamic `search_path` support | **Required**: Mid-tier SaaS isolation |
| **Tenancy** | Row-Level (Discriminator) | ❌ Missing | Completely absent; no query narrowing or RLS | **Required**: High-density low-cost tier |
| **Tenancy** | Tiered / Hybrid Routing | ⚠️ Partial | Hardcoded enum (`SHARED` vs `PREMIUM`) without routing engine | **Required**: Dynamic routing via Control Plane |
| **Tenancy** | Virtual Thread / Loom Context | ❌ Missing | Uses standard `ThreadLocal`; risks memory leaks on Loom | **Required**: Java 25 `ScopedValue` engine |
| **Tenancy** | Kotlin Coroutines Context | ❌ Missing | No `CoroutineContextElement`; context lost on dispatcher switch | **Required**: `TenantCoroutineContext` |
| **Tenancy** | Background Job Propagation | ❌ Missing | Job workers execute without tenant context or unverified string | **Required**: HMAC-sealed durable job tokens |
| **Tenancy** | Preload Relation Isolation | ❌ Missing | Preloaded child entities are un-scoped; cross-tenant leak risk | **Required**: Declared relation narrowing |
| **Tenancy** | Lifecycle Admission Whitelist | ⚠️ Primitive | Enums `ACTIVE`, `SUSPENDED`, `DELETED` checked ad-hoc | **Required**: Whitelist per operation class |
| **Tenancy** | Origin Environment Fencing | ❌ Missing | Tokens can cross environments (dev -> prod) | **Required**: Environment origin binding |
| **Tenancy** | Mid-Request Revalidation | ❌ Missing | Revoked/deleted tenant continues operating until request end | **Required**: Optional `revalidate` mode |
| **Tenancy** | Cross-Tenant Admin Grants | ❌ Missing | Unrestricted elevation or bypass | **Required**: Bounded, audited temporary grants |
| **Event Sourcing** | Append-Only Event Store | ✅ Implemented | PostgreSQL table `ES_EVENT` with `xid8` and `event_id` | **Required**: jOOQ + DB triggers in `rain_eventsource` |
| **Event Sourcing** | Immutability DB Triggers | ❌ Missing | App-level promise only; vulnerable to DB scripts/updates | **Required**: `BEFORE UPDATE/DELETE/TRUNCATE` triggers |
| **Event Sourcing** | Transaction ID Allocation | ❌ Missing | Standard insert; doesn't force transaction allocation | **Required**: `BEFORE INSERT: PERFORM pg_current_xact_id()` |
| **Event Sourcing** | Gap-Free Keyset Reading | ✅ Implemented | `(tx_id, id) > (last_tx, last_id)` + `xmin` watermark | **Required**: Optimized keyset cursor over `position` |
| **Event Sourcing** | Optimistic Concurrency | ✅ Implemented | Expected version check on aggregate stream | **Required**: CAS stream update with jittered backoff |
| **Event Sourcing** | Command Idempotency | ⚠️ Defective | Checks key collision only; ignores payload alterations | **Required**: SHA-256 fingerprint (`receipt.Once`) |
| **Event Sourcing** | Poison Event Dead-Lettering | ❌ **CRITICAL BUG** | **Aborted PG Connection Bug**: infinite crash loop | **Fixed**: Autonomous transaction / savepoint |
| **Event Sourcing** | Stream / Sequence Quarantine | ❌ Missing | Skips individual events; **causes state corruption** | **Required**: Sequence Parking (`Park`) |
| **Event Sourcing** | Operator Redrive API | ❌ Missing | Dead letters are stranded; no operator unpark API | **Required**: Exclusive Redrive engine (`Claim`/`Evict`) |
| **Event Sourcing** | Zero-Downtime Rebuilds | ❌ Missing | `ProjectionReplayer` truncates read model in-place (downtime!) | **Required**: Blue-Green `Generations` (`Cutover`) |
| **Event Sourcing** | Replay Side-Effect Silencing | ❌ Missing | Emails and webhooks re-fire during replay storms | **Required**: `EffectGate` (`Spec.Effects`) |
| **Event Sourcing** | Schema Upcasting | ✅ Implemented | `UpcasterChain` + startup validation | **Required**: Type-safe declarative upcaster chain |
| **Event Sourcing** | Aggregate Snapshots | ✅ Implemented | Snapshots every Nth event; drops on drift | **Required**: Versioned snapshots with safe fallback |
| **Event Sourcing** | Read Visibility / Wait API | ❌ Missing | Caller must `Thread.sleep` to wait for async projection | **Required**: Monotonic `Wait` / `Mark` polling API |
| **Event Sourcing** | Partitioned Parallel Streams | ❌ Missing | Single subscriber thread per projection | **Required**: Bitmask partitioning (`Cover`) + `Split` |
| **Event Sourcing** | GDPR Crypto-Shredding | ✅ Implemented | AEAD encryption in `platform-pii` | **Required**: Integrated `rain-eventsource-pii` |
| **Architecture** | ADR 0001 (Schema Ownership) | ❌ Violates | Unqualified tables in `public` or tenant DBs | **Required**: Strict `rain_<module>` schemas |
| **Architecture** | ADR 0002 (jOOQ vs. Spring Data) | ❌ Violates | Uses raw `NamedParameterJdbcTemplate` with raw SQL | **Required**: 100% typed jOOQ generated code |
| **Architecture** | Scale Guarantees (Criterion v3) | ❌ Violates | No query plan proofs; queries unverified under 10M rows | **Required**: Mandatory `QueryPlans.boundedScan` |

---

## 3. Deep Analysis of Critical Correctness Defects in Photon

### 3.1 Defect 1: The PostgreSQL Aborted-Transaction Crash Loop (P0 Blocker)
- **Location:** `com.photon.eventsourcing.subscription.EventSubscriptionProcessor.kt:85-115` and `DeadLetterStore.kt:42`.
- **The Defective Implementation:**
  ```kotlin
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  open fun processNewEventsImpl(...) {
      for (event in events) {
          try {
              eventHandler.handleEvent(event) // <--- If this throws PSQLException (e.g. UniqueConstraintViolation)
              lastGoodEventId = event.id
          } catch (ex: Exception) {
              log.error("Event failed: ${event.id}", ex)
              // ATTEMPT TO PERSIST TO DEAD LETTER STORE ON THE SAME CONNECTION:
              deadLetterStore.recordAttempt(subscriptionName, event, ex) // <--- FATAL CRASH!
              if (deadLetterStore.getAttempts(subscriptionName, event.id) >= maxAttempts) {
                  deadLetterStore.markDeadLetter(subscriptionName, event, ex)
                  lastGoodEventId = event.id // skip poisoned event
              }
              break
          }
      }
  }
  ```
- **The PostgreSQL Invariant Broken:**
  In PostgreSQL, whenever any SQL statement inside a transaction block fails (e.g. unique constraint violation, foreign key check failure, type conversion error), the PostgreSQL server marks the current backend transaction `ABORTED`.
  Any subsequent SQL statement sent over that same database connection immediately fails with:
  ```text
  org.postgresql.util.PSQLException: ERROR: current transaction is aborted, commands ignored until end of transaction block
  ```
- **Catastrophic Failure Mode:**
  1. An event handler fails due to an integrity constraint in the database.
  2. The catch block catches the exception and immediately invokes `deadLetterStore.recordAttempt(...)`.
  3. `deadLetterStore` attempts to execute an `INSERT` or `UPDATE` using the ambient Spring `@Transactional` connection.
  4. PostgreSQL throws `ERROR: current transaction is aborted`.
  5. The entire `REQUIRES_NEW` transaction rolls back.
  6. **The failure attempt is never recorded!** The dead letter table remains empty!
  7. On the very next polling cycle (or notification), the subscriber fetches the exact same event again.
  8. The subscriber enters an **infinite busy-loop of crashing transactions**, consuming 100% CPU and flooding disk logs with gigabytes of stack traces, while the subscription remains permanently halted!
- **The Rain Solution:**
  1. Handlers must execute inside an explicit **Savepoint** boundary (`TransactionTemplate` with nested savepoints or manual `Connection.setSavepoint()`).
  2. If an exception occurs, the savepoint is rolled back immediately to clear PostgreSQL's aborted transaction state before attempting to write to the quarantine/dead-letter table.
  3. Alternatively, dead-letter and attempt recording must be dispatched through an **autonomous transaction context** on an independent physical connection.

---

### 3.2 Defect 2: Poison-Event Skip Causing State Corruption (P0 Blocker)
- **Location:** `EventSubscriptionProcessor.kt:98-105`.
- **The Defective Mechanism:**
  When an event exceeds `maxAttempts`, Photon marks it as dead-lettered and advances the subscription cursor:
  ```kotlin
  lastGoodEventId = event.id // advances checkpoint past the failed event!
  ```
- **The Data Corruption Scenario:**
  Consider an aggregate stream `Order-9988`:
  1. Event #1: `OrderCreated(orderId="9988", customerId="cust-1", total=500)` -> Fails projection due to transient lock timeout or unmapped status code. Exceeds 3 retries and is skipped!
  2. Event #2: `OrderPaymentProcessed(orderId="9988", paymentId="pay-456")` -> Next in queue. The subscription cursor was advanced past Event #1, so Event #2 is executed!
  3. Event #3: `OrderFulfilled(orderId="9988", trackingNumber="TRK-789")` -> Executed!
  **Result in Read Model:**
  The projection table `orders_view` receives `OrderPaymentProcessed` and `OrderFulfilled` for an order that was *never created*! The SQL update statement updates 0 rows silently, or creates a corrupted stub row missing customer ID and line items. The customer's dashboard displays a corrupted, phantom order.
- **The Rain Solution (Stream Parking / `Park`):**
  Adopt the formal invariant from `vv/framework/event/projection/park.go`:
  - When an event fails for stream `Order-9988`, **the entire stream `Order-9988` is parked (quarantined)**.
  - Event #1 and all subsequent events (#2, #3) for `Order-9988` are held in table `rain_es_parked_event`.
  - **Crucially:** Completely independent streams (`Order-9989`, `Invoice-101`) continue processing at full speed without head-of-line blocking!
  - Causal consistency per stream is mathematically guaranteed.
  - An operator can inspect the quarantine queue, fix the bug, and invoke `Redrive("Order-9988")` to process the events in strict original order.

---

### 3.3 Defect 3: Monolithic Multitenancy & PostgreSQL Connection Exhaustion (P1)
- **Location:** `com.photon.tenancy.internal.TenantRoutingDataSource.kt` and `TenantPoolCache.kt`.
- **The Defective Mechanism:**
  Photon assumes that *every* tenant receives an independent physical PostgreSQL database and an independent `HikariDataSource` pool:
  ```kotlin
  class TenantPoolCache(
      private val maxPools: Int = 100,
      private val idleMinutes: Long = 30
  ) {
      private val poolCache = Caffeine.newBuilder()
          .maximumSize(maxPools)
          .expireAfterAccess(Duration.ofMinutes(idleMinutes))
          .removalListener { _, pool, _ -> pool?.close() }
          .build<TenantId, HikariDataSource>()
  }
  ```
- **The Production Failure:**
  1. In standard PostgreSQL deployments, `max_connections` is configured between 200 and 500 (PostgreSQL forks a process per connection; memory consumption degrades severely beyond 1000 connections).
  2. If a SaaS platform has 300 tenants across 3 application replica pods:
     - Minimum pool size: 5 connections per tenant.
     - `300 tenants * 5 connections * 3 pods = 4,500 connections`!
  3. PostgreSQL runs out of connection slots immediately (`FATAL: sorry, too many clients already`).
  4. Furthermore, 90% of tenants in typical SaaS tiers have low activity; dedicating persistent pools and separate databases to them is economically unviable.
- **The Rain Solution:**
  Implement a pluggable 4-strategy tenancy architecture:
  1. `DISCRIMINATOR`: Shared database, shared tables, `tenant_id` column. Automated jOOQ query narrowing and optional PostgreSQL RLS. Supports millions of tenants on one database pool.
  2. `SCHEMA`: Shared database, isolated PostgreSQL schemas (`tenant_<slug>`). Single shared connection pool with dynamic `search_path`.
  3. `DATABASE`: Dedicated physical databases for high-value enterprise tiers.
  4. `TIERED`: Automatic hybrid routing (e.g. Free/Pro -> `DISCRIMINATOR`; Enterprise -> `DATABASE`).

---

### 3.4 Defect 4: In-Place Projection Replay Causing Read Downtime (P1)
- **Location:** `com.photon.eventsourcing.replay.ProjectionReplayer.kt:35-58`.
- **The Defective Mechanism:**
  ```kotlin
  fun replay(projectionName: String) {
      val projection = resettableProjections.first { it.name == projectionName }
      projection.reset() // <--- EXECUTES "TRUNCATE TABLE read_model;" !!
      
      var cursor = 0L
      while (true) {
          val batch = eventRepository.readBatch(cursor, batchSize)
          if (batch.isEmpty()) break
          batch.forEach { projection.apply(it) }
          cursor = batch.last().position
      }
  }
  ```
- **The Production Failure:**
  When `projection.reset()` runs, the read model table is truncated. For an event store with 50 million events, replaying and projecting the history takes 15 to 45 minutes. During this entire time window, all user-facing queries and API calls return empty results or 404 errors!
- **The Rain Solution (Blue-Green Projection Generations):**
  Adopt `vv/framework/event/projection/generation.go`:
  - Active traffic reads `orders_view` pointing to generation 1 (`orders_view_gen_1`).
  - To rebuild, the system spins up generation 2 (`orders_view_gen_2`).
  - The rebuild subscriber catches up from the beginning of time into `orders_view_gen_2`.
  - While rebuilding, live users continue querying `orders_view_gen_1` with zero downtime.
  - When generation 2 catches up to the live event watermark, an atomic compare-and-swap cutover flips the view or table pointer to `orders_view_gen_2`.
  - Generation 1 is retired and drained asynchronously.

---

### 3.5 Defect 5: Unhedged External Side Effects During Replay (P1)
- **Location:** `com.photon.eventsourcing.subscription.EventHandlers.kt`.
- **The Defective Mechanism:**
  Projections and event listeners in Photon do not differentiate between deterministic in-database read-model updates and external side effects (sending emails via SendGrid, charging cards via Stripe, publishing messages to Kafka).
- **The Production Failure:**
  During an event replay or projection rebuild, millions of historical events pass through the event handler pipeline. If an event handler triggers `emailService.sendOrderConfirmation(order)`, thousands of historical emails are re-sent to real customers, causing severe reputational damage and financial cost!
- **The Rain Solution (The Effect Gate):**
  Adopt `vv/framework/event/projection/effect.go`:
  - Provide an explicit `EffectGate` abstraction.
  - Handlers declare side effects via `@SideEffect` or `EffectHandler`.
  - The framework passes an immutable `EffectDispatcher` into handlers.
  - During replays, historical time-travel, or background rebuilds, the `EffectGate` automatically silences all side-effect dispatchers.
  - Side effects are staged inside the committing transaction, ensuring they roll back if the event append or advance fails.

---

### 3.6 Defect 6: Idempotency Key Payload Mismatch Vulnerability (P2)
- **Location:** `com.photon.eventsourcing.gateway.CommandInterceptor.kt` and `IdempotencyRepository.kt`.
- **The Defective Mechanism:**
  Photon checks whether an `idempotencyKey` exists:
  ```kotlin
  val existing = idempotencyRepository.findByKey(command.idempotencyKey)
  if (existing != null) {
      return commandGateway.loadAggregate(existing.aggregateId) // Silently returns previous aggregate!
  }
  ```
- **The Security / Integrity Risk:**
  If a client accidentally or maliciously submits a request with an existing idempotency key but with *different command parameters* (e.g. `amount = 5000` instead of `amount = 50`), Photon sees that the key exists and silently returns the old aggregate state. The caller believes the new payment was accepted, while in reality the parameters were completely ignored!
- **The Rain Solution (SHA-256 Fingerprinted Receipts):**
  Adopt `vv/framework/event/receipt`:
  - Every idempotency claim computes a SHA-256 **Fingerprint** over the normalized payload bytes and target stream.
  - The claim answers one of three verdicts:
    1. `Recorded`: Brand new claim; execute command and commit.
    2. `Repeated`: Exact match of key AND fingerprint; safe replay of cached receipt.
    3. `Collided`: Same key BUT different fingerprint or target stream; throw `Fault.conflict("idempotency key reused with different payload")`.

---

### 3.7 Defect 7: Context Leaks on Java 21/25 Virtual Threads (P2)
- **Location:** `com.photon.tenancy.TenantContext.kt`.
- **The Defective Mechanism:**
  Photon relies purely on `java.lang.ThreadLocal<TenantId>`.
- **The Loom Risk:**
  On Java 21/25 with Project Loom Virtual Threads, unbounded creation of virtual threads with standard `ThreadLocal` can lead to memory retention and difficult-to-trace context bleeding across task boundaries. Furthermore, Kotlin coroutines that switch dispatchers (`withContext(Dispatchers.IO)`) lose the `ThreadLocal` value unless wrapped in a custom `ThreadContextElement`.
- **The Rain Solution:**
  Dual-carrier context engine in `rain-tenancy`:
  1. Java 25 `ScopedValue<TenantBinding>` as the primary carrier on Virtual Threads.
  2. `ThreadLocal<TenantBinding>` fallback for legacy platform threads.
  3. `TenantCoroutineContextElement` for seamless Kotlin coroutine propagation.

---

### 3.8 Defect 8: Bypassing Rain Architectural Rules (ADRs)
- **ADR 0001 (Schema per module):**
  Photon dumps all tables into `public` or into unqualified tenant databases. Rain mandates that every module owns a schema: `rain_tenancy` and `rain_eventsource`.
- **ADR 0002 (jOOQ over generated code):**
  Photon uses `NamedParameterJdbcTemplate` with raw SQL strings (`"SELECT * FROM es_event WHERE..."`). Rain mandates visible, type-safe jOOQ queries against code generated from migrations via `rain.jooq-schema`.
- **Conventions (Plan-Proven Bounded Queries):**
  Photon has zero query plan assertions. Rain mandates that every read query over extensible tables is proven bounded under `QueryPlans.boundedScan` (Criterion v3).

---

## 4. Summary of Required Remediations

To port the capabilities of `tmp/photon` safely into `rain`, we must:
1. Re-architect the multi-tenancy core to support all 4 storage strategies (`DISCRIMINATOR`, `SCHEMA`, `DATABASE`, `TIERED`).
2. Fix the PostgreSQL aborted transaction bug by executing parking and attempt tracking in autonomous transaction boundaries.
3. Replace single-event dead-letter skipping with sequence-preserving **Stream Parking (`Park`)** and an operator **Redrive** API.
4. Replace in-place projection resets with zero-downtime **Blue-Green Generations**.
5. Implement SHA-256 **Idempotency Receipts** (`receipt.Once`).
6. Enforce immutability and transaction allocation via database triggers.
7. Integrate GDPR Article 17 crypto-shredding from `platform-pii`.
8. Implement all persistence using jOOQ under `rain_tenancy` and `rain_eventsource` schemas with full Criterion v3 plan proofs.
