package com.gd.rain.crud

import com.gd.rain.core.error.FaultKind
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.Predicate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A permission that widens a shelf-confined caller to every shelf. */
const val EVERY_SHELF: String = "book.every-shelf"

/** Each caller's own shelf, for a caller who does not hold [EVERY_SHELF]. */
val OWN_SHELF: ScopeRule.Rows = ScopeRule.Rows { caller -> Predicate.eq(Books.SHELF, caller.actor.id) }

val EVERY_SHELF_OR_OWN: ScopeRule = ScopeRule.EveryRowWhenHolding(setOf(EVERY_SHELF), OWN_SHELF)

/**
 * One resource used to have one scope for every caller, so an application whose supervisors read every row and whose
 * other callers read their own mounted two resources and chose one per request. `ScopeRule.EveryRowWhenHolding` decides
 * it per caller, inside one resource, by one question about exactly the permissions it names.
 */
class EveryRowWhenHoldingScopeTest {
    private val store = MemoryStore(Books.SCHEMA)
    private val callers = SwitchableCallers()
    private val resource = CrudResource(Books.rules(), Books.policy(EVERY_SHELF_OR_OWN), store, callers, emptyList())

    private fun signIn(
        shelf: String,
        vararg permissions: String,
    ): TestCaller = TestCaller.of(shelf, *permissions).also { callers.caller = it }

    @Test
    fun `a caller holding the permission reads every row, asked once about exactly that permission`() {
        store.add(book("mine", "a", 1, T0), book("theirs", "b", 2, T0))
        val holder = signIn("a", Books.READ, EVERY_SHELF)

        assertThat(resource.list(emptyMap()).items.map { it["title"] }).containsExactlyInAnyOrder("mine", "theirs")
        assertThat(holder.asked).containsExactly(setOf(Books.READ), setOf(EVERY_SHELF))
        assertThat(resource.count(emptyMap())).isEqualTo(CappedCount(2, exact = true))
        assertThat(resource.scopeOf(holder)).isSameAs(RowScope.Everything)
        assertThat(store.reads.map { it.scope }).allMatch { it === RowScope.Everything }
    }

    @Test
    fun `a caller without it reaches only the rows the other rule gives it, reads and writes alike`() {
        val (mine, theirs) = store.add(book("mine", "a", 1, T0), book("theirs", "b", 2, T0))
        val confined = signIn("a", *Books.EVERY_PERMISSION.toTypedArray())

        assertThat(resource.list(emptyMap()).items.map { it["title"] }).containsExactly("mine")
        assertThat(resource.count(emptyMap())).isEqualTo(CappedCount(1, exact = true))
        assertThat(faultOf { resource.get(theirs.toString(), emptyMap()) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(faultOf { resource.update(theirs, mapOf("pages" to 9)) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(faultOf { resource.delete(theirs) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(faultOf { resource.create(book("stray", "b", 1, T0)) }.code.value).isEqualTo("outside_scope")
        assertThat(resource.updateMany(setOf(mine, theirs), mapOf("pages" to 7))).isEqualTo(1)

        assertThat(resource.scopeOf(confined)).isInstanceOf(RowScope.Matching::class.java)
        assertThat(store.stored(theirs)).containsEntry("pages", 2)
        assertThat(store.stored(mine)).containsEntry("pages", 7)
    }

    @Test
    fun `the same resource lets a holder write on any shelf, and asks each caller anew`() {
        val (_, theirs) = store.add(book("mine", "a", 1, T0), book("theirs", "b", 2, T0))
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray(), EVERY_SHELF)

        val created = resource.create(book("elsewhere", "c", 3, T0))
        resource.update(theirs, mapOf("pages" to 9))
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray())

        assertThat(created).containsEntry("shelf", "c")
        assertThat(store.stored(theirs)).containsEntry("pages", 9)
        assertThat(resource.list(emptyMap()).items.map { it["title"] }).containsExactly("mine")
    }

    @Test
    fun `an anonymous caller is refused before the scope is decided`() {
        assertThat(faultOf { resource.list(emptyMap()) }.kind).isEqualTo(FaultKind.UNAUTHORIZED)
        assertThat(store.operations).isEmpty()
    }

    @Test
    fun `the rule names at least one permission, none blank or with whitespace`() {
        assertThatThrownBy { ScopeRule.EveryRowWhenHolding(emptySet(), OWN_SHELF) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("a scope that widens for a caller holding permissions names at least one")
        assertThatThrownBy { ScopeRule.EveryRowWhenHolding(setOf("book read"), OWN_SHELF) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("a permission is non-empty and has no whitespace")
        assertThat(ScopeRule.EveryRowWhenHolding(setOf("b.two", "a.one"), OWN_SHELF).toString())
            .isEqualTo("EveryRowWhenHolding(a.one, b.two)")
    }
}
