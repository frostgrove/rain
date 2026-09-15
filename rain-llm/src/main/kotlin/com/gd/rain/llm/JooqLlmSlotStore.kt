package com.gd.rain.llm

import com.gd.rain.llm.jooq.Tables.LLM_BUDGET
import com.gd.rain.llm.jooq.Tables.LLM_SLOTS
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.Record2
import org.jooq.ResultQuery
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.types.DayToSecond
import java.time.Duration
import java.util.UUID

/**
 * Slots as rows in `rain_llm`, decided in one statement.
 *
 * Sweeping one pool's expired slots, reading its budget row `FOR UPDATE`, deciding and inserting are one
 * decision; split into round trips, two processes would both read "one left" and both take it. The lease
 * is written with the database server's clock, so no two processes compare their own clocks, and the
 * statement that sweeps expired slots repairs `taken` against the rows it removed.
 *
 * Cost does not grow with the table: the sweep is a range over `ix_llm_slots_expiry` within one pool,
 * the budget row is read by its primary key, and a release deletes one slot by its primary key.
 */
public class JooqLlmSlotStore(
    private val dsl: DSLContext,
) : LlmSlotStore {
    override fun tryAcquire(
        pool: String,
        ceiling: Int,
        id: UUID,
        holder: String,
        lease: Duration,
    ): SlotGrant {
        val outcome = acquireQuery(pool, ceiling, id, holder, lease).fetchSingle()
        return when {
            outcome.value1() == 0 -> SlotGrant.PoolMissing
            outcome.value2() == 1 -> SlotGrant.Granted
            else -> SlotGrant.Full
        }
    }

    override fun release(id: UUID) {
        releaseQuery(id).execute()
    }

    override fun ensurePools(pools: Collection<String>) {
        if (pools.isEmpty()) return
        dsl
            .insertInto(LLM_BUDGET, LLM_BUDGET.POOL)
            .valuesOfRows(pools.sorted().map { DSL.row(it) })
            .onConflictDoNothing()
            .execute()
    }

    /** `(pools, granted)`: whether the pool's budget row exists, and whether a slot was inserted. */
    internal fun acquireQuery(
        pool: String,
        ceiling: Int,
        id: UUID,
        holder: String,
        lease: Duration,
    ): ResultQuery<Record2<Int, Int>> {
        val expired = DSL.name("expired")
        val swept = DSL.name("swept")
        val room = DSL.name("room")
        val admitted = DSL.name("admitted")
        val inserted = DSL.name("inserted")

        val reclaimed = DSL.field(DSL.name(swept, DSL.name("reclaimed")), SQLDataType.INTEGER)
        val live = DSL.field(DSL.name(room, DSL.name("live")), SQLDataType.INTEGER)
        val granted = DSL.field(DSL.name(admitted, DSL.name("granted")), SQLDataType.BOOLEAN)
        val liveNow = DSL.select(live).from(room).asField<Int>()
        val hasRoom = liveNow.lt(ceiling)

        return dsl
            .with(expired)
            .`as`(
                DSL
                    .deleteFrom(LLM_SLOTS)
                    .where(LLM_SLOTS.POOL.eq(pool))
                    .and(LLM_SLOTS.EXPIRES_AT.le(DSL.currentOffsetDateTime()))
                    .returningResult(DSL.inline(1)),
            ).with(swept)
            .`as`(DSL.select(DSL.count().`as`("reclaimed")).from(expired))
            .with(room)
            .`as`(
                DSL
                    .select(
                        DSL.greatest(LLM_BUDGET.TAKEN.minus(DSL.select(reclaimed).from(swept).asField<Int>()), DSL.inline(0)).`as`("live"),
                    ).from(LLM_BUDGET)
                    .where(LLM_BUDGET.POOL.eq(pool))
                    .forUpdate(),
            ).with(admitted)
            .`as`(
                DSL
                    .update(LLM_BUDGET)
                    .set(LLM_BUDGET.TAKEN, liveNow.plus(DSL.`when`(hasRoom, DSL.inline(1)).otherwise(DSL.inline(0))))
                    .where(LLM_BUDGET.POOL.eq(pool))
                    .returningResult(hasRoom.`as`("granted")),
            ).with(inserted)
            .`as`(
                DSL
                    .insertInto(LLM_SLOTS, LLM_SLOTS.ID, LLM_SLOTS.POOL, LLM_SLOTS.HOLDER, LLM_SLOTS.EXPIRES_AT)
                    .select(
                        DSL
                            .select(
                                DSL.value(id),
                                DSL.value(pool),
                                DSL.value(holder),
                                DSL.currentOffsetDateTime().plus(DSL.inline(DayToSecond.valueOf(lease))),
                            ).where(
                                DSL
                                    .select(granted)
                                    .from(admitted)
                                    .asField<Boolean>()
                                    .isTrue,
                            ),
                    ).returningResult(LLM_SLOTS.ID),
            ).select(
                DSL.field(DSL.selectCount().from(room)).`as`("pools"),
                DSL.field(DSL.selectCount().from(inserted)).`as`("granted"),
            )
    }

    /** Deletes the slot and lowers `taken` only for a row that was actually deleted. */
    internal fun releaseQuery(id: UUID): Query {
        val released = DSL.name("released")
        val pool = DSL.field(DSL.name(released, LLM_SLOTS.POOL.unqualifiedName), SQLDataType.CLOB)
        return dsl
            .with(released)
            .`as`(DSL.deleteFrom(LLM_SLOTS).where(LLM_SLOTS.ID.eq(id)).returningResult(LLM_SLOTS.POOL))
            .update(LLM_BUDGET)
            .set(LLM_BUDGET.TAKEN, DSL.greatest(LLM_BUDGET.TAKEN.minus(DSL.inline(1)), DSL.inline(0)))
            .where(LLM_BUDGET.POOL.`in`(DSL.select(pool).from(released)))
    }
}
