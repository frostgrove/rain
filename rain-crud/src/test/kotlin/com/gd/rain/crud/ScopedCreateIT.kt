package com.gd.rain.crud

import com.gd.rain.core.error.FaultKind
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.query.Projection
import com.gd.rain.persistence.id.UuidV7Ids
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A create used to ignore the row scope: `CrudStore.insert` took none, so a caller confined to shelf `a` could insert
 * a row on shelf `b`. The store now checks the row as the database stored it against the scope, with the condition a
 * read applies, in the insert's transaction — and so does every write that leaves a row behind. A row outside the
 * scope is `403 outside_scope` and nothing is written.
 */
@Tag("integration")
class ScopedCreateIT {
    private val database = BookDatabase("crud_scoped_create")
    private val callers = SwitchableCallers(TestCaller.of("a", *Books.EVERY_PERMISSION.toTypedArray()))
    private val store = JooqResourceStore(Books.VERSIONED, database.dsl, UuidV7Ids, RowReader.fields(Books.VERSIONED))
    private val resource = CrudResource(Books.rules(), Books.policy(Books.SHELF_SCOPE), store, callers, emptyList())

    private fun stored(id: UUID): Map<String, Any?>? = store.find(id, RowScope.Everything, Projection.Full)

    private fun rows(): Long = checkNotNull(database.jdbc.queryForObject("SELECT count(*) FROM public.books", Long::class.java))

    @Test
    fun `a create outside the caller's scope is 403 outside_scope and inserts nothing`() {
        val refused = faultOf { resource.create(book("stray", "b", 1, T0)) }

        assertThat(refused.kind).isEqualTo(FaultKind.FORBIDDEN)
        assertThat(refused.code.value).isEqualTo("outside_scope")
        assertThat(rows()).isZero()
    }

    @Test
    fun `a create inside the caller's scope is inserted at version 1, and each caller's scope is its own`() {
        val mine = resource.create(book("mine", "a", 1, T0))

        assertThat(mine).containsEntry("shelf", "a").containsEntry("version", 1L)
        callers.caller = TestCaller.of("b", *Books.EVERY_PERMISSION.toTypedArray())
        assertThat(resource.create(book("theirs", "b", 1, T0))).containsEntry("shelf", "b")
        assertThat(faultOf { resource.create(book("stray", "a", 1, T0)) }.code.value).isEqualTo("outside_scope")
        assertThat(rows()).isEqualTo(2)
    }

    @Test
    fun `the database decides with the row as stored, not the values as sent`() {
        database.jdbc.execute(
            """
            CREATE FUNCTION public.shelve_moved() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.title = 'moved' THEN NEW.shelf := 'b'; END IF;
                RETURN NEW;
            END
            $$;
            CREATE TRIGGER books_shelve_moved BEFORE INSERT OR UPDATE ON public.books FOR EACH ROW EXECUTE FUNCTION public.shelve_moved();
            """,
        )

        assertThat(faultOf { resource.create(book("moved", "a", 1, T0)) }.code.value).isEqualTo("outside_scope")
        assertThat(rows()).isZero()

        val kept = resource.create(book("kept", "a", 1, T0))
        assertThat(faultOf { resource.update(kept["id"] as UUID, mapOf("version" to 1L, "title" to "moved")) }.code.value)
            .isEqualTo("outside_scope")
        assertThat(stored(kept["id"] as UUID)).containsEntry("title", "kept").containsEntry("shelf", "a")
    }

    @Test
    fun `an update that would move a row out of the scope is refused and leaves it as it was`() {
        val id = resource.create(book("mine", "a", 1, T0))["id"] as UUID

        val refused = faultOf { resource.update(id, mapOf("version" to 1L, "shelf" to "b")) }

        assertThat(refused.kind).isEqualTo(FaultKind.FORBIDDEN)
        assertThat(refused.code.value).isEqualTo("outside_scope")
        assertThat(stored(id)).containsEntry("shelf", "a").containsEntry("version", 1L)
    }

    @Test
    fun `a bulk update that would move one row out of the scope writes none of them`() {
        val first = resource.create(book("first", "a", 1, T0))["id"] as UUID
        val second = resource.create(book("second", "a", 2, T0))["id"] as UUID

        assertThat(faultOf { resource.updateMany(setOf(first, second), mapOf("shelf" to "b")) }.code.value).isEqualTo("outside_scope")

        assertThat(stored(first)).containsEntry("shelf", "a").containsEntry("version", 1L)
        assertThat(stored(second)).containsEntry("shelf", "a").containsEntry("version", 1L)
        assertThat(resource.updateMany(setOf(first, second), mapOf("pages" to 9))).isEqualTo(2)
        assertThat(stored(second)).containsEntry("pages", 9).containsEntry("version", 2L)
    }
}
