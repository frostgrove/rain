# Photon to Rain Porting & Patch Guide

**Purpose:** Comprehensive file-by-file inventory of `./tmp/photon` (`platform-tenancy`, `platform-eventsourcing`, and `platform-pii`), detailing exact destination paths in `rain`, porting actions, architectural adaptations, and concrete code patches.

---

## 1. Master Porting Inventory

| Source in `./tmp/photon` | Target in `rain` | Action | Required Remediations / Architectural Changes |
|---|---|---|---|
| **platform-eventsourcing** | | | |
| `domain/AggregateRoot.kt` | `rain-eventsource: .../domain/AggregateRoot.kt` | **Rewrite** | Explicit API mode; decouple from Spring; deterministic event versioning. |
| `domain/Primitives.kt` | `rain-eventsource: .../domain/StreamId.kt` | **Rewrite** | Adopt composite tuple `(family, key)` with length bounds and regex. |
| `domain/EventMetadata.kt` | `rain-eventsource: .../domain/EventMetadata.kt` | **Port** | Jackson 3 compatible; immutable; explicit API mode. |
| `gateway/CommandGateway.kt` | `rain-eventsource: .../gateway/CommandGateway.kt` | **Rewrite** | Add `IdempotencyClaimService`; integrate SHA-256 fingerprinting. |
| `gateway/CommandContext.kt` | `rain-eventsource: .../gateway/CommandContext.kt` | **Port** | Virtual thread compatible; immutable metadata. |
| `gateway/CommandInterceptor.kt` | `rain-eventsource: .../gateway/CommandInterceptor.kt` | **Rewrite** | Replace naive key dedup with two-statement receipt claim. |
| `repository/AggregateRepository.kt`| `rain-eventsource: .../repository/AggregateRepository.kt`| **Rewrite** | Port from raw JDBC to jOOQ; inject `Clock`; safe snapshot fallback. |
| `repository/EventRepository.kt` | `rain-eventsource: .../store/JooqEventStore.kt` | **Rewrite** | 100% typed jOOQ over `rain_eventsource.events`; criterion v3 plan proofs. |
| `repository/IdempotencyRepository.kt`| `rain-eventsource: .../receipt/ReceiptLedger.kt` | **Rewrite** | Port to `receipt.Once` with two-statement insert+select claim. |
| `subscription/EventSubscriptionProcessor.kt` | `rain-eventsource: .../subscription/SubscriptionProcessor.kt` | **Rewrite** | **CRITICAL FIX**: Remove aborted-tx bug; replace single skip with stream parking. |
| `subscription/DeadLetterStore.kt`| `rain-eventsource: .../park/ParkedEventStore.kt` | **Rewrite** | Replaced by `ParkedEventStore` owning `rain_es_parked_event`. |
| `subscription/EventHandlers.kt` | `rain-eventsource: .../subscription/EventHandlers.kt` | **Port** | Decouple from reflection; support `@SideEffect` and `EffectGate`. |
| `replay/ProjectionReplayer.kt` | `rain-eventsource: .../generation/GenerationManager.kt` | **Rewrite** | Replace in-place truncate with zero-downtime Blue-Green Generations. |
| `replay/ResettableProjection.kt`| `rain-eventsource: .../generation/Generations.kt` | **Rewrite** | Replaced by `Generations.activate(from, to)` atomic cutover. |
| `upcaster/Upcaster.kt` | `rain-eventsource: .../upcaster/Upcaster.kt` | **Port** | Explicit API; type-safe chain `From[V]()` -> `Then[A, B]()`. |
| `timetravel/AggregateTimeTravel.kt`| `rain-eventsource: .../timetravel/TimeTravel.kt` | **Port** | Map to `Repo.stateAt(family, key, version)` returning no append token. |
| `store/PartitionMaintenanceJob.kt`| `rain-eventsource: .../store/PartitionMaintenance.kt` | **Rewrite** | Port to `rain-jobs` `RecurringWork`. |
| `EventSourcingAutoConfiguration.kt`| `rain-eventsource: .../autoconfigure/...` | **Rewrite** | Role-gated: API role gets gateways; Worker role gets subscribers. |
| **platform-tenancy** | | | |
| `TenantId.kt` | `rain-tenancy: .../TenantId.kt` | **Rewrite** | Value class; regex validation `^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`. |
| `TenantContext.kt` | `rain-tenancy: .../TenantContext.kt` | **Rewrite** | Java 25 `ScopedValue` + CoroutineContextElement + ThreadLocal. |
| `internal/TenantRoutingDataSource.kt`| `rain-tenancy: .../routing/TenantRoutingDataSource.kt`| **Rewrite** | Support all 4 strategies (`DISCRIMINATOR`, `SCHEMA`, `DATABASE`, `TIERED`). |
| `internal/TenantPoolCache.kt` | `rain-tenancy: .../routing/TenantPoolCache.kt` | **Port** | Add idle eviction; connection bounds; health check metrics. |
| `internal/TenantContextFilter.kt` | `rain-tenancy: .../web/TenantPerimeterFilter.kt` | **Rewrite** | Add origin check; lifecycle admission check; negative cache protection. |
| `controlplane/ControlPlane.kt` | `rain-tenancy: .../controlplane/ControlPlane.kt` | **Port** | Explicit API; caching with negative-cache prevention. |
| `controlplane/JdbcControlPlane.kt` | `rain-tenancy: .../controlplane/JooqControlPlane.kt` | **Rewrite** | Port from raw JDBC to jOOQ over `rain_tenancy.tenants`. |
| `controlplane/TenantLifecycle.kt`| `rain-tenancy: .../controlplane/TenantAdmission.kt` | **Rewrite** | Add operation classes (`Read`, `Write`, `Durable`) and whitelist. |
| `provisioning/*` | `rain-tenancy: .../provisioning/*` | **Rewrite** | Multi-strategy provisioning: creates schema/DB or sets up RLS row. |
| `TenantFlywayMigrator.kt` | `rain-tenancy: .../migration/TenantFlywayMigrator.kt` | **Port** | Ported to Rain's `RainSchemaMigrationStrategy`. |
| `TenancyAutoConfiguration.kt` | `rain-tenancy: .../autoconfigure/...` | **Rewrite** | Conditional on `rain.tenancy.enabled=true`. |
| **platform-pii** | | | |
| `PiiAead.kt` | `rain-eventsource-pii: .../PiiAead.kt` | **Port** | AES-256-GCM authenticated encryption; explicit API. |
| `EncryptedField.kt` | `rain-eventsource-pii: .../EncryptedField.kt` | **Port** | Jackson 3 module integration; sealed interface (`Present`, `Erased`). |
| `PiiKeyStore.kt` | `rain-eventsource-pii: .../PiiKeyStore.kt` | **Rewrite** | Port to jOOQ over `rain_eventsource_pii.pii_subject_key`. |
| `PiiSnapshotEraser.kt` | `rain-eventsource-pii: .../PiiSnapshotEraser.kt`| **Port** | Scrubs `rain_eventsource.snapshots` upon subject erasure. |
| `PiiReadModelScrubber.kt` | `rain-eventsource-pii: .../PiiReadModelScrubber.kt`| **Port** | Dispatches scrub events to registered projection scrubbers. |
| `PiiErasureService.kt` | `rain-eventsource-pii: .../PiiErasureService.kt`| **Port** | Coordinates atomic erasure and logs to `pii_erasure_log`. |

---

## 2. Concrete Code Patches for Critical Defect Areas

### 2.1 Patch 1: Fixing the PostgreSQL Aborted-Transaction Crash in `SubscriptionProcessor`

**The Defect in Photon:**
```kotlin
// BEFORE (Buggy Photon Code):
@Transactional(propagation = Propagation.REQUIRES_NEW)
open fun processBatch(...) {
    for (event in events) {
        try {
            handler.handle(event)
        } catch (ex: Exception) {
            // PostgreSQL transaction is ABORTED!
            // This INSERT fails with PSQLException: transaction is aborted:
            deadLetterStore.recordAttempt(subName, event, ex) 
            break
        }
    }
}
```

**The Production Patch for Rain:**
```kotlin
// AFTER (Fixed Rain Code):
public class SubscriptionProcessor(
    private val eventStore: EventStore,
    private val parkedStore: ParkedEventStore,
    private val checkpointStore: CheckpointStore,
    private val handler: ProjectionHandler,
    private val transactionManager: PlatformTransactionManager
) {
    public fun processNextBatch(projection: String, maxBatchSize: Int) {
        val checkpoint = checkpointStore.getCheckpoint(projection)
        val batch = eventStore.readBatch(checkpoint.highest, maxBatchSize)
        if (batch.isEmpty()) return

        for (envelope in batch) {
            val sequence = handler.sequenceFor(envelope)
            
            // Check if sequence is already quarantined
            if (parkedStore.holds(projection, sequence)) {
                // Divert to park queue without executing handler
                parkedStore.park(projection, sequence, envelope, cause = null)
                checkpointStore.advance(projection, envelope.position, quarantinedInc = 1)
                continue
            }

            // Execute handler in dedicated transaction with SAVEPOINT protection
            val transactionTemplate = TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }

            val success = transactionTemplate.execute { status ->
                val savepoint = (status as SavepointManager).createSavepoint()
                try {
                    handler.handle(envelope)
                    checkpointStore.advance(projection, envelope.position, appliedInc = 1)
                    true
                } catch (ex: Exception) {
                    // Rollback savepoint immediately to clear PostgreSQL aborted status:
                    (status as SavepointManager).rollbackToSavepoint(savepoint)
                    
                    log.warn("Handler failed on event ${envelope.position} for sequence $sequence: ${ex.message}")
                    
                    // Park the ENTIRE sequence
                    parkedStore.park(projection, sequence, envelope, cause = ex)
                    checkpointStore.advance(projection, envelope.position, quarantinedInc = 1)
                    
                    // Transaction commits the quarantine record and checkpoint advance
                    true
                }
            } ?: false

            if (!success) {
                log.error("Fatal failure handling subscription for projection $projection")
                break
            }
        }
    }
}
```

---

### 2.2 Patch 2: Upgrading `TenantContext` to Loom Virtual Threads & Coroutines

**The Defect in Photon:**
Uses standard `java.lang.ThreadLocal<TenantId>`, causing context loss in Coroutines and memory leaks on Virtual Threads.

**The Production Patch for Rain:**
```kotlin
package com.gd.rain.tenancy

import java.lang.ScopedValue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

public object TenantContext {
    private val SCOPED_BINDING: ScopedValue<TenantBinding?> = ScopedValue.newInstance()
    private val THREAD_LOCAL_BINDING: ThreadLocal<TenantBinding?> = ThreadLocal()

    public fun currentOrNull(): TenantId? = currentBinding()?.tenantId
    
    public fun currentBinding(): TenantBinding? =
        if (SCOPED_BINDING.isBound) SCOPED_BINDING.get() else THREAD_LOCAL_BINDING.get()

    public fun require(): TenantId = currentOrNull()
        ?: throw TenantRequiredFault("Operation requires an active tenant context")

    public fun <T> withTenant(binding: TenantBinding, block: () -> T): T {
        return ScopedValue.callWhere(SCOPED_BINDING, binding) {
            val previous = THREAD_LOCAL_BINDING.get()
            THREAD_LOCAL_BINDING.set(binding)
            try {
                block()
            } finally {
                THREAD_LOCAL_BINDING.set(previous)
            }
        }
    }

    internal fun setThreadLocal(binding: TenantBinding?) {
        if (binding == null) THREAD_LOCAL_BINDING.remove() else THREAD_LOCAL_BINDING.set(binding)
    }
}

public class TenantCoroutineContext(
    public val binding: TenantBinding
) : ThreadContextElement<TenantBinding?>, AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<TenantCoroutineContext>

    override fun updateThreadContext(context: CoroutineContext): TenantBinding? {
        val old = TenantContext.currentBinding()
        TenantContext.setThreadLocal(binding)
        return old
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: TenantBinding?) {
        TenantContext.setThreadLocal(oldState)
    }
}
```

---

### 2.3 Patch 3: Command Idempotency Fingerprinted Receipts (`receipt.Once`)

**The Defect in Photon:**
Checks if `key` exists and blindly returns old state, ignoring altered command parameters.

**The Production Patch for Rain:**
```kotlin
public class CommandGateway(
    private val receiptLedger: ReceiptLedger,
    private val aggregateRepository: AggregateRepository,
    private val transactionTemplate: TransactionTemplate
) {
    public fun <C : DomainCommand, S : Any> execute(
        command: C,
        idempotencyKey: String?,
        stream: StreamId
    ): CommandResult<S> {
        if (idempotencyKey == null) {
            return executeWithoutIdempotency(command, stream)
        }

        // 1. Calculate SHA-256 payload fingerprint
        val fingerprint = computeFingerprint(stream, command)

        // 2. Execute claim inside transaction
        return transactionTemplate.execute {
            val claim = receiptLedger.claim(idempotencyKey, fingerprint, stream)
            when (claim) {
                is ClaimVerdict.Repeated -> {
                    // Safe replay! Load version recorded by first attempt
                    val aggregate = aggregateRepository.loadAtVersion<S>(stream, claim.receipt.lastVersion)
                    CommandResult.Repeated(aggregate.state, claim.receipt)
                }
                is ClaimVerdict.Recorded -> {
                    // Won the claim: execute domain decision
                    val aggregate = aggregateRepository.load<S>(stream)
                    val events = aggregate.decide(command)
                    val commit = aggregateRepository.append(stream, aggregate.version, events)
                    
                    // Mark claim complete
                    receiptLedger.complete(idempotencyKey, commit.firstVersion, commit.lastVersion)
                    CommandResult.Recorded(aggregate.state, commit)
                }
            }
        } ?: throw Fault.internal("Transaction execution returned null")
    }
}
```

---

### 2.4 Patch 4: Blue-Green Projection Generations Rebuild

**The Defect in Photon:**
Invokes `projection.reset()` (`TRUNCATE read_model`), causing total read downtime during rebuilds.

**The Production Patch for Rain:**
```kotlin
public class ProjectionRebuilder(
    private val generationManager: ProjectionGenerationManager,
    private val checkpointStore: CheckpointStore,
    private val parkedStore: ParkedEventStore,
    private val subscriberFactory: ProjectionSubscriberFactory,
    private val dsl: DSLContext
) {
    public fun rebuildProjection(projectionName: String) {
        val currentGen = generationManager.getActiveGeneration(projectionName)
        val targetGen = currentGen + 1

        log.info("Starting zero-downtime rebuild for '$projectionName': Gen $currentGen -> Gen $targetGen")

        // 1. Create target generation read model table: read_model_v{targetGen}
        generationManager.initializeGenerationTables(projectionName, targetGen)

        // 2. Launch rebuild subscriber writing into targetGen
        val rebuildSubscriber = subscriberFactory.createRebuildSubscriber(projectionName, targetGen)
        rebuildSubscriber.start()

        // 3. Poll until caught up and zero parked holes
        while (true) {
            val barrier = generationManager.observeBarrier(projectionName, currentGen)
            val rebuildProgress = checkpointStore.getCheckpoint("$projectionName@$targetGen")
            val holes = parkedStore.holes("$projectionName@$targetGen")

            if (rebuildProgress.highest >= barrier.position && holes == 0L) {
                log.info("Rebuild generation $targetGen caught up to watermark ${barrier.position} with 0 holes")
                break
            }
            Thread.sleep(500)
        }

        // 4. Perform atomic fenced cutover in single transaction
        dsl.transaction { config ->
            val txDsl = DSL.using(config)
            // Repoint SQL View: view_{projection} -> read_model_v{targetGen}
            generationManager.cutoverView(txDsl, projectionName, targetGen)
            // Update active generation metadata
            generationManager.activate(txDsl, projectionName, from = currentGen, to = targetGen)
        }

        log.info("Cutover to Gen $targetGen complete with zero downtime. Retiring Gen $currentGen.")
        generationManager.retireGeneration(projectionName, currentGen)
    }
}
```
