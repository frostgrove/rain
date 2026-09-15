package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.Direction
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * What every [CrudStore] does, run against the in-memory store and against PostgreSQL through jOOQ, so the
 * double the unit tests stand on behaves like the store applications run.
 */
abstract class CrudStoreContract {
    /** An empty store for [Books.SCHEMA], of its own for each call. */
    abstract fun freshStore(): CrudStore<Map<String, Any?>>

    private fun at(minutes: Long) = T0.plus(Duration.ofMinutes(minutes))

    private fun CrudStore<Map<String, Any?>>.ids(read: RowRead): List<UUID> = read(read).map { it.item["id"] as UUID }

    private fun everything(
        order: List<Order>,
        limit: Int = 100,
        filter: Predicate? = null,
        seek: Predicate.Keyset? = null,
        offset: Long = 0,
        scope: RowScope = RowScope.Everything,
    ) = RowRead(scope, filter, seek, order, limit, offset, Projection.Full)

    private val byId = listOf(Order(Books.ID, Direction.ASC))

    @Test
    fun `an insert mints the identifier and every kind reads back as it was written`() {
        val store = freshStore()
        val written =
            store.insert(
                book(
                    "dune",
                    "a",
                    412,
                    T0,
                    BigDecimal("12.50"),
                    LocalDate.of(1965, 8, 1),
                    available = false,
                    isbn = "978-0441013593",
                    copies = 5_000_000_000,
                ),
            )

        val id = written["id"] as UUID
        assertThat(store.find(id, RowScope.Everything, Projection.Full)).isEqualTo(written)
        assertThat(written).containsEntry("title", "dune").containsEntry("pages", 412).containsEntry("createdAt", T0)
        assertThat(written).containsEntry("price", BigDecimal("12.50")).containsEntry("publishedOn", LocalDate.of(1965, 8, 1))
        assertThat(
            written,
        ).containsEntry("available", false).containsEntry("isbn", "978-0441013593").containsEntry("copies", 5_000_000_000L)
    }

    @Test
    fun `an insert keeps an identifier it is given`() {
        val store = freshStore()
        val id = UUID.fromString("0192f1c0-0000-7000-8000-000000000001")

        assertThat(store.insert(book("emma", "a", 1, T0, id = id))["id"]).isEqualTo(id)
    }

    @Test
    fun `an update writes the named fields, keeps the rest, and answers the whole row`() {
        val store = freshStore()
        val id = store.insert(book("dune", "a", 412, T0, isbn = "1"))["id"] as UUID

        val updated = store.update(id, RowScope.Everything, mapOf("pages" to 500, "isbn" to null))

        assertThat(
            updated,
        ).containsEntry("pages", 500).containsEntry("isbn", null).containsEntry("title", "dune").containsEntry("shelf", "a")
        assertThat(store.find(id, RowScope.Everything, Projection.Full)).isEqualTo(updated)
    }

    @Test
    fun `a read filters, orders by every term, skips its offset and stops at its limit`() {
        val store = freshStore()
        listOf(100, 300, 200, 50, 300).forEachIndexed { index, pages -> store.insert(book("t$index", "a", pages, at(index.toLong()))) }
        val order = listOf(Order(Books.PAGES, Direction.DESC), Order(Books.TITLE, Direction.ASC), Order(Books.ID, Direction.ASC))
        val big = Predicate.Compare(Books.PAGES, Operator.GTE, listOf(100))

        val rows = store.read(everything(order, limit = 2, filter = big, offset = 1))

        assertThat(rows.map { it.item["title"] }).containsExactly("t4", "t2")
        assertThat(rows.first().keys).containsExactly(300, "t4", rows.first().item["id"])
    }

    @Test
    fun `a keyset read continues strictly after its key, whichever way the order runs`() {
        val store = freshStore()
        listOf("c", "a", "b", "b", "d").forEachIndexed { index, title -> store.insert(book(title, "a", 1, at(index.toLong()))) }
        val ascending = listOf(Order(Books.TITLE, Direction.ASC), Order(Books.ID, Direction.ASC))
        val all = store.read(everything(ascending))
        val third = all[2]

        val after = store.ids(everything(ascending, seek = Predicate.Keyset(ascending, third.keys, Predicate.Side.AFTER)))
        val descending = ascending.map { Order(it.field, it.direction.inverted()) }
        val before = store.ids(everything(descending, seek = Predicate.Keyset(descending, third.keys, Predicate.Side.AFTER)))

        val allIds = all.map { it.item["id"] }
        assertThat(after).isEqualTo(allIds.drop(3))
        assertThat(before).isEqualTo(allIds.take(2).reversed())
    }

    @Test
    fun `a mixed-direction keyset continues the order exactly`() {
        val store = freshStore()
        listOf("a" to 3, "a" to 1, "b" to 2, "a" to 2, "b" to 2, "c" to 9, "b" to 1).forEachIndexed { index, (title, pages) ->
            store.insert(book(title, "a", pages, at(index.toLong())))
        }
        val order = listOf(Order(Books.TITLE, Direction.ASC), Order(Books.PAGES, Direction.DESC), Order(Books.ID, Direction.ASC))
        val all = store.read(everything(order))

        all.indices.forEach { index ->
            val after = store.ids(everything(order, seek = Predicate.Keyset(order, all[index].keys, Predicate.Side.AFTER)))
            val before = store.ids(everything(order, seek = Predicate.Keyset(order, all[index].keys, Predicate.Side.BEFORE)))
            assertThat(after).describedAs("after row $index").isEqualTo(all.drop(index + 1).map { it.item["id"] })
            assertThat(before).describedAs("before row $index").isEqualTo(all.take(index).map { it.item["id"] })
        }
        assertThat(all.map { "${it.item["title"]}${it.item["pages"]}" }).startsWith("a3", "a2", "a1", "b2", "b2", "b1", "c9")
    }

    @Test
    fun `a comparison never matches NULL and isnull does`() {
        val store = freshStore()
        store.insert(book("priced", "a", 1, T0, price = BigDecimal("10")))
        store.insert(book("other", "a", 1, T0, price = BigDecimal("20.5")))
        store.insert(book("free", "a", 1, T0, price = null))

        fun titles(predicate: Predicate) = store.read(everything(byId, filter = predicate)).map { it.item["title"] }.toSet()

        assertThat(titles(Predicate.Compare(Books.PRICE, Operator.GT, listOf(BigDecimal("10.00"))))).containsExactly("other")
        assertThat(
            titles(Predicate.Compare(Books.PRICE, Operator.LTE, listOf(BigDecimal("20.50")))),
        ).containsExactlyInAnyOrder("priced", "other")
        assertThat(
            titles(Predicate.Compare(Books.PRICE, Operator.IN, listOf(BigDecimal("10"), BigDecimal("20.5")))),
        ).containsExactlyInAnyOrder("priced", "other")
        assertThat(titles(Predicate.isNull(Books.PRICE))).containsExactly("free")
        assertThat(titles(Predicate.isNotNull(Books.PRICE))).containsExactlyInAnyOrder("priced", "other")
    }

    @Test
    fun `dates, instants, booleans and longs compare by value`() {
        val store = freshStore()
        store.insert(book("old", "a", 1, T0, publishedOn = LocalDate.of(1965, 8, 1), available = false, copies = 1))
        store.insert(book("new", "a", 1, at(90), publishedOn = LocalDate.of(2020, 1, 1), available = true, copies = 9_000_000_000))

        fun titles(predicate: Predicate) = store.read(everything(byId, filter = predicate)).map { it.item["title"] }.toSet()

        assertThat(titles(Predicate.Compare(Books.PUBLISHED_ON, Operator.GTE, listOf(LocalDate.of(2000, 1, 1))))).containsExactly("new")
        assertThat(titles(Predicate.Compare(Books.CREATED_AT, Operator.LT, listOf(at(1))))).containsExactly("old")
        assertThat(titles(Predicate.eq(Books.AVAILABLE, false))).containsExactly("old")
        assertThat(titles(Predicate.Compare(Books.COPIES, Operator.GT, listOf(5_000_000_000L)))).containsExactly("new")
        assertThat(titles(Predicate.AnyOf(listOf(Predicate.eq(Books.TITLE, "old"), Predicate.eq(Books.TITLE, "new"))))).hasSize(2)
    }

    @Test
    fun `a count reads at most cap plus one rows`() {
        val store = freshStore()
        repeat(5) { store.insert(book("t$it", if (it < 4) "a" else "b", 1, T0)) }
        val shelfA = Predicate.eq(Books.SHELF, "a")

        assertThat(store.countUpTo(RowScope.Everything, null, 2)).isEqualTo(3)
        assertThat(store.countUpTo(RowScope.Everything, null, 10)).isEqualTo(5)
        assertThat(store.countUpTo(RowScope.Everything, shelfA, 4)).isEqualTo(4)
        assertThat(store.countUpTo(RowScope.Matching(shelfA), Predicate.eq(Books.TITLE, "t4"), 10)).isZero()
    }

    @Test
    fun `every read and every write stays inside its scope`() {
        val store = freshStore()
        val inside = store.insert(book("inside", "a", 1, T0))["id"] as UUID
        val outside = store.insert(book("outside", "b", 1, T0))["id"] as UUID
        val shelfA = RowScope.Matching(Predicate.eq(Books.SHELF, "a"))

        assertThat(store.find(outside, shelfA, Projection.Full)).isNull()
        assertThat(store.update(outside, shelfA, mapOf("pages" to 9))).isNull()
        assertThat(store.updateMany(setOf(inside, outside), shelfA, mapOf("pages" to 7))).isEqualTo(1)
        assertThat(store.delete(outside, shelfA)).isZero()
        assertThat(store.deleteMany(setOf(outside), shelfA)).isZero()
        assertThat(store.ids(everything(byId, scope = shelfA))).containsExactly(inside)
        assertThat(store.countUpTo(shelfA, null, 10)).isEqualTo(1)

        assertThat(store.find(outside, RowScope.Everything, Projection.Full)).containsEntry("pages", 1).containsEntry("title", "outside")
        assertThat(store.find(inside, RowScope.Everything, Projection.Full)).containsEntry("pages", 7)
        assertThat(store.deleteMany(setOf(inside, outside), shelfA)).isEqualTo(1)
        assertThat(store.find(outside, RowScope.Everything, Projection.Full)).isNotNull()
    }

    @Test
    fun `a projection answers the identifier and the named fields only`() {
        val store = freshStore()
        val id = store.insert(book("dune", "a", 412, T0))["id"] as UUID
        val projection = Projection.Only(setOf(Books.ID, Books.TITLE))
        val order = listOf(Order(Books.CREATED_AT, Direction.DESC), Order(Books.ID, Direction.DESC))

        val read = store.read(RowRead(RowScope.Everything, null, null, order, 10, 0, projection)).single()

        assertThat(read.item).containsOnlyKeys("id", "title")
        assertThat(read.keys).containsExactly(T0, id)
        assertThat(store.find(id, RowScope.Everything, projection)).isEqualTo(mapOf("id" to id, "title" to "dune"))
    }
}
