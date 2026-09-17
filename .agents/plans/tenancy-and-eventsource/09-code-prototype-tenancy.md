# Production Code Prototypes: `rain-tenancy`

**Language:** Kotlin 2.1+ / Java 25  
**Compilation Flags:** `-Xexplicit-api=strict`, `-Werror`, `-Xjspecify-annotations=strict`  
**Package:** `com.gd.rain.tenancy`

---

## 1. Domain Primitives & Scope Engine

### 1.1 `TenantId.kt`
```kotlin
package com.gd.rain.tenancy

import java.io.Serializable

@JvmInline
public value class TenantId(public val value: String) : Serializable, Comparable<TenantId> {
    init {
        require(SLUG_REGEX.matches(value)) {
            "TenantId must match ${SLUG_REGEX.pattern} (1..63 lowercase alphanumeric with hyphens), got '$value'"
        }
    }

    public companion object {
        public val SLUG_REGEX: Regex = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

        public fun ofOrNull(raw: String?): TenantId? =
            if (raw != null && SLUG_REGEX.matches(raw)) TenantId(raw) else null
    }

    override fun compareTo(other: TenantId): Int = value.compareTo(other.value)
    override fun toString(): String = value
}
```

### 1.2 `TenantBinding.kt`
```kotlin
package com.gd.rain.tenancy

import java.io.Serializable

public data class TenantBinding(
    public val tenantId: TenantId,
    public val lifecycle: TenantLifecycleState = TenantLifecycleState.ACTIVE,
    public val epoch: Long = 1L,
    public val origin: String = "",
    public val operationClass: TenantOperationClass = TenantOperationClass.READ,
    public val isSystem: Boolean = false
) : Serializable {
    init {
        require(epoch > 0) { "epoch must be positive, got $epoch" }
    }
}

public enum class TenantLifecycleState {
    RESERVED,
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    DELETING,
    DELETED,
    ARCHIVED
}

public enum class TenantOperationClass {
    READ,
    WRITE,
    DURABLE
}
```

### 1.3 `TenantContext.kt` (Dual-Carrier: Loom `ScopedValue` + Coroutines + `ThreadLocal`)
```kotlin
package com.gd.rain.tenancy

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
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

    public fun isBound(): Boolean = currentBinding() != null

    public fun require(): TenantId =
        currentOrNull() ?: throw Fault(
            FaultKind.UNAUTHORIZED,
            TenancyErrorCodes.TENANT_REQUIRED,
            "Operation requires a bound tenant context, but none was provided"
        )

    public fun isSystemElevated(): Boolean = currentBinding()?.isSystem == true

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

    public fun <T> withTenant(tenantId: TenantId, block: () -> T): T =
        withTenant(TenantBinding(tenantId), block)

    public fun <T> withSystemElevation(block: () -> T): T {
        val current = currentBinding() ?: TenantBinding(TenantId("system"), isSystem = true)
        val elevated = current.copy(isSystem = true)
        return withTenant(elevated, block)
    }

    internal fun setThreadLocal(binding: TenantBinding?) {
        if (binding == null) {
            THREAD_LOCAL_BINDING.remove()
        } else {
            THREAD_LOCAL_BINDING.set(binding)
        }
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

## 2. Security & Admission Whitelist

### 2.1 `TenantAdmissionPolicy.kt`
```kotlin
package com.gd.rain.tenancy

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind

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

    public fun assertAdmitted(operationClass: TenantOperationClass, state: TenantLifecycleState) {
        val admittedStates = admitted[operationClass] ?: emptySet()
        if (!admittedStates.contains(state)) {
            throw Fault(
                FaultKind.FORBIDDEN,
                TenancyErrorCodes.TENANT_INACTIVE,
                "Tenant lifecycle state '$state' is not admitted for operation class '$operationClass'"
            )
        }
    }

    public companion object {
        public val DEFAULT: TenantAdmissionPolicy = TenantAdmissionPolicy(
            mapOf(
                TenantOperationClass.READ to setOf(TenantLifecycleState.ACTIVE, TenantLifecycleState.SUSPENDED),
                TenantOperationClass.WRITE to setOf(TenantLifecycleState.ACTIVE),
                TenantOperationClass.DURABLE to setOf(TenantLifecycleState.ACTIVE, TenantLifecycleState.SUSPENDED)
            )
        )
    }
}
```

---

## 3. Durable Job Token Sealer (`rain-jobs` Hook)

### 3.1 `TenantJobTokenSealer.kt`
```kotlin
package com.gd.rain.tenancy

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public class TenantJobTokenSealer(
    private val currentKey: ByteArray,
    private val retiredKeys: List<ByteArray> = emptyList()
) {
    init {
        require(currentKey.size >= 32) { "currentKey must be at least 32 bytes (256 bits) for HMAC-SHA256" }
    }

    public fun seal(
        binding: TenantBinding,
        queue: String,
        definition: String,
        invocationId: UUID,
        payloadDigest: ByteArray
    ): String {
        val rawData = buildPayloadBytes(binding.tenantId, binding.epoch, queue, definition, invocationId, payloadDigest)
        val mac = computeMac(rawData, currentKey)
        val b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(rawData)
        val b64Mac = Base64.getUrlEncoder().withoutPadding().encodeToString(mac)
        return "v1.$b64Payload.$b64Mac"
    }

    public fun unseal(
        token: String,
        queue: String,
        definition: String,
        invocationId: UUID,
        payloadDigest: ByteArray
    ): Pair<TenantId, Long> {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != "v1") {
            throw Fault(FaultKind.UNAUTHORIZED, TenancyErrorCodes.TOKEN_MALFORMED, "Invalid job token format")
        }

        val rawData = Base64.getUrlDecoder().decode(parts[1])
        val presentedMac = Base64.getUrlDecoder().decode(parts[2])

        val valid = MessageDigest.isEqual(computeMac(rawData, currentKey), presentedMac) ||
            retiredKeys.any { MessageDigest.isEqual(computeMac(rawData, it), presentedMac) }

        if (!valid) {
            throw Fault(FaultKind.FORBIDDEN, TenancyErrorCodes.TOKEN_FORGED, "Durable job token signature is invalid")
        }

        val buffer = ByteBuffer.wrap(rawData)
        val epoch = buffer.long
        val tenantLen = buffer.int
        val tenantBytes = ByteArray(tenantLen).also { buffer.get(it) }
        val tenantId = TenantId(String(tenantBytes, Charsets.UTF_8))

        val queueLen = buffer.int
        val queueBytes = ByteArray(queueLen).also { buffer.get(it) }
        val tokenQueue = String(queueBytes, Charsets.UTF_8)

        val defLen = buffer.int
        val defBytes = ByteArray(defLen).also { buffer.get(it) }
        val tokenDef = String(defBytes, Charsets.UTF_8)

        val mostSig = buffer.long
        val leastSig = buffer.long
        val tokenInvocationId = UUID(mostSig, leastSig)

        val tokenPayloadDigest = ByteArray(32).also { buffer.get(it) }

        if (tokenQueue != queue || tokenDef != definition ||
            tokenInvocationId != invocationId || !tokenPayloadDigest.contentEquals(payloadDigest)) {
            throw Fault(FaultKind.FORBIDDEN, TenancyErrorCodes.TOKEN_MISMATCH, "Job token parameters do not match execution invocation")
        }

        return tenantId to epoch
    }

    private fun computeMac(data: ByteArray, key: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key, "HmacSHA256"))
        return hmac.doFinal(data)
    }

    private fun buildPayloadBytes(
        tenant: TenantId,
        epoch: Long,
        queue: String,
        def: String,
        id: UUID,
        payloadDigest: ByteArray
    ): ByteArray {
        val tenantBytes = tenant.value.toByteArray(Charsets.UTF_8)
        val queueBytes = queue.toByteArray(Charsets.UTF_8)
        val defBytes = def.toByteArray(Charsets.UTF_8)

        val totalSize = 8 + 4 + tenantBytes.size + 4 + queueBytes.size + 4 + defBytes.size + 16 + 32
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putLong(epoch)
        buffer.putInt(tenantBytes.size)
        buffer.put(tenantBytes)
        buffer.putInt(queueBytes.size)
        buffer.put(queueBytes)
        buffer.putInt(defBytes.size)
        buffer.put(defBytes)
        buffer.putLong(id.mostSignificantBits)
        buffer.putLong(id.leastSignificantBits)
        buffer.put(payloadDigest)
        return buffer.array()
    }
}
```

---

## 4. Multi-Tenant DataSource & Hikari Pool Cache

### 4.1 `TenantPoolCache.kt`
```kotlin
package com.gd.rain.tenancy.routing

import com.gd.rain.tenancy.TenantId
import com.github.benmanes.caffeine.cache.Caffeine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

public class TenantPoolCache(
    private val maxActivePools: Int = 128,
    private val idleEvictDuration: Duration = Duration.ofMinutes(30),
    private val dataSourceFactory: (TenantId) -> HikariConfig
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TenantPoolCache::class.java)

    private val poolCache = Caffeine.newBuilder()
        .maximumSize(maxActivePools.toLong())
        .expireAfterAccess(idleEvictDuration)
        .removalListener<TenantId, HikariDataSource> { tenant, pool, cause ->
            log.info("Evicting connection pool for tenant '$tenant' (cause: $cause)")
            pool?.close()
        }
        .build<TenantId, HikariDataSource> { tenant ->
            log.info("Creating new HikariCP pool for tenant '$tenant'")
            val config = dataSourceFactory(tenant)
            HikariDataSource(config)
        }

    public fun getDataSource(tenant: TenantId): DataSource = poolCache.get(tenant)

    public fun activePoolCount(): Int = poolCache.estimatedSize().toInt()

    override fun close() {
        log.info("Shutting down TenantPoolCache; closing all active pools")
        poolCache.invalidateAll()
    }
}
```

### 4.2 `TenantRoutingDataSource.kt`
```kotlin
package com.gd.rain.tenancy.routing

import com.gd.rain.tenancy.TenantContext
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import javax.sql.DataSource

public class TenantRoutingDataSource(
    private val poolCache: TenantPoolCache,
    private val defaultDataSource: DataSource
) : AbstractRoutingDataSource() {

    init {
        setDefaultTargetDataSource(defaultDataSource)
    }

    override fun determineCurrentLookupKey(): Any? = TenantContext.currentOrNull()

    override fun determineTargetDataSource(): DataSource {
        val tenantId = TenantContext.currentOrNull() ?: return defaultDataSource
        return poolCache.getDataSource(tenantId)
    }
}
```

---

## 5. jOOQ Row-Level Narrowing (`TenantQueryNarrower`)

### 5.1 `TenantQueryNarrower.kt`
```kotlin
package com.gd.rain.tenancy.jooq

import com.gd.rain.tenancy.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.RecordContext
import org.jooq.RecordListener
import org.jooq.Table
import org.jooq.impl.DSL

public class TenantQueryNarrower(
    private val tenantColumnName: String = "tenant_id"
) : RecordListener {

    override fun insertStart(ctx: RecordContext) {
        val record = ctx.record() ?: return
        val field = record.field(tenantColumnName) ?: return

        val currentTenant = TenantContext.require()
        val existingValue = record.get(field)

        if (existingValue == null) {
            // Automatically stamp tenant ID
            record.set(field as org.jooq.Field<Any>, currentTenant.value)
        } else if (existingValue.toString() != currentTenant.value) {
            throw SecurityException(
                "Cross-tenant write rejected: attempted to insert tenant '${existingValue}' " +
                "while active context is '${currentTenant.value}'"
            )
        }
    }
}
```
