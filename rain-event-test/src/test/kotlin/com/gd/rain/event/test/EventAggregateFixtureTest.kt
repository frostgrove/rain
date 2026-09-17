package com.gd.rain.event.test

import com.gd.rain.event.AggregateSpec
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventCatalogue
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.FactSpec
import com.gd.rain.event.OperationKey
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

class EventAggregateFixtureTest {
    @Test
    fun `fixture gives declared facts invokes a pure decision once and exposes deterministic capabilities`() {
        val clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
        val ids = SeededFixtureIds(42)
        val fixture = EventAggregateFixture(Counter, EventCatalogue(setOf(Counter)), "counter", clock, ids)
        val metadata = EventMetadata(OperationKey.of("fixture-decision"))
        var invocations = 0
        var issued: UUID? = null

        val result =
            fixture
                .given(Incremented(2))
                .whenDecide(metadata) { context ->
                    invocations++
                    assertThat(context.exists).isTrue()
                    assertThat(context.state).isEqualTo(2)
                    assertThat(context.version).isEqualTo(1)
                    assertThat(context.clock.instant()).isEqualTo(clock.instant())
                    issued = context.ids.next()
                    listOf(Incremented(3))
                }

        assertThat(result).isInstanceOf(FixtureDecisionResult.Emitted::class.java)
        val emitted = result as FixtureDecisionResult.Emitted
        assertThat(emitted.facts).containsExactly(Incremented(3))
        assertThat(emitted.metadata).isSameAs(metadata)
        assertThat(emitted.range).isEqualTo(
            com.gd.rain.event
                .CommitRange(2, 2, 2, 2, 1),
        )
        assertThat(invocations).isEqualTo(1)
        assertThat(issued).isEqualTo(SeededFixtureIds(42).next())
        assertThat(fixture.state()).isEqualTo(5)
    }

    @Test
    fun `closed decision failure does not append a fact`() {
        val fixture =
            EventAggregateFixture(
                Counter,
                EventCatalogue(setOf(Counter)),
                "counter",
                MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            ).given(Incremented(2))

        val result = fixture.whenDecide(EventMetadata(OperationKey.of("fixture-failure"))) { error("invalid decision") }

        assertThat(result).isInstanceOf(FixtureDecisionResult.ClosedFailure::class.java)
        assertThat((result as FixtureDecisionResult.ClosedFailure).cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.state()).isEqualTo(2)
    }

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
}
