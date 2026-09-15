package com.gd.rain.llm

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** The wait for a slot: bounded by the deadline, explicit about a missing pool row, released once. */
class LlmSlotsTest {
    private val clock = MutableClock(START)
    private val pool = LlmPool("default", bulk = 1, interactive = 2)

    @Test
    fun `a slot freed while the ask waits is taken on the next attempt`() {
        val store = MemorySlotStore("default")
        val occupant = store.occupy("default", 1).single()
        val pause = ClockPause(clock) { ordinal -> if (ordinal == 2) store.release(occupant) }

        val slot = slots(store, pause).acquire(pool, LlmClass.BULK, START.plusSeconds(5))

        assertThat(store.attempts.get()).isEqualTo(3)
        assertThat(pause.pauses).hasSize(2)
        slot.close()
        assertThat(store.held()).isZero()
    }

    @Test
    fun `interactive work may go deeper into the pool than bulk work`() {
        val store = MemorySlotStore("default")
        val slots = slots(store, ClockPause(clock))
        slots.acquire(pool, LlmClass.BULK, START.plusSeconds(1))

        assertThatThrownBy {
            slots.acquire(
                pool,
                LlmClass.BULK,
                clock.instant().plusMillis(500),
            )
        }.isInstanceOf(LlmBudgetExhausted::class.java)
        slots.acquire(pool, LlmClass.INTERACTIVE, clock.instant().plusMillis(500))
        assertThat(store.held()).isEqualTo(2)
    }

    @Test
    fun `a pool without a budget row is refused at once, without waiting`() {
        val pause = ClockPause(clock)
        val slots = slots(MemorySlotStore(), pause)

        assertThatThrownBy { slots.acquire(pool, LlmClass.BULK, START.plusSeconds(30)) }
            .isInstanceOf(LlmPoolMissing::class.java)
            .hasMessageContaining("\"default\"")
        assertThat(pause.pauses).isEmpty()
    }

    @Test
    fun `a deadline already passed takes one attempt and no pause`() {
        val store = MemorySlotStore("default")
        store.occupy("default", 2)
        val pause = ClockPause(clock)

        assertThatThrownBy { slots(store, pause).acquire(pool, LlmClass.INTERACTIVE, START) }.isInstanceOf(LlmBudgetExhausted::class.java)
        assertThat(store.attempts.get()).isEqualTo(1)
        assertThat(pause.pauses).isEmpty()
    }

    @Test
    fun `closing a slot twice gives it back once`() {
        val store = CountingReleaseStore()
        val slot = slots(store, ClockPause(clock)).acquire(pool, LlmClass.BULK, START.plusSeconds(1))

        slot.close()
        slot.close()

        assertThat(store.releases).isEqualTo(1)
    }

    @Test
    fun `a release that fails does not fail the ask whose answer is already in hand`() {
        val store = CountingReleaseStore(failing = true)
        val slot = slots(store, ClockPause(clock)).acquire(pool, LlmClass.BULK, START.plusSeconds(1))

        slot.close()

        assertThat(store.releases).isEqualTo(1)
    }

    @Test
    fun `an interrupted wait restores the interrupt and says so`() {
        val store = MemorySlotStore("default")
        store.occupy("default", 2)
        val slots = slots(store) { throw InterruptedException("stop") }

        assertThatThrownBy { slots.acquire(pool, LlmClass.INTERACTIVE, START.plusSeconds(1)) }.isInstanceOf(LlmInterrupted::class.java)
        assertThat(Thread.interrupted()).isTrue()
    }

    private fun slots(
        store: LlmSlotStore,
        pause: SlotPause,
    ): LlmSlots = LlmSlots(store, Duration.ofSeconds(30), Duration.ofMillis(300), clock, { UUID.randomUUID() }, "test/1", pause)

    private class CountingReleaseStore(
        private val failing: Boolean = false,
    ) : LlmSlotStore {
        var releases = 0

        override fun tryAcquire(
            pool: String,
            ceiling: Int,
            id: UUID,
            holder: String,
            lease: Duration,
        ): SlotGrant = SlotGrant.Granted

        override fun release(id: UUID) {
            releases++
            check(!failing) { "the connection was lost" }
        }

        override fun ensurePools(pools: Collection<String>) = Unit
    }
}
