package com.gd.rain.llm

import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.MutableClock
import com.gd.rain.test.QueryPlans
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `rain_llm.llm_slots` and `llm_budget` against PostgreSQL: the claims no unit test can reach, because
 * they are about several processes sharing one ceiling.
 */
@Tag("integration")
class LlmSlotsIT {
    class Database(
        prefix: String,
        provisioned: Boolean = true,
    ) {
        val dataSource: DataSource = RainPostgres.freshDatabase(prefix).dataSource()
        val dsl: DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)
        val store = JooqLlmSlotStore(dsl)
        val clock = MutableClock(START)
        private val jdbc = JdbcTemplate(dataSource)

        init {
            RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
                .migrate(
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/none")
                        .failOnMissingLocations(false)
                        .load(),
                )
            if (provisioned) store.ensurePools(listOf(POOL.name))
        }

        fun replica(
            holder: String,
            pause: SlotPause = ClockPause(clock),
        ): LlmSlots = LlmSlots(store, LEASE, Duration.ofMillis(200), clock, { UUID.randomUUID() }, holder, pause)

        fun taken(pool: String = POOL.name): Int? =
            jdbc.query("SELECT taken FROM rain_llm.llm_budget WHERE pool = ?", { rows, _ -> rows.getInt(1) }, pool).singleOrNull()

        fun slotIds(): List<UUID> = jdbc.query("SELECT id FROM rain_llm.llm_slots", { rows, _ -> rows.getObject(1, UUID::class.java) })

        fun budgetRows(): Int = jdbc.queryForObject("SELECT count(*) FROM rain_llm.llm_budget", Int::class.java) ?: 0

        fun expireEverySlot() {
            jdbc.update("UPDATE rain_llm.llm_slots SET expires_at = now() - interval '1 second'")
        }

        fun soon() = clock.instant().plusSeconds(1)
    }

    @Test
    fun `bulk work saturates at its ceiling and waits rather than taking the interactive reserve`() {
        val database = Database("llm_reserve")
        val slots = database.replica("replica-a")

        val bulk = (1..POOL.bulk).map { slots.acquire(POOL, LlmClass.BULK, database.soon()) }
        assertThat(database.taken()).isEqualTo(POOL.bulk)
        assertThatThrownBy { slots.acquire(POOL, LlmClass.BULK, database.soon()) }.isInstanceOf(LlmBudgetExhausted::class.java)

        val interactive = (1..POOL.interactive - POOL.bulk).map { slots.acquire(POOL, LlmClass.INTERACTIVE, database.soon()) }
        assertThat(database.taken()).isEqualTo(POOL.interactive)
        assertThatThrownBy { slots.acquire(POOL, LlmClass.INTERACTIVE, database.soon()) }.isInstanceOf(LlmBudgetExhausted::class.java)

        (bulk + interactive).forEach(LlmSlot::close)
        assertThat(database.taken()).isZero()
        assertThat(database.slotIds()).isEmpty()
    }

    @Test
    fun `the ceiling holds across two replicas with no shared memory`() {
        val database = Database("llm_replicas")
        val first = database.replica("replica-a")
        val second = database.replica("replica-b")

        val held =
            (0 until POOL.interactive).map { index ->
                val replica = if (index % 2 == 0) first else second
                replica.acquire(POOL, LlmClass.INTERACTIVE, database.soon())
            }
        assertThatThrownBy { second.acquire(POOL, LlmClass.INTERACTIVE, database.soon()) }.isInstanceOf(LlmBudgetExhausted::class.java)

        held.first().close()
        second.acquire(POOL, LlmClass.INTERACTIVE, database.soon()).use { assertThat(database.taken()).isEqualTo(POOL.interactive) }
        held.drop(1).forEach(LlmSlot::close)
    }

    @Test
    fun `concurrent acquisitions released together never grant more than the ceiling`() {
        val database = Database("llm_concurrent")
        val contenders = POOL.interactive * 3
        val barrier = CyclicBarrier(contenders)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val grants =
                (1..contenders)
                    .map { index ->
                        executor.submit(
                            Callable {
                                barrier.await(10, TimeUnit.SECONDS)
                                database.store.tryAcquire(POOL.name, POOL.interactive, UUID.randomUUID(), "contender-$index", LEASE)
                            },
                        )
                    }.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(grants.count { it == SlotGrant.Granted }).isEqualTo(POOL.interactive)
            assertThat(grants.count { it == SlotGrant.Full }).isEqualTo(contenders - POOL.interactive)
            assertThat(database.taken()).isEqualTo(POOL.interactive)
            assertThat(database.slotIds()).hasSize(POOL.interactive)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired slots are swept by the next acquiring statement, which repairs the counter with them`() {
        val database = Database("llm_expiry")
        val dead = database.replica("a replica that died")
        val abandoned = (1..POOL.interactive).map { dead.acquire(POOL, LlmClass.INTERACTIVE, database.soon()) }
        database.expireEverySlot()

        database.replica("replica-b").acquire(POOL, LlmClass.INTERACTIVE, database.soon()).use { slot ->
            assertThat(database.slotIds()).containsExactly(slot.id)
            assertThat(database.taken()).describedAs("the sweep repaired the counter against the rows it removed").isEqualTo(1)
        }
        assertThat(abandoned).hasSize(POOL.interactive)
        assertThat(database.slotIds()).isEmpty()
        assertThat(database.taken()).isZero()
    }

    @Test
    fun `releasing twice, or releasing a slot nobody holds, never drives the counter below the live slots`() {
        val database = Database("llm_release")
        val slots = database.replica("replica-a")
        val held = slots.acquire(POOL, LlmClass.BULK, database.soon())
        val other = slots.acquire(POOL, LlmClass.BULK, database.soon())

        database.store.release(held.id)
        database.store.release(held.id)
        database.store.release(UUID.randomUUID())

        assertThat(database.taken()).isEqualTo(1)
        other.close()
        assertThat(database.taken()).isZero()
    }

    @Test
    fun `a slot released while an ask waits is taken on its next attempt`() {
        val database = Database("llm_wait")
        val held = (1..POOL.interactive).map { database.replica("replica-a").acquire(POOL, LlmClass.INTERACTIVE, database.soon()) }
        val pause = ClockPause(database.clock) { ordinal -> if (ordinal == 1) held.first().close() }

        database.replica("replica-b", pause).acquire(POOL, LlmClass.INTERACTIVE, database.soon()).close()

        assertThat(pause.pauses).hasSize(1)
        held.drop(1).forEach(LlmSlot::close)
        assertThat(database.taken()).isZero()
    }

    @Test
    fun `provisioning is idempotent and never resets a pool that is in use`() {
        val database = Database("llm_provision")
        val slot = database.replica("replica-a").acquire(POOL, LlmClass.BULK, database.soon())

        database.store.ensurePools(listOf("batch", POOL.name))
        database.store.ensurePools(listOf(POOL.name))

        assertThat(database.taken()).isEqualTo(1)
        assertThat(database.taken("batch")).isZero()
        assertThat(database.budgetRows()).isEqualTo(2)
        slot.close()
    }

    @Test
    fun `the acquiring statement reaches both tables through their indexes`() {
        val database = Database("llm_plan_acquire")
        val query = database.store.acquireQuery(POOL.name, POOL.interactive, UUID(0, 1), "replica-a", LEASE)

        val plan = QueryPlans.explain(database.dataSource, database.dsl.renderInlined(query), generic = false)

        assertThat(plan.usesIndex("ix_llm_slots_expiry")).describedAs(plan.json).isTrue()
        assertThat(plan.usesIndex("llm_budget_pkey")).describedAs(plan.json).isTrue()
        assertThat(plan.scansSequentially("llm_slots")).describedAs(plan.json).isFalse()
        assertThat(plan.scansSequentially("llm_budget")).describedAs(plan.json).isFalse()
    }

    @Test
    fun `a release reaches its slot and its budget row by key`() {
        val database = Database("llm_plan_release")

        val plan =
            QueryPlans.explain(
                database.dataSource,
                database.dsl.renderInlined(database.store.releaseQuery(UUID(0, 1))),
                generic = false,
            )

        assertThat(plan.usesIndex("llm_slots_pkey")).describedAs(plan.json).isTrue()
        assertThat(plan.scansSequentially("llm_slots")).describedAs(plan.json).isFalse()
        assertThat(plan.scansSequentially("llm_budget")).describedAs(plan.json).isFalse()
    }

    companion object {
        val POOL = LlmPool("default", bulk = 6, interactive = 8)
        val LEASE: Duration = Duration.ofSeconds(30)
    }
}
