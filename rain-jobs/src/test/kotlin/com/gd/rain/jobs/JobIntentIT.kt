package com.gd.rain.jobs

import com.gd.rain.jobs.internal.ledger.DedupeMode
import com.gd.rain.jobs.internal.ledger.IntentLedger
import com.gd.rain.jobs.internal.ledger.Reservation
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.jobs.support.QueueFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Reservations under contention, with the interleavings staged rather than hoped for: the loser's statement is
 * observed waiting on the winner's uncommitted index entry before the winner commits.
 */
@Tag("integration")
class JobIntentIT {
    @Test
    fun `an order that waits on an uncommitted holder is absorbed into it, and its transaction stays usable`() {
        val fixture = QueueFixture("intent_race")
        val winnerReserved = CountDownLatch(1)
        val loserPid = AtomicInteger()
        val loserOutcome = AtomicReference<Any>()
        val pool = Executors.newVirtualThreadPerTaskExecutor()

        val winner =
            pool.submit<EnqueueOutcome> {
                fixture.database.inTransaction {
                    val outcome = fixture.queue.enqueue(Fixtures.definition(), Note("w"), Fixtures.options(Dedupe.Unique("k")))
                    winnerReserved.countDown()
                    Awaits.until("the loser to wait on the reservation index") { waitingOnLock(fixture, loserPid.get()) }
                    outcome
                }
            }
        val loser =
            pool.submit {
                Awaits.latch(winnerReserved, "the winner's reservation")
                runCatching {
                    fixture.database.inTransaction {
                        loserPid.set(requireNotNull(fixture.database.dsl.fetchValue(DSL.field("pg_backend_pid()", Int::class.java))))
                        val outcome = fixture.queue.enqueue(Fixtures.definition(), Note("l"), Fixtures.options(Dedupe.Unique("k")))
                        // An aborted transaction would refuse this statement.
                        fixture.database.dsl.execute("SELECT 1")
                        outcome
                    }
                }.fold({ loserOutcome.set(it) }, { loserOutcome.set(it) })
            }

        val won = winner.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
        loser.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
        pool.shutdown()

        assertThat(won).isInstanceOf(EnqueueOutcome.Scheduled::class.java)
        assertThat(loserOutcome.get()).isEqualTo(EnqueueOutcome.Deduplicated(won.invocation))
        assertThat(fixture.invocations()).isEqualTo(1)
        assertThat(fixture.heldIntents()).isEqualTo(1)
    }

    @Test
    fun `a holder released between the conflicting insert and the absorption leaves the key free, and the order takes it`() {
        val released = AtomicInteger()
        lateinit var fixture: QueueFixture
        fixture =
            QueueFixture("intent_released", decorate = { real ->
                object : IntentLedger by real {
                    override fun absorb(
                        definition: String,
                        key: String,
                        now: Instant,
                    ): UUID? {
                        if (released.getAndIncrement() == 0) {
                            fixture.database.jdbc.update(
                                "UPDATE rain_jobs.job_intent SET released_at = reserved_at WHERE released_at IS NULL",
                            )
                        }
                        return real.absorb(definition, key, now)
                    }
                }
            })
        val first = fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options(Dedupe.Unique("k")))

        val second = fixture.queue.enqueue(Fixtures.definition(), Note("b"), Fixtures.options(Dedupe.Unique("k")))

        assertThat(second).isInstanceOf(EnqueueOutcome.Scheduled::class.java)
        assertThat(second.invocation).isNotEqualTo(first.invocation)
        assertThat(
            fixture.database.jdbc.queryForObject(
                "SELECT invocation_id FROM rain_jobs.job_intent WHERE released_at IS NULL",
                UUID::class.java,
            ),
        ).isEqualTo(second.invocation)
    }

    @Test
    fun `a key that changes holder on every placement refuses the order after the declared number of placements`() {
        val placements = AtomicInteger()
        val fixture =
            QueueFixture("intent_conflict", decorate = { real ->
                object : IntentLedger by real {
                    override fun reserve(
                        definition: String,
                        profile: String,
                        key: String,
                        mode: DedupeMode,
                        invocation: UUID,
                        now: Instant,
                    ): Reservation {
                        placements.incrementAndGet()
                        return Reservation.Held(null)
                    }

                    override fun absorb(
                        definition: String,
                        key: String,
                        now: Instant,
                    ): UUID? = null
                }
            })

        assertThatThrownBy { fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options(Dedupe.Unique("k"))) }
            .isInstanceOf(IntentConflictException::class.java)
        assertThat(placements.get()).isEqualTo(3)
        assertThat(fixture.invocations()).isZero()
    }

    @Test
    fun `eight concurrent orders for one key produce one invocation and seven absorptions, and none fails`() {
        val fixture = QueueFixture("intent_eight")
        val start = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()

        val futures =
            (1..CONTENDERS).map {
                pool.submit<EnqueueOutcome> {
                    Awaits.latch(start, "the start signal")
                    fixture.queue.enqueue(Fixtures.definition(), Note("n"), Fixtures.options(Dedupe.Unique("k")))
                }
            }
        start.countDown()
        val outcomes = futures.map { it.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS) }
        pool.shutdown()

        val scheduled = outcomes.filterIsInstance<EnqueueOutcome.Scheduled>()
        assertThat(scheduled).hasSize(1)
        assertThat(outcomes.map { it.invocation }.distinct()).containsExactly(scheduled.single().invocation)
        assertThat(fixture.invocations()).isEqualTo(1)
        assertThat(
            fixture.database.count("SELECT absorbed_count FROM rain_jobs.job_invocation"),
        ).isEqualTo((CONTENDERS - 1).toLong())
    }

    private fun waitingOnLock(
        fixture: QueueFixture,
        pid: Int,
    ): Boolean =
        pid != 0 &&
            fixture.database.count(
                "SELECT count(*) FROM pg_stat_activity WHERE pid = $pid AND wait_event_type = 'Lock'",
            ) == 1L

    private companion object {
        const val CONTENDERS = 8
    }
}
