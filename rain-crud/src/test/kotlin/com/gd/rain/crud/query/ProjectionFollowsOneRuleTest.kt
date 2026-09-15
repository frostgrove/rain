package com.gd.rain.crud.query

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.crud.Books
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.MemoryStore
import com.gd.rain.crud.SwitchableCallers
import com.gd.rain.crud.TestCaller
import com.gd.rain.crud.faultOf
import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.pointedCodes
import com.gd.rain.persistence.id.UuidV7Ids
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultExecuteListenerProvider
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `fields=id` used to pass whatever `selectable` granted, and a reader that needed another column then threw
 * `RowShapeException`, a 500. One rule now decides every projection when the query is compiled: `fields` names only
 * fields `selectable` grants, the identifier included, and the projection holds every field the store's reader reads,
 * or the query is `400 bad_query` with `/fields required` — before any statement runs.
 */
class ProjectionFollowsOneRuleTest {
    private val titleOnly = listOf(QueryShape.of(SortKey.NONE))
    private val reader = TestCaller.of("a", Books.READ)

    private fun resource(
        store: com.gd.rain.crud.CrudStore<Map<String, Any?>>,
        selectable: FieldGrant = Books.SELECTABLE,
        shapes: List<QueryShape> = Books.SHAPES,
    ) = CrudResource(Books.rules(selectable = selectable, shapes = shapes), Books.policy(), store, SwitchableCallers(reader), emptyList())

    @Test
    fun `naming the identifier needs the selectable grant like any other field`() {
        val store = MemoryStore(Books.SCHEMA)
        val id = store.add(book("dune", "a", 412, T0)).single().toString()

        assertThat(faultOf { resource(store, FieldGrant.only("title"), titleOnly).list(mapOf("fields" to listOf("id"))) }.pointedCodes())
            .containsExactly("/fields field_not_granted")
        assertThat(faultOf { resource(store, FieldGrant.None, titleOnly).get(id, mapOf("fields" to listOf("id"))) }.pointedCodes())
            .containsExactly("/fields field_not_granted")
        assertThat(resource(store).list(mapOf("fields" to listOf("id"))).items.single()).containsOnlyKeys("id")
        assertThat(resource(store, FieldGrant.only("title"), titleOnly).list(mapOf("fields" to listOf("title"))).items.single())
            .containsOnlyKeys("id", "title")
    }

    @Test
    fun `a projection without a field the reader reads is refused at compile time and reaches no row`() {
        val store = MemoryStore(Books.SCHEMA, itemFields = setOf(Books.ID, Books.TITLE))
        val id = store.add(book("dune", "a", 412, T0)).single().toString()
        val resource = resource(store)

        val refused = faultOf { resource.list(mapOf("fields" to listOf("pages"))) }

        assertThat(refused.code.value).isEqualTo("bad_query")
        assertThat(refused.pointedCodes()).containsExactly("/fields required")
        assertThat(refused.violations.single().message).isEqualTo("names no title, which this resource reads to make an item")
        assertThat(faultOf { resource.get(id, mapOf("fields" to listOf("id"))) }.pointedCodes()).containsExactly("/fields required")
        assertThat(store.operations).isEmpty()
        assertThat(resource.list(mapOf("fields" to listOf("pages,title"))).items.single()).containsOnlyKeys("id", "title", "pages")
    }

    @Test
    fun `over jOOQ, a reader of more than the identifier never meets a row without its column`() {
        val executed = CopyOnWriteArrayList<String>()
        val create = DSL.using(SQLDialect.POSTGRES)
        val configuration =
            DefaultConfiguration()
                .set(MockConnection(MockDataProvider { arrayOf(MockResult(0, create.newResult())) }))
                .set(SQLDialect.POSTGRES)
                .set(DefaultExecuteListenerProvider(ExecuteListener.onExecuteStart { executed += checkNotNull(it.sql()) }))
        val titles = RowReader.of<Map<String, Any?>>(setOf(Books.ID, Books.TITLE)) { row -> mapOf("title" to row.requiredString("title")) }
        val resource = resource(JooqResourceStore(Books.SCHEMA, DSL.using(configuration), UuidV7Ids, titles))

        assertThat(faultOf { resource.list(mapOf("fields" to listOf("pages"))) }.pointedCodes()).containsExactly("/fields required")
        assertThat(executed).isEmpty()

        assertThat(resource.list(mapOf("fields" to listOf("title"))).items).isEmpty()
        assertThat(executed).hasSize(1)
    }

    @Test
    fun `a reader of fields the schema does not declare, or that selectable withholds, is a declaration problem`() {
        val stranger = SchemaField("colour", "colour", FieldKind.TEXT, nullable = false)

        assertThatThrownBy { resource(MemoryStore(Books.SCHEMA, itemFields = setOf(Books.ID, stranger))) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .hasMessageContaining("reads colour, which is not a field")
        assertThatThrownBy { resource(MemoryStore(Books.SCHEMA, itemFields = setOf(Books.ID, Books.ISBN))) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .hasMessageContaining("reads isbn, which selectable does not grant, so no fields selection could be answered")
        assertThat(resource(MemoryStore(Books.SCHEMA, itemFields = setOf(Books.ID, Books.ISBN)), FieldGrant.None, titleOnly)).isNotNull()
        assertThatThrownBy {
            JooqResourceStore(
                Books.SCHEMA,
                DSL.using(SQLDialect.POSTGRES),
                UuidV7Ids,
                RowReader.of(setOf(stranger)) { it },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
