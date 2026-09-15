package com.gd.rain.crud

import com.gd.rain.crud.query.DialectV1
import com.gd.rain.crud.query.QueryCompiler
import com.gd.rain.crud.web.pointedCodes
import com.gd.rain.crud.web.problem
import com.gd.rain.crud.web.withBooks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The bulk size refusal used to be `bad_request` while every other bound was `bad_query`, and "this resource offers no
 * count" pointed at the root on the count route but at `/count` on a list. Every bound refusal is now `400 bad_query`
 * with an `out_of_range` violation at what exceeded it, and a missing count is always at `/count`.
 */
class BoundRefusalsShareOneCodeTest {
    private val resource =
        CrudResource(
            Books.rules(),
            Books.policy(),
            MemoryStore(Books.SCHEMA),
            SwitchableCallers(TestCaller.of("a", *Books.EVERY_PERMISSION.toTypedArray())),
            emptyList(),
        )
    private val tooMany = List(501) { UUID(0, it.toLong()) }

    @Test
    fun `more ids than maxBulkIds is bad_query with out_of_range at ids, like every other bound`() {
        listOf(
            faultOf { resource.deleteMany(tooMany.toSet()) },
            faultOf { resource.updateMany(tooMany.toSet(), mapOf("pages" to 1)) },
            faultOf { resource.bulkDelete { tooMany.map(UUID::toString) } },
            faultOf { resource.list(mapOf("limit" to listOf("51"))) },
        ).forEach { refused ->
            assertThat(refused.code.value).isEqualTo("bad_query")
            assertThat(
                refused.violations
                    .single()
                    .code.value,
            ).isEqualTo("out_of_range")
        }
        withBooks { world ->
            val body = tooMany.joinToString(",", """{"ids":[""", "]}") { "\"$it\"" }

            val problem = world.post("/books/bulk-delete", body).problem(400)

            assertThat(problem["code"].asString()).isEqualTo("bad_query")
            assertThat(problem.pointedCodes()).containsExactly("/ids out_of_range")
        }
    }

    @Test
    fun `no count offered points at count on the count route and on a list`() {
        val uncapped = QueryCompiler(Books.SCHEMA, Books.rules(countCap = null), emptySet(), setOf(Books.ID))

        val onCount = faultOf { uncapped.count(DialectV1.parse(emptyMap())) }
        val onList = faultOf { uncapped.list(DialectV1.parse(mapOf("count" to listOf("capped")))) }

        assertThat(onCount.code.value).isEqualTo("bad_query")
        assertThat(onCount.pointedCodes()).containsExactly("/count not_offered")
        assertThat(onList.pointedCodes()).containsExactly("/count not_offered")
        assertThat(onCount.violations.single().message).isEqualTo(onList.violations.single().message)
    }
}
