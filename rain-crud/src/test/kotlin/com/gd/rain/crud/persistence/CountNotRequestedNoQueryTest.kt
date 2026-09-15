package com.gd.rain.crud.persistence

import com.gd.rain.crud.Books
import com.gd.rain.crud.CappedCount
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.SwitchableCallers
import com.gd.rain.crud.TestCaller
import com.gd.rain.crud.faultOf
import com.gd.rain.persistence.id.UuidV7Ids
import org.assertj.core.api.Assertions.assertThat
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultExecuteListenerProvider
import org.jooq.impl.SQLDataType
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Gap 27: every list used to run `count(*)`; now a count statement runs only for `count=capped`. Statements are captured by a jOOQ `ExecuteListener`. */
class CountNotRequestedNoQueryTest {
    private val executed = CopyOnWriteArrayList<String>()

    private fun resource(countCap: Long?): CrudResource<Map<String, Any?>> {
        val create = DSL.using(SQLDialect.POSTGRES)
        val answers =
            MockDataProvider { context ->
                if (context.sql().contains("count(")) {
                    val counted = DSL.field(DSL.name("count"), SQLDataType.BIGINT)
                    val result = create.newResult(counted)
                    result.add(create.newRecord(counted).values(3L))
                    arrayOf(MockResult(1, result))
                } else {
                    arrayOf(MockResult(0, create.newResult()))
                }
            }
        val configuration =
            DefaultConfiguration()
                .set(MockConnection(answers))
                .set(SQLDialect.POSTGRES)
                .set(DefaultExecuteListenerProvider(ExecuteListener.onExecuteStart { executed += checkNotNull(it.sql()) }))
        val store = JooqResourceStore(Books.SCHEMA, DSL.using(configuration), UuidV7Ids, RowReader.fields(Books.SCHEMA))
        return CrudResource(
            Books.rules(countCap = countCap),
            Books.policy(),
            store,
            SwitchableCallers(TestCaller.of("a", Books.READ)),
            emptyList(),
        )
    }

    @Test
    fun `a list without count executes one bounded read and no count`() {
        val page = resource(countCap = 50).list(mapOf("filter[shelf][eq]" to listOf("a")))

        assertThat(page.count).isNull()
        assertThat(executed).hasSize(1)
        assertThat(executed.single()).doesNotContain("count(").contains("fetch next ? rows only")
    }

    @Test
    fun `an offset list without count executes no count either`() {
        resource(countCap = 50).list(mapOf("sort" to listOf("pages"), "offset" to listOf("20")))

        assertThat(executed).hasSize(1)
        assertThat(executed.single()).doesNotContain("count(")
    }

    @Test
    fun `a list with count=capped also executes the capped count`() {
        val page = resource(countCap = 50).list(mapOf("count" to listOf("capped")))

        assertThat(page.count).isEqualTo(CappedCount(3, exact = true))
        assertThat(executed).hasSize(2)
        assertThat(executed.filter { it.contains("count(") }).hasSize(1)
    }

    @Test
    fun `count=capped on a resource without a cap is refused before any statement`() {
        val refused = faultOf { resource(countCap = null).list(mapOf("count" to listOf("capped"))) }

        assertThat(refused.violations.map { "${it.pointer} ${it.code.value}" }).containsExactly("/count not_offered")
        assertThat(executed).isEmpty()
    }
}
