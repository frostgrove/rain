package com.gd.rain.event.test

import com.gd.rain.event.AggregateSpec
import com.gd.rain.event.AppendResult
import com.gd.rain.event.CommitRange
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventCatalogue
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.EventRepository
import com.gd.rain.event.FactSpec
import com.gd.rain.event.OperationKey
import com.gd.rain.event.ReceiptClaim
import com.gd.rain.event.ReceiptCompletion
import com.gd.rain.event.ReceiptRefusal
import com.gd.rain.event.RequestFingerprint
import com.gd.rain.event.SnapshotCodec
import com.gd.rain.event.SnapshotCodecId
import com.gd.rain.event.SnapshotFingerprint
import com.gd.rain.event.SnapshotIgnoredReason
import com.gd.rain.event.SnapshotObserver
import com.gd.rain.event.SnapshotSaveResult
import com.gd.rain.event.SnapshotSpec
import com.gd.rain.event.StreamLoad
import com.gd.rain.event.appendFingerprint
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.KClass

class InMemoryEventStoreTest {
    private val store = InMemoryEventStore(clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")))
    private val repository = EventRepository(Counter, EventCatalogue(setOf(Counter)), store)
    private val namespace = EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 7 })

    @Test
    fun `an append is atomic dense and replayed only through finite pages`() {
        store.inCallerTransaction { transaction ->
            val missing = repository.load(transaction, namespace, "counter") as StreamLoad.Missing
            val changes = repository.changes(missing.create.appendToken(), listOf(Incremented(1), Incremented(2)))

            assertThat(repository.append(transaction, missing.create.appendToken(), changes, metadata("one")))
                .isEqualTo(
                    AppendResult.Committed(
                        com.gd.rain.event
                            .CommitRange(1, 2, 1, 2, 2),
                    ),
                )
        }

        store.inCallerTransaction { transaction ->
            val loaded = repository.load(transaction, namespace, "counter") as StreamLoad.Existing

            assertThat(loaded.state).isEqualTo(3)
            assertThat(repository.stateAt(transaction, namespace, "counter", 1).state).isEqualTo(1)
        }
    }

    @Test
    fun `a token cannot cross virtual transaction units`() {
        val token =
            store.inCallerTransaction { transaction ->
                (repository.load(transaction, namespace, "new-counter") as StreamLoad.Missing).create.appendToken()
            }

        store.inCallerTransaction { transaction ->
            val changes = repository.changes(token, listOf(Incremented(1)))

            assertThatThrownBy { repository.append(transaction, token, changes, metadata("two")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("append token is from another unit")
        }
    }

    @Test
    fun `receipt claim commits atomically and repetitions never rerun the decision`() {
        val operation = OperationKey.of("receipt")
        val request = RequestFingerprint.of(EventBytes.utf8("command"))
        val stream =
            com.gd.rain.event
                .StreamRef(namespace, "counter", "receipt")
        val metadata = EventMetadata(operation)
        val completed =
            store.inCallerTransaction { transaction ->
                val claimed = store.claim(transaction, namespace, operation, request) as ReceiptClaim.Claimed
                store.complete(
                    transaction,
                    claimed.token,
                    ReceiptCompletion(stream, CommitRange.EMPTY, appendFingerprint(stream, emptyList(), metadata), metadata),
                )
            }

        val repeated =
            store.inCallerTransaction { transaction ->
                store.claim(transaction, namespace, operation, request)
            }

        assertThat(repeated).isEqualTo(ReceiptClaim.Repeated(completed))
        assertThatThrownBy {
            store.inCallerTransaction { transaction ->
                store.claim(transaction, namespace, operation, RequestFingerprint.of(EventBytes.utf8("another-command")))
            }
        }.isInstanceOf(ReceiptRefusal.Collision::class.java)
    }

    @Test
    fun `snapshot is a disposable replay optimization and historical reads before it still fold source events`() {
        val snapshotRepository =
            EventRepository(
                Counter,
                EventCatalogue(
                    setOf(Counter),
                ),
                store,
                snapshot =
                    SnapshotSpec(
                        IntSnapshot,
                        SnapshotFingerprint.of(
                            ByteArray(32) {
                                1
                            },
                        ),
                    ),
            )
        store.inCallerTransaction { transaction ->
            val missing = snapshotRepository.load(transaction, namespace, "snapshot") as StreamLoad.Missing
            val changes = snapshotRepository.changes(missing.create.appendToken(), listOf(Incremented(1), Incremented(2)))
            snapshotRepository.append(transaction, missing.create.appendToken(), changes, metadata("snapshot-one"))
            assertThat(snapshotRepository.snapshot(transaction, namespace, "snapshot")).isInstanceOf(SnapshotSaveResult.Saved::class.java)
        }
        store.inCallerTransaction { transaction ->
            val existing = snapshotRepository.load(transaction, namespace, "snapshot") as StreamLoad.Existing
            val changes = snapshotRepository.changes(existing.append, listOf(Incremented(3)))
            snapshotRepository.append(transaction, existing.append, changes, metadata("snapshot-two"))
            assertThat(snapshotRepository.snapshot(transaction, namespace, "snapshot")).isInstanceOf(SnapshotSaveResult.Saved::class.java)
            assertThat(
                store.prune(
                    transaction,
                    com.gd.rain.event
                        .StreamRef(namespace, "counter", "snapshot"),
                    keep = 1,
                    batch = 1,
                ),
            ).isEqualTo(1)
        }
        store.inCallerTransaction { transaction ->
            assertThat((snapshotRepository.load(transaction, namespace, "snapshot") as StreamLoad.Existing).state).isEqualTo(6)
            assertThat(snapshotRepository.stateAt(transaction, namespace, "snapshot", 1).state).isEqualTo(1)
        }
    }

    @Test
    fun `fingerprint and decode drift ignore a snapshot rather than changing aggregate state`() {
        store.inCallerTransaction { transaction ->
            val missing = repository.load(transaction, namespace, "snapshot-drift") as StreamLoad.Missing
            val changes = repository.changes(missing.create.appendToken(), listOf(Incremented(2)))
            repository.append(transaction, missing.create.appendToken(), changes, metadata("drift-one"))
            val writer =
                EventRepository(
                    Counter,
                    EventCatalogue(
                        setOf(Counter),
                    ),
                    store,
                    snapshot =
                        SnapshotSpec(
                            IntSnapshot,
                            SnapshotFingerprint.of(
                                ByteArray(32) {
                                    1
                                },
                            ),
                        ),
                )
            writer.snapshot(transaction, namespace, "snapshot-drift")
        }
        val ignored = mutableListOf<SnapshotIgnoredReason>()
        val differentFingerprint =
            EventRepository(
                Counter,
                EventCatalogue(setOf(Counter)),
                store,
                snapshot = SnapshotSpec(IntSnapshot, SnapshotFingerprint.of(ByteArray(32) { 2 })),
                snapshotObserver = SnapshotObserver(ignored::add),
            )
        store.inCallerTransaction { transaction ->
            assertThat((differentFingerprint.load(transaction, namespace, "snapshot-drift") as StreamLoad.Existing).state).isEqualTo(2)
        }
        assertThat(ignored).containsExactly(SnapshotIgnoredReason.FINGERPRINT)

        val decodeIgnored = mutableListOf<SnapshotIgnoredReason>()
        val broken =
            EventRepository(
                Counter,
                EventCatalogue(setOf(Counter)),
                store,
                snapshot = SnapshotSpec(ThrowingIntSnapshot, SnapshotFingerprint.of(ByteArray(32) { 1 })),
                snapshotObserver = SnapshotObserver(decodeIgnored::add),
            )
        store.inCallerTransaction { transaction ->
            assertThat((broken.load(transaction, namespace, "snapshot-drift") as StreamLoad.Existing).state).isEqualTo(2)
        }
        assertThat(decodeIgnored).containsExactly(SnapshotIgnoredReason.DECODE)
    }

    @Test
    fun `committed global log is bounded ordered and rejects a cursor from another backing`() {
        store.inCallerTransaction { transaction ->
            val first = repository.load(transaction, namespace, "log-first") as StreamLoad.Missing
            repository.append(
                transaction,
                first.create.appendToken(),
                repository.changes(first.create.appendToken(), listOf(Incremented(1))),
                metadata("log-first"),
            )
            val second = repository.load(transaction, namespace, "log-second") as StreamLoad.Missing
            repository.append(
                transaction,
                second.create.appendToken(),
                repository.changes(second.create.appendToken(), listOf(Incremented(2))),
                metadata("log-second"),
            )
        }

        val firstPage = store.readCommitted(store.initialCursor(), 1)
        val secondPage = store.readCommitted(firstPage.next, 1)

        assertThat(firstPage.events.map { it.position }).containsExactly(1)
        assertThat(firstPage.hasMore).isTrue()
        assertThat(secondPage.events.map { it.position }).containsExactly(2)
        assertThat(secondPage.hasMore).isFalse()
        assertThat(store.mark(CommitRange(1, 2, 1, 2, 2))?.position).isEqualTo(2)

        val other = InMemoryEventStore(clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")))
        assertThatThrownBy { store.readCommitted(other.initialCursor(), 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("event log cursor belongs to another log")
    }

    private fun metadata(key: String): EventMetadata = EventMetadata(OperationKey.of(key))

    private data class Incremented(
        val amount: Int,
    )

    private object IncrementedFact : FactSpec<Incremented> {
        override val type: String = "counter.incremented"
        override val kotlinType: KClass<Incremented> = Incremented::class
        override val readableRevisions: Set<Int> = setOf(1)
        override val writeRevision: Int = 1

        override fun write(value: Incremented): EventBytes = EventBytes.utf8(value.amount.toString())

        override fun read(
            revision: Int,
            payload: EventBytes,
        ): Incremented = Incremented(String(payload.copy(), Charsets.UTF_8).toInt())
    }

    private object Counter : AggregateSpec<Int, String> {
        override val family: String = "counter"
        override val facts: Set<FactSpec<out Any>> = setOf(IncrementedFact)

        override fun streamKey(id: String): String = id

        override fun initial(id: String): Int = 0

        override fun fold(
            state: Int,
            fact: Any,
        ): Int = state + (fact as Incremented).amount
    }

    private object IntSnapshot : SnapshotCodec<Int> {
        override val id: SnapshotCodecId = SnapshotCodecId("counter.state", 1)

        override fun encode(state: Int): EventBytes = EventBytes.utf8(state.toString())

        override fun decode(payload: EventBytes): Int = String(payload.copy(), Charsets.UTF_8).toInt()
    }

    private object ThrowingIntSnapshot : SnapshotCodec<Int> {
        override val id: SnapshotCodecId = IntSnapshot.id

        override fun encode(state: Int): EventBytes = IntSnapshot.encode(state)

        override fun decode(payload: EventBytes): Int = error("snapshot format is no longer readable")
    }
}
