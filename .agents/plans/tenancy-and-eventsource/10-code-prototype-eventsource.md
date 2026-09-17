# Production Code Prototypes: `rain-eventsource`

**Language:** Kotlin 2.1+ / Java 25  
**Compilation Flags:** `-Xexplicit-api=strict`, `-Werror`, `-Xjspecify-annotations=strict`  
**Package:** `com.gd.rain.eventsource`

---

## 1. Domain Primitives & Stream Identity

### 1.1 `StreamId.kt`
```kotlin
package com.gd.rain.eventsource.domain

import java.io.Serializable

public data class StreamId(
    public val family: String,
    public val key: String
) : Serializable, Comparable<StreamId> {
    init {
        require(FAMILY_REGEX.matches(family)) {
            "Stream family must match ${FAMILY_REGEX.pattern} (1..64 chars), got '$family'"
        }
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) {
            "Stream key must be between 1 and $MAX_KEY_LENGTH characters, got length ${key.length}"
        }
    }

    public val qualifiedName: String get() = "$family:$key"

    override fun compareTo(other: StreamId): Int {
        val f = family.compareTo(other.family)
        return if (f != 0) f else key.compareTo(other.key)
    }

    override fun toString(): String = qualifiedName

    public companion object {
        public val FAMILY_REGEX: Regex = Regex("^[a-z][a-z0-9_-]{0,63}$")
        public const val MAX_KEY_LENGTH: Int = 512

        public fun parse(raw: String): StreamId {
            val colon = raw.indexOf(':')
            require(colon > 0 && colon < raw.length - 1) { "StreamId must follow format 'family:key', got '$raw'" }
            return StreamId(raw.substring(0, colon), raw.substring(colon + 1))
        }
    }
}
```

### 1.2 `EventEnvelope.kt`
```kotlin
package com.gd.rain.eventsource.domain

import java.time.Instant

public data class EventEnvelope(
    public val position: Long,
    public val stream: StreamId,
    public val version: Long,
    public val type: String,
    public val revision: Int,
    public val payload: ByteArray,
    public val metadata: Map<String, String>,
    public val recordedAt: Instant
) {
    init {
        require(position > 0) { "position must be positive, got $position" }
        require(version > 0) { "version must be positive, got $version" }
        require(revision > 0) { "revision must be positive, got $revision" }
    }
}
```

### 1.3 `AggregateRoot.kt`
```kotlin
package com.gd.rain.eventsource.domain

public abstract class AggregateRoot<S : Any>(
    public val streamId: StreamId
) {
    public var version: Long = 0L
        protected set

    private val uncommittedEvents: MutableList<UncommittedEvent> = mutableListOf()

    public abstract val state: S

    public fun uncommittedChanges(): List<UncommittedEvent> = uncommittedEvents.toList()

    public fun markChangesCommitted(newVersion: Long) {
        uncommittedEvents.clear()
        this.version = newVersion
    }

    protected fun applyChange(type: String, revision: Int, payload: ByteArray, isNew: Boolean = true) {
        foldEvent(type, revision, payload)
        if (isNew) {
            uncommittedEvents.add(UncommittedEvent(type, revision, payload))
        }
    }

    public fun loadFromHistory(events: List<EventEnvelope>) {
        for (event in events) {
            applyChange(event.type, event.revision, event.payload, isNew = false)
            this.version = event.version
        }
    }

    protected abstract fun foldEvent(type: String, revision: Int, payload: ByteArray)
}

public data class UncommittedEvent(
    public val type: String,
    public val revision: Int,
    public val payload: ByteArray
)
```

---

## 2. jOOQ Event Store Implementation

### 2.1 `JooqEventStore.kt`
```kotlin
package com.gd.rain.eventsource.store

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.eventsource.domain.EventEnvelope
import com.gd.rain.eventsource.domain.StreamId
import com.gd.rain.eventsource.domain.UncommittedEvent
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant

public class JooqEventStore(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    public fun append(
        stream: StreamId,
        expectedVersion: Long,
        changes: List<UncommittedEvent>
    ): AppendCommit {
        if (changes.isEmpty()) {
            return AppendCommit(stream, expectedVersion, expectedVersion, 0)
        }

        return dsl.transactionResult { config ->
            val txDsl = DSL.using(config)
            val now = Instant.now(clock)

            // 1. Optimistic Concurrency Check & Version Increment
            val newVersion = expectedVersion + changes.size
            val updated = if (expectedVersion == 0L) {
                txDsl.insertInto(DSL.table(DSL.name("rain_eventsource", "streams")))
                    .columns(
                        DSL.field(DSL.name("family"), String::class.java),
                        DSL.field(DSL.name("key"), String::class.java),
                        DSL.field(DSL.name("version"), Long::class.java),
                        DSL.field(DSL.name("updated_at"), Instant::class.java)
                    )
                    .values(stream.family, stream.key, newVersion, now)
                    .onConflictDoNothing()
                    .execute()
            } else {
                txDsl.update(DSL.table(DSL.name("rain_eventsource", "streams")))
                    .set(DSL.field(DSL.name("version"), Long::class.java), newVersion)
                    .set(DSL.field(DSL.name("updated_at"), Instant::class.java), now)
                    .where(
                        DSL.field(DSL.name("family"), String::class.java).eq(stream.family)
                            .and(DSL.field(DSL.name("key"), String::class.java).eq(stream.key))
                            .and(DSL.field(DSL.name("version"), Long::class.java).eq(expectedVersion))
                    )
                    .execute()
            }

            if (updated == 0) {
                throw Fault(
                    FaultKind.CONFLICT,
                    "concurrency_conflict",
                    "Optimistic concurrency conflict on stream '$stream': expected version $expectedVersion"
                )
            }

            // 2. Insert Events
            var currentVersion = expectedVersion
            val insert = txDsl.insertInto(
                DSL.table(DSL.name("rain_eventsource", "events")),
                DSL.field(DSL.name("family"), String::class.java),
                DSL.field(DSL.name("key"), String::class.java),
                DSL.field(DSL.name("version"), Long::class.java),
                DSL.field(DSL.name("type"), String::class.java),
                DSL.field(DSL.name("revision"), Int::class.java),
                DSL.field(DSL.name("payload"), ByteArray::class.java),
                DSL.field(DSL.name("recorded_at"), Instant::class.java)
            )

            for (change in changes) {
                currentVersion++
                insert.values(
                    stream.family,
                    stream.key,
                    currentVersion,
                    change.type,
                    change.revision,
                    change.payload,
                    now
                )
            }
            insert.execute()

            AppendCommit(stream, expectedVersion + 1, newVersion, changes.size)
        }
    }

    public fun loadStream(stream: StreamId, afterVersion: Long = 0L): List<EventEnvelope> {
        val records = dsl.selectFrom(DSL.table(DSL.name("rain_eventsource", "events")))
            .where(
                DSL.field(DSL.name("family"), String::class.java).eq(stream.family)
                    .and(DSL.field(DSL.name("key"), String::class.java).eq(stream.key))
                    .and(DSL.field(DSL.name("version"), Long::class.java).gt(afterVersion))
            )
            .orderBy(DSL.field(DSL.name("version")).asc())
            .fetch()

        return records.map { r ->
            EventEnvelope(
                position = r.get("position", Long::class.java),
                stream = stream,
                version = r.get("version", Long::class.java),
                type = r.get("type", String::class.java),
                revision = r.get("revision", Int::class.java),
                payload = r.get("payload", ByteArray::class.java),
                metadata = emptyMap(),
                recordedAt = r.get("recorded_at", Instant::class.java)
            )
        }
    }
}

public data class AppendCommit(
    public val stream: StreamId,
    public val firstVersion: Long,
    public val lastVersion: Long,
    public val count: Int
)
```

---

## 3. Stream Parking Engine (`ParkedEventStore`)

### 3.1 `ParkedEventStore.kt`
```kotlin
package com.gd.rain.eventsource.park

import com.gd.rain.eventsource.domain.EventEnvelope
import com.gd.rain.eventsource.domain.StreamId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Duration
import java.time.Instant
import java.util.UUID

public class ParkedEventStore(private val dsl: DSLContext) {

    public fun holds(projection: String, sequence: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(DSL.table(DSL.name("rain_eventsource", "parked_events")))
                .where(
                    DSL.field(DSL.name("projection"), String::class.java).eq(projection)
                        .and(DSL.field(DSL.name("sequence"), String::class.java).eq(sequence))
                        .and(DSL.field(DSL.name("status"), String::class.java).eq("PARKED"))
                )
        )
    }

    public fun park(
        projection: String,
        sequence: String,
        envelope: EventEnvelope,
        cause: Throwable?
    ) {
        val causeClass = cause?.javaClass?.name ?: "StreamPreQuarantined"
        val causeMsg = cause?.message ?: "Parked behind previously failed event"

        dsl.insertInto(DSL.table(DSL.name("rain_eventsource", "parked_events")))
            .columns(
                DSL.field(DSL.name("id"), UUID::class.java),
                DSL.field(DSL.name("projection"), String::class.java),
                DSL.field(DSL.name("sequencer"), String::class.java),
                DSL.field(DSL.name("sequence"), String::class.java),
                DSL.field(DSL.name("position"), Long::class.java),
                DSL.field(DSL.name("family"), String::class.java),
                DSL.field(DSL.name("key"), String::class.java),
                DSL.field(DSL.name("version"), Long::class.java),
                DSL.field(DSL.name("type"), String::class.java),
                DSL.field(DSL.name("revision"), Int::class.java),
                DSL.field(DSL.name("payload"), ByteArray::class.java),
                DSL.field(DSL.name("cause_class"), String::class.java),
                DSL.field(DSL.name("cause_message"), String::class.java),
                DSL.field(DSL.name("attempt"), Int::class.java),
                DSL.field(DSL.name("status"), String::class.java)
            )
            .values(
                UUID.randomUUID(),
                projection,
                "ByStream",
                sequence,
                envelope.position,
                envelope.stream.family,
                envelope.stream.key,
                envelope.version,
                envelope.type,
                envelope.revision,
                envelope.payload,
                causeClass,
                causeMsg,
                1,
                "PARKED"
            )
            .onConflictDoNothing()
            .execute()
    }

    public fun holes(projection: String): Long {
        return dsl.selectCount()
            .from(DSL.table(DSL.name("rain_eventsource", "parked_events")))
            .where(
                DSL.field(DSL.name("projection"), String::class.java).eq(projection)
                    .and(DSL.field(DSL.name("status"), String::class.java).eq("PARKED"))
            )
            .fetchOne(0, Long::class.java) ?: 0L
    }

    public fun claim(projection: String, sequence: String, leaseDuration: Duration): ParkClaim? {
        val token = UUID.randomUUID().toString()
        val until = Instant.now().plus(leaseDuration)

        val updated = dsl.update(DSL.table(DSL.name("rain_eventsource", "parked_events")))
            .set(DSL.field(DSL.name("claimed_by"), String::class.java), token)
            .set(DSL.field(DSL.name("claimed_until"), Instant::class.java), until)
            .where(
                DSL.field(DSL.name("projection"), String::class.java).eq(projection)
                    .and(DSL.field(DSL.name("sequence"), String::class.java).eq(sequence))
                    .and(DSL.field(DSL.name("status"), String::class.java).eq("PARKED"))
                    .and(
                        DSL.field(DSL.name("claimed_until"), Instant::class.java).isNull
                            .or(DSL.field(DSL.name("claimed_until"), Instant::class.java).lt(Instant.now()))
                    )
            )
            .execute()

        return if (updated > 0) ParkClaim(projection, sequence, token, until) else null
    }

    public fun evict(claim: ParkClaim, letterId: UUID) {
        dsl.deleteFrom(DSL.table(DSL.name("rain_eventsource", "parked_events")))
            .where(
                DSL.field(DSL.name("id"), UUID::class.java).eq(letterId)
                    .and(DSL.field(DSL.name("claimed_by"), String::class.java).eq(claim.token))
            )
            .execute()
    }
}

public data class ParkClaim(
    public val projection: String,
    public val sequence: String,
    public val token: String,
    public val expiresAt: Instant
)
```

---

## 4. Idempotency Receipts (`receipt.Once`)

### 4.1 `JooqReceiptLedger.kt`
```kotlin
package com.gd.rain.eventsource.receipt

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.eventsource.domain.StreamId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

public class JooqReceiptLedger(private val dsl: DSLContext) {

    public fun claim(key: String, fingerprint: String, stream: StreamId): ReceiptClaimResult {
        // Statement 1: Speculative insertion (blocks concurrent writers)
        val affected = dsl.insertInto(DSL.table(DSL.name("rain_eventsource", "receipts")))
            .columns(
                DSL.field(DSL.name("key"), String::class.java),
                DSL.field(DSL.name("fingerprint"), String::class.java),
                DSL.field(DSL.name("family"), String::class.java),
                DSL.field(DSL.name("stream_key"), String::class.java)
            )
            .values(key, fingerprint, stream.family, stream.key)
            .onConflictDoNothing()
            .execute()

        // Statement 2: Read outcome in fresh snapshot
        val row = dsl.selectFrom(DSL.table(DSL.name("rain_eventsource", "receipts")))
            .where(DSL.field(DSL.name("key"), String::class.java).eq(key))
            .fetchOne() ?: throw Fault(FaultKind.INTERNAL, "receipt_missing", "Receipt missing immediately after claim")

        val complete = row.get("complete", Boolean::class.java)
        val rowFingerprint = row.get("fingerprint", String::class.java)

        return when {
            affected == 1 -> ReceiptClaimResult.Recorded(key)
            complete && rowFingerprint == fingerprint -> {
                val first = row.get("first_version", Long::class.java)
                val last = row.get("last_version", Long::class.java)
                ReceiptClaimResult.Repeated(key, first, last)
            }
            rowFingerprint != fingerprint -> {
                throw Fault(
                    FaultKind.CONFLICT,
                    "idempotency_conflict",
                    "Idempotency key '$key' was already spent with a different payload"
                )
            }
            else -> {
                throw Fault(
                    FaultKind.CONFLICT,
                    "idempotency_in_flight",
                    "A command with idempotency key '$key' is currently in flight"
                )
            }
        }
    }

    public fun complete(key: String, firstVersion: Long, lastVersion: Long) {
        dsl.update(DSL.table(DSL.name("rain_eventsource", "receipts")))
            .set(DSL.field(DSL.name("first_version"), Long::class.java), firstVersion)
            .set(DSL.field(DSL.name("last_version"), Long::class.java), lastVersion)
            .set(DSL.field(DSL.name("complete"), Boolean::class.java), true)
            .where(DSL.field(DSL.name("key"), String::class.java).eq(key))
            .execute()
    }
}

public sealed interface ReceiptClaimResult {
    public data class Recorded(public val key: String) : ReceiptClaimResult
    public data class Repeated(public val key: String, public val firstVersion: Long, public val lastVersion: Long) : ReceiptClaimResult
}
```
