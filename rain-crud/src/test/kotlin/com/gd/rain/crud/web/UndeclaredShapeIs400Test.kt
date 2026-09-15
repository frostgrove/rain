package com.gd.rain.crud.web

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A list or count is served only for a declared query shape; anything else is 400 not_offered, naming what was asked. */
class UndeclaredShapeIs400Test {
    @Test
    fun `a filter no declared shape has is 400 not_offered naming the request`() =
        withBooks { world ->
            val problem = world.get("/books", "filter[isbn][eq]" to "978").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("not_offered")
            assertThat(problem["detail"].asString()).isEqualTo("no query shape of this resource is filters [filter[isbn][eq]], sort (none)")
        }

    @Test
    fun `a declared filter under a sort its shape does not declare is refused, and no nearest shape is served or named`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0))

            val problem = world.get("/books", "filter[shelf][eq]" to "a", "sort" to "title").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("not_offered")
            assertThat(problem["detail"].asString()).isEqualTo("no query shape of this resource is filters [filter[shelf][eq]], sort title")
            assertThat(problem.toString()).doesNotContain("createdAt")
            assertThat(world.get("/books", "filter[shelf][eq]" to "a", "sort" to "-createdAt").json()["items"].size()).isEqualTo(1)
        }

    @Test
    fun `more filters, another operator or another sort than a declared shape is not that shape`() =
        withBooks { world ->
            listOf(
                arrayOf("filter[shelf][eq]" to "a", "filter[createdAt][gte]" to "2026-09-15T10:00:00Z", "sort" to "-createdAt"),
                arrayOf("filter[createdAt][lt]" to "2026-09-15T10:00:00Z", "sort" to "-createdAt"),
                arrayOf("filter[createdAt][gte]" to "2026-09-15T10:00:00Z", "sort" to "createdAt"),
                arrayOf("filter[title][in]" to "dune"),
                arrayOf("sort" to "shelf"),
            ).forEach { request ->
                assertThat(world.get("/books", *request).problem(400)["code"].asString())
                    .describedAs(request.joinToString("&") { "${it.first}=${it.second}" })
                    .isEqualTo("not_offered")
            }
        }

    @Test
    fun `a count is served for the filters of a declared shape whatever its sort, and refused for any other`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0))

            val problem = world.get("/books/count", "filter[isbn][eq]" to "978").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("not_offered")
            assertThat(problem["detail"].asString()).isEqualTo("no query shape of this resource has the filters [filter[isbn][eq]]")
            assertThat(world.get("/books/count", "filter[createdAt][gte]" to "2026-09-15T10:00:00Z").json().toString())
                .isEqualTo("""{"count":{"value":1,"exact":true}}""")
        }

    @Test
    fun `a query with other problems is refused as bad_query before its shape is looked up`() =
        withBooks { world ->
            val problem = world.get("/books", "filter[isbn][eq]" to "978", "limit" to "0").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("bad_query")
            assertThat(problem.pointedCodes()).containsExactly("/limit out_of_range")
        }
}
