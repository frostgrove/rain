package com.gd.rain.crud

import com.gd.rain.core.error.FaultKind
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Gap 24: writes used to skip the policy scope. Every update and delete, single or bulk, now carries it: a
 * row outside the caller's scope is not found (or not counted), and it is left exactly as it was.
 */
@Tag("integration")
class ScopedUpdateDeleteIT {
    private val database = BookDatabase("crud_scope")
    private val callers = SwitchableCallers(TestCaller.of("a", *Books.EVERY_PERMISSION.toTypedArray()))
    private val resource = CrudResource(Books.rules(), Books.policy(Books.SHELF_SCOPE), database.store, callers, emptyList())
    private val mine = database.insert(book("mine", "a", 100, T0)).single()
    private val theirs = database.insert(book("theirs", "b", 200, T0)).single()

    @Test
    fun `updating a row outside the scope is not found and leaves it unchanged`() {
        val before = database.stored(theirs)

        assertThat(faultOf { resource.update(theirs, mapOf("pages" to 1, "title" to "taken")) }.kind).isEqualTo(FaultKind.NOT_FOUND)

        assertThat(database.stored(theirs)).isEqualTo(before)
        assertThat(resource.update(mine, mapOf("pages" to 1))).containsEntry("pages", 1).containsEntry("title", "mine")
    }

    @Test
    fun `a bulk update writes and counts only the rows inside the scope`() {
        val before = database.stored(theirs)

        assertThat(resource.updateMany(setOf(mine, theirs), mapOf("pages" to 7))).isEqualTo(1)

        assertThat(database.stored(theirs)).isEqualTo(before)
        assertThat(database.stored(mine)).containsEntry("pages", 7)
    }

    @Test
    fun `deleting a row outside the scope is not found and the row stays`() {
        assertThat(faultOf { resource.delete(theirs) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(faultOf { resource.delete(theirs.toString()) }.kind).isEqualTo(FaultKind.NOT_FOUND)

        assertThat(database.stored(theirs)).isNotNull()
    }

    @Test
    fun `a bulk delete deletes and counts only the rows inside the scope`() {
        assertThat(resource.deleteMany(setOf(theirs))).isZero()
        assertThat(resource.bulkDelete(listOf(theirs.toString(), mine.toString()))).isEqualTo(1)

        assertThat(database.stored(theirs)).isNotNull()
        assertThat(database.stored(mine)).isNull()
    }

    @Test
    fun `reads, counts and one-item reads see only the scope`() {
        assertThat(resource.list(emptyMap()).ids()).containsExactly(mine)
        assertThat(resource.count(emptyMap())).isEqualTo(CappedCount(1, exact = true))
        assertThat(faultOf { resource.get(theirs.toString(), emptyMap()) }.kind).isEqualTo(FaultKind.NOT_FOUND)
    }

    @Test
    fun `each caller's scope is its own`() {
        callers.caller = TestCaller.of("b", *Books.EVERY_PERMISSION.toTypedArray())

        assertThat(resource.update(theirs, mapOf("pages" to 3))).containsEntry("pages", 3)
        assertThat(faultOf { resource.update(mine, mapOf("pages" to 3)) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(database.stored(mine)).containsEntry("pages", 100)
    }
}
