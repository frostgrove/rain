package com.gd.rain.crud

import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection

/**
 * The reads a list page makes, exactly as [CrudResource] issues them. They live in one place so that what a
 * resource runs and what rain-crud's plan proof explains cannot drift apart.
 *
 * Every read asks for one row more than the page holds; the extra row tells whether there is a page
 * beyond. A cursor page reads in the effective order, after the boundary row's key; the page before a
 * cursor reads in the inverted order after the same key, so the rows nearest to the cursor come first.
 */
public object PageReads {
    /** The first cursor page of [limit] rows in [order]. */
    public fun first(
        scope: RowScope,
        filter: Predicate?,
        order: List<Order>,
        limit: Int,
        projection: Projection,
    ): RowRead = RowRead(scope, filter, null, order, probe(limit), 0, projection)

    /** The cursor page of [limit] rows after the row whose values under [order] are [keys]. */
    public fun after(
        scope: RowScope,
        filter: Predicate?,
        order: List<Order>,
        keys: List<Any>,
        limit: Int,
        projection: Projection,
    ): RowRead = RowRead(scope, filter, Predicate.Keyset(order, keys, Predicate.Side.AFTER), order, probe(limit), 0, projection)

    /** The cursor page of [limit] rows before the row whose values under [order] are [keys], nearest first. */
    public fun before(
        scope: RowScope,
        filter: Predicate?,
        order: List<Order>,
        keys: List<Any>,
        limit: Int,
        projection: Projection,
    ): RowRead {
        val inverted = inverted(order)
        return RowRead(scope, filter, Predicate.Keyset(inverted, keys, Predicate.Side.AFTER), inverted, probe(limit), 0, projection)
    }

    /** The offset page of [limit] rows in [order], past the first [offset] rows. */
    public fun offset(
        scope: RowScope,
        filter: Predicate?,
        order: List<Order>,
        offset: Long,
        limit: Int,
        projection: Projection,
    ): RowRead = RowRead(scope, filter, null, order, probe(limit), offset, projection)

    /** [order] with every direction inverted. */
    public fun inverted(order: List<Order>): List<Order> = order.map { Order(it.field, it.direction.inverted()) }

    private fun probe(limit: Int): Int {
        require(limit in 1 until Int.MAX_VALUE) { "a page holds 1..${Int.MAX_VALUE - 1} rows, got $limit" }
        return limit + 1
    }
}
