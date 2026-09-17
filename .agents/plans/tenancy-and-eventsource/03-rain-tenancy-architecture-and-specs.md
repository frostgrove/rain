# `rain-tenancy` Architecture & Specification

**Module:** `rain-tenancy`  
**Package:** `com.gd.rain.tenancy`  
**Schema:** `rain_tenancy` (ADR 0001)  
**Configuration Namespace:** `rain.tenancy.*`  
**Optionality:** 100% Optional (`rain.tenancy.enabled=false` by default, zero runtime overhead when dormant)

---

## 1. Architectural Principles & Invariants

The `rain-tenancy` module provides multi-tenancy capabilities across data planes, execution runtimes, and background tasks. Its architecture adheres to five formal invariants:

1. **Zero Runtime Overhead When Dormant:** If `rain.tenancy.enabled=false`, no routing datasources, servlet filters, coroutine interceptors, or query narrower listeners are registered. The dependency graph remains completely neutral.
2. **Four Unified Isolation Strategies:**
   - **`DISCRIMINATOR` (Row-Level):** Shared database and tables; partitioned by `tenant_id` column with automated jOOQ query narrowing, `rain-crud` `TenantScopeRule`, and optional PostgreSQL Row-Level Security (RLS).
   - **`SCHEMA` (Schema-per-Tenant):** Shared database; each tenant is partitioned into schema `tenant_<slug>` via dynamic `search_path`.
   - **`DATABASE` (Database-per-Tenant):** Physical PostgreSQL database per tenant with an adaptive, idle-evicting HikariCP connection pool cache (`TenantPoolCache`).
   - **`TIERED` (Hybrid):** Dynamic routing based on tenant tier metadata (e.g. Free/Pro -> `DISCRIMINATOR`; Regulated/Enterprise -> `DATABASE`).
3. **Unforgeable Dual-Carrier Scope Engine:** A tenant scope cannot be forged by application code. It is verified by the `TenantAuthority` and carried across:
   - Java 25 Virtual Threads via Project Loom `ScopedValue<TenantBinding>`.
   - Platform threads via `ThreadLocal<TenantBinding>`.
   - Kotlin Coroutines via `TenantCoroutineContextElement`.
4. **Lifecycle Admission Whitelist & Origin Fencing:**
   - Tenants declare states: `RESERVED`, `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DELETING`, `DELETED`, `ARCHIVED`.
   - Operations belong to three orthogonal classes: `ClassRead`, `ClassWrite`, and `ClassDurable`.
   - Admitted states are declared per class. Consistency is enforced fail-closed at startup: any state admitted for `ClassWrite` MUST be admitted for `ClassRead`.
   - Environment `Origin` is baked into tokens, preventing credentials from crossing environments.
5. **HMAC-Sealed Durable Job Identities:** When enqueuing asynchronous tasks into `rain-jobs`, the tenant context is sealed using an HMAC-SHA256 token binding queue name, job definition, invocation ID, payload digest, and tenant epoch. Workers verify the token and re-validate against the control plane before execution.

---

## 2. Configuration & Properties

All configuration properties reside under the `rain.tenancy` section and are registered through `TenancyConfigurationContributor` for early validation:

```yaml
rain:
  tenancy:
    enabled: true                          # Master toggle (default: false)
    strategy: DISCRIMINATOR                # DISCRIMINATOR | SCHEMA | DATABASE | TIERED
    origin: "prod-eu-west-1"               # Environment origin fencing string (required if enabled)
    revalidate: false                      # Re-verify tenant with control plane on every query (default: false)
    
    # Request Perimeter Resolution
    resolution:
      header-name: "X-Tenant-ID"          # Primary header extraction
      subdomain-suffix: ".saas.example.com" # Optional subdomain extraction (e.g. acme.saas.example.com)
      path-prefix-enabled: false           # Support /api/t/{tenant}/...
      jwt-claim: "tenant_id"              # JWT claim extraction from SecurityContext
      cache-ttl: 5m                       # Local cache duration for resolved tenant descriptors
      negative-cache-ttl: 30s             # Cache duration for non-existent slugs (DDoS protection)
      
    # Strategy 1: DISCRIMINATOR (Row-Level)
    discriminator:
      column-name: "tenant_id"            # Column name for row ownership
      column-type: "UUID"                 # UUID | STRING | BIGINT
      enforce-rls: false                  # Generate and assert PostgreSQL RLS session variables
      session-variable: "rain.tenant_id"  # PostgreSQL local setting for RLS
      
    # Strategy 2: SCHEMA
    schema:
      prefix: "tenant_"                   # Schema prefix: tenant_<slug>
      baseline-locations:                 # Flyway migrations for tenant schemas
        - "classpath:db/rain/tenant"
        
    # Strategy 3: DATABASE
    database:
      max-active-pools: 128               # Maximum open Hikari pools per application instance
      idle-evict-minutes: 30              # Evict pool if unused for 30 minutes
      default-pool-size: 10               # Maximum connections per tenant database
      min-idle-connections: 2
      connection-timeout: 30s
      
    # Durable Work & Job Sealing (rain-jobs integration)
    durable:
      durable-key: "${TENANCY_DURABLE_KEY}" # HMAC-SHA256 secret (at least 32 bytes)
      retired-durable-keys: []            # Previous keys for zero-downtime rotation
```

---

## 3. Storage & Isolation Strategies

### 3.1 Strategy 1: DISCRIMINATOR (Shared-Row)
In this strategy, all tenants share physical tables in the application's schema (or module schemas).
- **jOOQ Query Narrowing (`TenantQueryNarrower`):**
  A jOOQ `VisitListener` and `RecordListener` that inspects every AST query node:
  1. For `SELECT`, `UPDATE`, `DELETE`: automatically injects `AND tenant_id = ?` to the `WHERE` clause for any table declaring the tenant column.
  2. For `INSERT`: automatically validates or stamps `tenant_id` from `TenantContext.require()`. If a caller attempts to insert a row with a different `tenant_id`, a `TenantIntegrityFault` is thrown immediately.
- **`rain-crud` Integration (`TenantScopeRule`):**
  When `rain-crud` is on the classpath, `rain-tenancy` registers a `TenantScopeRule`:
  ```kotlin
  public class TenantScopeRule : ScopeRule {
      override fun apply(schema: ResourceSchema, caller: CallerLookup): Condition {
          val tenantId = TenantContext.require()
          val tenantField = schema.field("tenantId") 
              ?: throw Fault.spec("Resource ${schema.name} has no tenantId field under DISCRIMINATOR tenancy")
          return DSL.field(DSL.name(tenantField.column)).eq(tenantId.toDbValue())
      }
  }
  ```
  Every statement plan proof (`CrudPlanProof`) proves that `(tenant_id, id)` or `(tenant_id, <sort_key>, id)` index scans bound the query under plan criterion v3.
- **Declared Preload Relations:**
  To prevent cross-tenant leaks when loading related child entities (e.g. `order.items`), child tables without a direct `tenant_id` must declare their ownership path:
  ```kotlin
  public sealed interface TenantOwnership {
      public data class Column(val columnName: String = "tenant_id") : TenantOwnership
      public data class Through(val toOneRelation: String, val parentColumn: String = "tenant_id") : TenantOwnership
  }
  ```
  A to-one relation compiles to:
  ```sql
  WHERE EXISTS (
      SELECT 1 FROM parent_table p 
       WHERE p.id = child_table.parent_id AND p.tenant_id = :current_tenant
  )
  ```
  **Invariable Rule:** A to-many relation path is strictly refused at startup.

---

### 3.2 Strategy 2: SCHEMA (Schema-per-Tenant)
In this strategy, tenants share a PostgreSQL database server and physical database, but each tenant has a dedicated PostgreSQL schema (`tenant_<slug>`).
- **Dynamic Search Path Interception:**
  A delegating `DataSource` interceptor intercepts `getConnection()`:
  ```sql
  SET search_path = tenant_acme, rain_tenancy, public;
  ```
  When the connection is returned to the pool, the search path is reset to `public`.
- **Tenant Schema Migrations (`TenantSchemaMigrator`):**
  When a tenant is provisioned, `TenantSchemaMigrator` executes Flyway migrations specifically targeted at `tenant_<slug>`:
  ```kotlin
  Flyway.configure()
      .dataSource(dataSource)
      .schemas("tenant_${tenant.value}")
      .defaultSchema("tenant_${tenant.value}")
      .locations(*schemaProperties.baselineLocations.toTypedArray())
      .load()
      .migrate()
  ```

---

### 3.3 Strategy 3: DATABASE (Database-per-Tenant)
For high-compliance enterprise tiers, each tenant receives an isolated PostgreSQL physical database.
- **`TenantRoutingDataSource` & `TenantPoolCache`:**
  - Dynamic `AbstractRoutingDataSource` implementation.
  - Connection pools are created on-demand and cached in `TenantPoolCache` backed by Caffeine.
  - Idle eviction: pools with no active connections for `idle-evict-minutes` are cleanly closed to conserve server file descriptors and memory.
  - Connection limits: capped at `max-active-pools` (default: 128) per node. Least recently accessed idle pools are evicted when capacity is reached.
- **Control Plane Integration:**
  Database credentials and host coordinates are resolved dynamically from `TenantControlPlane` via secure `TenantSecretsResolver`. Credentials are never stored as plain text in the database.

---

### 3.4 Strategy 4: TIERED (Hybrid Routing)
The `TieredRoutingDataSource` routes each request based on the tenant's tier declared in `TenantDescriptor`:
- `FREE`, `STARTER`, `PRO` -> Routed to the shared `DISCRIMINATOR` DataSource.
- `ISOLATED_COMPLIANCE` -> Routed to dedicated `SCHEMA`.
- `ENTERPRISE` -> Routed to dedicated physical `DATABASE`.

---

## 4. Context Engine & Runtimes

### 4.1 `TenantContext` Engine
Supports Virtual Threads (Java 25 `ScopedValue`), Platform Threads (`ThreadLocal`), and Kotlin Coroutines:

```kotlin
package com.gd.rain.tenancy

import java.lang.ScopedValue
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
            val prev = THREAD_LOCAL_BINDING.get()
            THREAD_LOCAL_BINDING.set(binding)
            try {
                block()
            } finally {
                THREAD_LOCAL_BINDING.set(prev)
            }
        }
    }
}
```

### 4.2 Kotlin Coroutines Integration
`TenantCoroutineContextElement` ensures that whenever a coroutine suspends or resumes across dispatchers (`withContext(Dispatchers.IO)`), the tenant binding remains intact:

```kotlin
public class TenantCoroutineContextElement(
    private val binding: TenantBinding
) : ThreadContextElement<TenantBinding?>, AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<TenantCoroutineContextElement>

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

## 5. Security, Lifecycle & Admission

### 5.1 Operation Classes & Lifecycle Admission
Operations are categorized into:
- `ClassRead`: Read queries, projections, exports.
- `ClassWrite`: State mutations, event appends, inserts.
- `ClassDurable`: Background jobs, queue handlers, maintenance tasks.

```kotlin
public enum class TenantLifecycleState {
    RESERVED, PROVISIONING, ACTIVE, SUSPENDED, DELETING, DELETED, ARCHIVED
}

public class TenantAdmissionPolicy(
    private val admitted: Map<TenantOperationClass, Set<TenantLifecycleState>>
) {
    init {
        // Enforce formal invariant: write requires read
        val writeStates = admitted[TenantOperationClass.WRITE] ?: emptySet()
        val readStates = admitted[TenantOperationClass.READ] ?: emptySet()
        val invalidStates = writeStates - readStates
        require(invalidStates.isEmpty()) {
            "Invalid admission policy: states $invalidStates are admitted for WRITE but not for READ"
        }
    }

    public fun isAdmitted(operationClass: TenantOperationClass, state: TenantLifecycleState): Boolean =
        admitted[operationClass]?.contains(state) == true
}
```

### 5.2 Origin Environment Fencing
Every minted `TenantBinding` includes an `origin` string (e.g. `prod-eu-west-1`).
When `TenantAuthority.validate(binding)` runs:
```kotlin
if (binding.origin != properties.origin) {
    throw TenantOriginMismatchFault(
        "Presented tenant token originated from environment '${binding.origin}', " +
        "which does not match local environment '${properties.origin}'"
    )
}
```

### 5.3 Bounded Cross-Tenant Grants (Admin / Support Elevation)
Support personnel or background maintenance tasks requiring cross-tenant access must be explicitly granted and audited:
```kotlin
public fun <T> withCrossTenantGrant(
    targetTenant: TenantId,
    grant: CrossTenantGrant,
    block: () -> T
): T {
    auditRecorder.record(
        AuditEvent(
            type = "tenancy.cross_tenant_access",
            actor = SecurityContext.currentActor(),
            details = mapOf("target_tenant" to targetTenant.value, "reason" to grant.reason)
        )
    )
    return TenantContext.withTenant(TenantBinding(targetTenant, isElevated = true), block)
}
```

---

## 6. Durable Job Token Sealing (`rain-jobs` Integration)

To securely pass tenant context through `rain-jobs` without exposing raw tenant IDs to untrusted queue workers, `rain-tenancy` provides an HMAC-SHA256 token sealer:

```kotlin
public class TenantJobTokenSealer(
    private val currentKey: ByteArray,
    private val retiredKeys: List<ByteArray> = emptyList()
) {
    public fun seal(
        binding: TenantBinding,
        queue: String,
        definition: String,
        invocationId: UUID,
        payloadDigest: ByteArray
    ): String {
        val payload = buildTokenPayload(binding.tenantId, binding.epoch, queue, definition, invocationId, payloadDigest)
        val hmac = computeHmac(payload, currentKey)
        return "v1." + Base64.getUrlEncoder().encodeToString(payload) + "." + Base64.getUrlEncoder().encodeToString(hmac)
    }

    public fun unseal(
        token: String,
        queue: String,
        definition: String,
        invocationId: UUID,
        payloadDigest: ByteArray
    ): Pair<TenantId, Long> {
        val (payload, signature) = parseToken(token)
        val valid = verifyHmac(payload, signature, currentKey) || 
                    retiredKeys.any { verifyHmac(payload, signature, it) }
        if (!valid) throw TenantTokenForgedFault("Invalid job token signature")
        
        val record = decodePayload(payload)
        require(record.queue == queue && record.definition == definition && 
                record.invocationId == invocationId && record.payloadDigest.contentEquals(payloadDigest)) {
            "Job token does not match ambient invocation metadata"
        }
        return record.tenantId to record.epoch
    }
}
```

When a worker picks up the job:
1. It unseals the token and verifies the HMAC signature.
2. It asserts that the token matches the invocation and payload digest.
3. It asks the `TenantControlPlane` for the tenant's current lifecycle state. If the tenant was suspended while the job sat in the queue, the job is refused immediately without executing domain logic.

---

## 7. Rain Module Integrations

| Rain Module | Integration Hook |
|---|---|
| **`rain-web`** | `TenantPerimeterFilter` handles header/subdomain extraction, validates lifecycle admission, and binds `TenantContext`. |
| **`rain-crud`** | `TenantScopeRule` automatically narrows all CRUD queries; `CrudPlanProof` validates index boundedness per tenant. |
| **`rain-jobs`** | `TenantJobContextEnricher` automatically stamps sealed tokens into enqueued tasks; `TenantJobExecutionListener` restores context in workers. |
| **`rain-realtime`** | Channels and topics are automatically namespaced: `tenant:{tenant_id}:{topic}`. |
| **`rain-audit`** | `TenantAuditContributor` automatically adds `tenant_id` to every audit log record in `rain_audit`. |
| **`rain-access`** | Directory controllers, roles, and permissions are partitioned by tenant; prevents cross-tenant credential stuffing. |
