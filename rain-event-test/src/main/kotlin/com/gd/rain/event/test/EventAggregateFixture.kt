package com.gd.rain.event.test

import com.gd.rain.event.AggregateSpec
import com.gd.rain.event.AppendResult
import com.gd.rain.event.CommitRange
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventCatalogue
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.EventRepository
import com.gd.rain.event.OperationKey
import com.gd.rain.event.StreamLoad
import java.time.Clock
import java.util.SplittableRandom
import java.util.UUID

/** Deterministic UUID capability exposed to an aggregate decision under test. */
public fun interface FixtureIds {
    public fun next(): UUID
}

/** Seeded, thread-confined fixture ids; production code should keep its own id source explicit. */
public class SeededFixtureIds(
    seed: Long,
) : FixtureIds {
    private val random: SplittableRandom = SplittableRandom(seed)

    override fun next(): UUID = UUID(random.nextLong(), random.nextLong())
}

/** The only ambient values a fixture decision receives, all deterministic and explicit. */
public data class FixtureDecisionContext<S : Any, ID : Any>(
    public val id: ID,
    public val exists: Boolean,
    public val state: S,
    public val version: Long,
    public val clock: Clock,
    public val ids: FixtureIds,
)

/** Exact result of one decision; no command handler was discovered or retried by the fixture. */
public sealed interface FixtureDecisionResult {
    public data class Emitted(
        public val facts: List<Any>,
        public val metadata: EventMetadata,
        public val range: CommitRange,
    ) : FixtureDecisionResult

    public data class ClosedFailure(
        public val cause: Throwable,
    ) : FixtureDecisionResult
}

/**
 * Typed aggregate test fixture backed by the published deterministic reference store.
 *
 * `given` writes only declared facts. `whenDecide` loads through the same public repository API an
 * application uses, invokes the supplied pure decision once, and appends its exact returned facts.
 * It does not inspect classes, discover handlers, create a command bus, or retry a decision.
 */
public class EventAggregateFixture<S : Any, ID : Any>(
    private val aggregate: AggregateSpec<S, ID>,
    catalogue: EventCatalogue,
    private val id: ID,
    private val clock: Clock,
    private val ids: FixtureIds = SeededFixtureIds(0),
    namespace: EventNamespace = EventNamespace.derive(EventBytes.utf8(aggregate.family)),
) {
    private val store: InMemoryEventStore = InMemoryEventStore(clock = clock)
    private val repository: EventRepository<S, ID> = EventRepository(aggregate, catalogue, store)
    private val namespace: EventNamespace = namespace
    private var givenOperations: Int = 0

    /** Appends declared historical facts as one deterministic fixture operation. */
    public fun given(facts: List<Any>): EventAggregateFixture<S, ID> {
        store.inCallerTransaction { transaction ->
            val loaded = repository.load(transaction, namespace, id)
            val token = appendToken(loaded)
            val changes = repository.changes(token, facts)
            val result =
                repository.append(
                    transaction,
                    token,
                    changes,
                    EventMetadata(OperationKey.of("fixture-given-${givenOperations++}")),
                )
            check(result is AppendResult.Committed) { "deterministic fixture append conflicted" }
        }
        return this
    }

    /** Convenience for a one-fact history entry. */
    public fun given(fact: Any): EventAggregateFixture<S, ID> = given(listOf(fact))

    /**
     * Invokes [decide] once over the reconstructed state and attempts exactly one append of its
     * returned facts. A thrown decision is returned as a closed fixture result and cannot advance
     * the reference stream.
     */
    public fun whenDecide(
        metadata: EventMetadata,
        decide: (FixtureDecisionContext<S, ID>) -> List<Any>,
    ): FixtureDecisionResult =
        store.inCallerTransaction { transaction ->
            val loaded = repository.load(transaction, namespace, id)
            val context =
                when (loaded) {
                    is StreamLoad.Missing -> FixtureDecisionContext(id, false, aggregate.initial(id), 0, clock, ids)
                    is StreamLoad.Existing -> FixtureDecisionContext(id, true, loaded.state, loaded.append.expectedVersion, clock, ids)
                }
            try {
                val facts = decide(context).toList()
                val token = appendToken(loaded)
                val result = repository.append(transaction, token, repository.changes(token, facts), metadata)
                check(result is AppendResult.Committed) { "deterministic fixture append conflicted" }
                FixtureDecisionResult.Emitted(facts, metadata, result.range)
            } catch (failure: Throwable) {
                FixtureDecisionResult.ClosedFailure(failure)
            }
        }

    /** Reconstructs the current aggregate state through the same finite-page repository path. */
    public fun state(): S =
        store.inCallerTransaction { transaction ->
            when (val loaded = repository.load(transaction, namespace, id)) {
                is StreamLoad.Missing -> aggregate.initial(id)
                is StreamLoad.Existing -> loaded.state
            }
        }

    private fun appendToken(loaded: StreamLoad<S>): com.gd.rain.event.AppendToken<S> =
        when (loaded) {
            is StreamLoad.Missing -> loaded.create.appendToken()
            is StreamLoad.Existing -> loaded.append
        }
}
