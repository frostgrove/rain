package com.gd.rain.event

import com.gd.rain.persistence.tx.BackingIdentity
import java.security.MessageDigest
import java.util.UUID

/** A missing stream is deliberately distinct from an aggregate in its initial state. */
public sealed interface StreamLoad<S : Any> {
    public data class Missing<S : Any>(
        public val create: CreateToken<S>,
    ) : StreamLoad<S>

    public data class Existing<S : Any>(
        public val state: S,
        public val append: AppendToken<S>,
    ) : StreamLoad<S>
}

/** A state read at history never carries a capability to append. */
public data class HistoricalState<S : Any>(
    public val state: S,
    public val version: Long,
)

/** A typed stream page decoded only through the declared [FactSpec]s. */
public data class TypedStreamPage(
    public val events: List<TypedStoredEvent>,
    public val afterVersion: Long,
    public val hasMore: Boolean,
)

/** A stored fact paired with its declared Kotlin value. */
public data class TypedStoredEvent(
    public val envelope: StoredEvent,
    public val value: Any,
)

/** A capability to create a stream at expected version zero. */
public class CreateToken<S : Any> internal constructor(
    internal val binding: TokenBinding,
) {
    /** Converts this missing-stream capability into an append capability; it remains unit-bound. */
    public fun appendToken(): AppendToken<S> = AppendToken(binding)

    override fun toString(): String = "create-token[opaque]"
}

/** A capability to append only to the stream/version and transaction unit from which it was loaded. */
public class AppendToken<S : Any> internal constructor(
    internal val binding: TokenBinding,
) {
    /** The optimistic version this capability expects. It is informative; the store enforces it. */
    public val expectedVersion: Long get() = binding.expectedVersion

    override fun toString(): String = "append-token[opaque]"
}

/**
 * Explicit typed facade over an [EventStore]. It contains no command handler, retry loop, or
 * reflection discovery. A service loads, makes its pure decision, converts it with [changes], and
 * then calls [append] in the same caller-owned unit.
 */
public class EventRepository<S : Any, ID : Any>(
    private val aggregate: AggregateSpec<S, ID>,
    private val catalogue: EventCatalogue,
    private val store: EventStore,
    private val snapshots: EventSnapshotStore? = store as? EventSnapshotStore,
    private val snapshot: SnapshotSpec<S>? = null,
    private val snapshotObserver: SnapshotObserver = SnapshotObserver.NONE,
) {
    init {
        require(catalogue.aggregate(aggregate.family) === aggregate) {
            "the repository aggregate is not the catalogue declaration"
        }
        require(snapshot == null || snapshots != null) { "an aggregate snapshot declaration requires an event snapshot store" }
    }

    /** Loads an existing aggregate or returns an explicit creation capability. */
    public fun load(
        transaction: EventTransaction,
        namespace: EventNamespace,
        id: ID,
    ): StreamLoad<S> {
        val stream = stream(namespace, id)
        val version = store.version(transaction, stream)
        if (version == null) return StreamLoad.Missing(CreateToken(binding(transaction, stream, 0)))
        val state = replay(transaction, id, stream, version)
        return StreamLoad.Existing(state, AppendToken(binding(transaction, stream, version)))
    }

    /** Rebuilds a historical version without producing an append capability. */
    public fun stateAt(
        transaction: EventTransaction,
        namespace: EventNamespace,
        id: ID,
        version: Long,
    ): HistoricalState<S> {
        require(version >= 0) { "historical version is not negative" }
        val stream = stream(namespace, id)
        val actual = store.version(transaction, stream) ?: throw EventRefusal.VersionNotFound
        if (version > actual) throw EventRefusal.VersionNotFound
        if (version == 0L) return HistoricalState(aggregate.initial(id), 0L)
        return HistoricalState(replay(transaction, id, stream, version), version)
    }

    /** Reads one bounded typed page; a caller must provide a positive bounded [limit]. */
    public fun readStream(
        transaction: EventTransaction,
        namespace: EventNamespace,
        id: ID,
        afterVersion: Long,
        limit: Int,
    ): TypedStreamPage {
        require(afterVersion >= 0) { "after version is not negative" }
        require(limit in 1..store.limits.streamPageSize) { "stream page limit is outside the configured bound" }
        val page = store.readStream(transaction, stream(namespace, id), afterVersion, limit)
        return TypedStreamPage(page.events.map { event -> TypedStoredEvent(event, decode(event)) }, page.afterVersion, page.hasMore)
    }

    /**
     * Encodes facts through their declared specs. An empty batch is valid, but its token is still
     * checked by [append], so a foreign or stale token is never silently accepted.
     */
    public fun changes(
        token: AppendToken<S>,
        facts: List<Any>,
    ): EncodedChanges {
        val binding = token.binding
        require(facts.size <= store.limits.batchSize) { "event batch exceeds the configured bound" }
        return EncodedChanges(binding.stream, facts.map(::encode))
    }

    /** Performs no retry and returns a confirmed conflict separately from an uncertain backend failure. */
    public fun append(
        transaction: EventTransaction,
        token: AppendToken<S>,
        changes: EncodedChanges,
        metadata: EventMetadata,
    ): AppendResult {
        val binding = token.binding
        check(binding.storeId == transaction.storeId && binding.nonce == transaction.nonce) { "append token is from another unit" }
        check(binding.backing == transaction.backing && binding.backing == store.backing) { "append token is from another backing" }
        require(changes.stream == binding.stream) { "encoded changes target another stream" }
        require(metadata.canonicalBytes().size <= store.limits.metadataBytes) { "metadata exceeds the configured bound" }
        return store.append(transaction, binding.expectedVersion, changes, metadata)
    }

    /**
     * Rebuilds state from source records and writes a disposable optimization at the current
     * version. A caller may invoke it in the append transaction or a later transaction; it never
     * changes append success and it cannot snapshot an absent stream.
     */
    public fun snapshot(
        transaction: EventTransaction,
        namespace: EventNamespace,
        id: ID,
    ): SnapshotSaveResult {
        val declaration = checkNotNull(snapshot) { "this aggregate has no snapshot declaration" }
        val snapshotStore = checkNotNull(snapshots) { "this event store has no snapshot extension" }
        val stream = stream(namespace, id)
        val version = store.version(transaction, stream) ?: throw EventRefusal.VersionNotFound
        val state = replayFrom(transaction, id, stream, version, aggregate.initial(id), 0)
        return snapshotStore.save(
            transaction,
            SnapshotWrite(stream, version, declaration.fingerprint, declaration.codec.id, declaration.codec.encode(state)),
        )
    }

    /** Stable digest of the immutable append contents, excluding expected version and database time. */
    public fun digest(
        token: AppendToken<S>,
        changes: EncodedChanges,
        metadata: EventMetadata,
    ): AppendFingerprint {
        val binding = token.binding
        require(changes.stream == binding.stream) { "encoded changes target another stream" }
        return appendFingerprint(binding.stream, changes.facts, metadata)
    }

    private fun stream(
        namespace: EventNamespace,
        id: ID,
    ): StreamRef = StreamRef(namespace, aggregate.family, aggregate.streamKey(id))

    private fun binding(
        transaction: EventTransaction,
        stream: StreamRef,
        expectedVersion: Long,
    ): TokenBinding {
        check(transaction.backing == store.backing) { "transaction is from another backing" }
        return TokenBinding(transaction.storeId, transaction.backing, transaction.nonce, stream, expectedVersion)
    }

    private fun fold(
        id: ID,
        events: List<StoredEvent>,
    ): S = events.fold(aggregate.initial(id)) { state, event -> aggregate.fold(state, decode(event)) }

    private fun replay(
        transaction: EventTransaction,
        id: ID,
        stream: StreamRef,
        targetVersion: Long,
    ): S {
        val seed = snapshotSeed(transaction, id, stream, targetVersion)
        return replayFrom(transaction, id, stream, targetVersion, seed.state, seed.version)
    }

    private fun replayFrom(
        transaction: EventTransaction,
        id: ID,
        stream: StreamRef,
        targetVersion: Long,
        initial: S,
        initialVersion: Long,
    ): S {
        var state = initial
        var after = initialVersion
        while (after < targetVersion) {
            val remaining = targetVersion - after
            val limit = minOf(store.limits.streamPageSize.toLong(), remaining).toInt()
            val page = store.readStream(transaction, stream, after, limit)
            if (page.events.isEmpty() || page.events.first().streamVersion != after + 1) throw EventRefusal.VersionNotFound
            page.events.forEach { event -> state = aggregate.fold(state, decode(event)) }
            after = page.events.last().streamVersion
            if (page.events.size < limit && after < targetVersion) throw EventRefusal.VersionNotFound
        }
        return state
    }

    private fun snapshotSeed(
        transaction: EventTransaction,
        id: ID,
        stream: StreamRef,
        targetVersion: Long,
    ): SnapshotSeed<S> {
        val declaration = snapshot ?: return SnapshotSeed(aggregate.initial(id), 0)
        val candidate = snapshots?.latest(transaction, stream, targetVersion) ?: return SnapshotSeed(aggregate.initial(id), 0)
        if (!candidate.checksumIsValid()) return ignored(SnapshotIgnoredReason.CHECKSUM, id)
        if (candidate.fingerprint != declaration.fingerprint) return ignored(SnapshotIgnoredReason.FINGERPRINT, id)
        if (candidate.codec != declaration.codec.id) return ignored(SnapshotIgnoredReason.CODEC, id)
        if (candidate.version !in 1..targetVersion) return ignored(SnapshotIgnoredReason.VERSION, id)
        return try {
            SnapshotSeed(declaration.codec.decode(candidate.payload), candidate.version)
        } catch (_: Exception) {
            ignored(SnapshotIgnoredReason.DECODE, id)
        }
    }

    private fun ignored(
        reason: SnapshotIgnoredReason,
        id: ID,
    ): SnapshotSeed<S> {
        snapshotObserver.ignored(reason)
        return SnapshotSeed(aggregate.initial(id), 0)
    }

    private fun decode(event: StoredEvent): Any {
        val fact = catalogue.fact(aggregate.family, event.fact.name)
        check(event.fact.revision in fact.readableRevisions) { "stored fact revision is not readable" }
        return fact.read(event.fact.revision, event.payload)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(value: Any): EncodedFact {
        val fact =
            aggregate.facts.singleOrNull { it.kotlinType.isInstance(value) }
                ?: throw IllegalArgumentException("a decision emitted an undeclared fact type")
        val typed = fact as FactSpec<Any>
        val payload = typed.write(value)
        require(payload.size <= store.limits.payloadBytes) { "fact payload exceeds the configured bound" }
        return EncodedFact(FactType(typed.type, typed.writeRevision), payload)
    }

    private data class SnapshotSeed<S : Any>(
        val state: S,
        val version: Long,
    )
}

/** One canonical append fingerprint algorithm, shared by the repository, receipts, and explicit low-level services. */
public fun appendFingerprint(
    stream: StreamRef,
    facts: List<EncodedFact>,
    metadata: EventMetadata,
): AppendFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("rain.event.append.v1".toByteArray(Charsets.UTF_8))
    digest.update(stream.namespace.copy())
    digest.update(stream.family.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(stream.key.toByteArray(Charsets.UTF_8))
    facts.forEach { fact ->
        digest.update(0)
        digest.update(fact.type.name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(
            fact.type.revision
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
        digest.update(0)
        digest.update(fact.payload.copy())
    }
    digest.update(0)
    digest.update(metadata.canonicalBytes().copy())
    return AppendFingerprint(EventBytes.of(digest.digest()))
}

/** A digest of exact append content, suitable for a durable receipt row but not for public display. */
public class AppendFingerprint internal constructor(
    private val value: EventBytes,
) {
    public fun copy(): EventBytes = EventBytes.of(value.copy())

    override fun equals(other: Any?): Boolean = other is AppendFingerprint && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "append-fingerprint[redacted]"

    public companion object {
        /** Builds an already calculated SHA-256 append fingerprint for a durable receipt adapter. */
        public fun of(value: EventBytes): AppendFingerprint {
            require(
                value.size == RequestFingerprint.BYTES,
            ) { "an append fingerprint has ${value.size} bytes, not ${RequestFingerprint.BYTES}" }
            return AppendFingerprint(EventBytes.of(value.copy()))
        }
    }
}

internal data class TokenBinding(
    val storeId: UUID,
    val backing: BackingIdentity,
    val nonce: UUID,
    val stream: StreamRef,
    val expectedVersion: Long,
)

/** Closed kernel refusals that adapters translate to their registered public fault codes. */
public sealed class EventRefusal(
    message: String,
) : IllegalStateException(message) {
    public data object VersionNotFound : EventRefusal("event version was not found")
}
