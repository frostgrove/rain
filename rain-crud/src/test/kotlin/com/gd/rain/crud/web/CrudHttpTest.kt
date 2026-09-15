package com.gd.rain.crud.web

import com.gd.rain.crud.Books
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.Caller
import com.gd.rain.crud.TestCaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.math.BigDecimal
import java.time.LocalDate

/** A mounted resource over HTTP: the page shapes, the one-item routes and the refusals, rendered by rain-web. */
class CrudHttpTest {
    private fun BooksWorld.three() = store.add(*(0 until 3).map { book("t$it", "a", it, T0.plusSeconds(it.toLong())) }.toTypedArray())

    @Test
    fun `a cursor page renders items and page, with next and prev only where there is a page that way`() =
        withBooks { world ->
            world.three()

            val first = world.get("/books", "sort" to "-createdAt", "limit" to "2").json()
            val second = world.get("/books", "sort" to "-createdAt", "limit" to "2", "cursor" to first["page"]["next"].asString()).json()

            assertThat(first.propertyNames().toList()).containsExactly("items", "page")
            assertThat(first["page"].propertyNames().toList()).containsExactly("limit", "next")
            assertThat(first["items"].values().map { it["title"].asString() }).containsExactly("t2", "t1")
            assertThat(second["page"].propertyNames().toList()).containsExactly("limit", "prev")
            assertThat(second["items"].values().map { it["title"].asString() }).containsExactly("t0")
        }

    @Test
    fun `an offset page renders limit, offset and hasNext`() =
        withBooks { world ->
            world.three()

            val body = world.get("/books", "sort" to "pages", "offset" to "1", "limit" to "1").json()

            assertThat(body["page"].toString()).isEqualTo("""{"limit":1,"offset":1,"hasNext":true}""")
            assertThat(body["items"][0]["title"].asString()).isEqualTo("t1")
        }

    @Test
    fun `a counted page and the count route render a capped count`() =
        withBooks { world ->
            world.three()
            world.store.add(*(0 until 60).map { book("b$it", "b", it, T0) }.toTypedArray())

            assertThat(world.get("/books", "filter[shelf][eq]" to "a", "count" to "capped").json()["count"].toString())
                .isEqualTo("""{"value":3,"exact":true}""")
            assertThat(
                world.get("/books/count", "filter[shelf][eq]" to "a").json().toString(),
            ).isEqualTo("""{"count":{"value":3,"exact":true}}""")
            assertThat(world.get("/books/count").json().toString()).isEqualTo("""{"count":{"value":50,"exact":false}}""")
        }

    @Test
    fun `an item renders every kind as JSON, with an included relation`() =
        withBooks { world ->
            val id = world.store.add(book("dune", "a", 412, T0, BigDecimal("12.50"), LocalDate.of(1965, 8, 1))).single()

            val response = world.get("/books/$id", "include" to "reviews")
            val body = response.json()

            assertThat(body["id"].asString()).isEqualTo(id.toString())
            assertThat(body["createdAt"].asString()).isEqualTo("2026-09-15T10:00:00Z")
            assertThat(body["publishedOn"].asString()).isEqualTo("1965-08-01")
            assertThat(response.contentAsString).contains("\"price\":12.50")
            assertThat(body["reviews"][0].asString()).isEqualTo("fine")
        }

    @Test
    fun `an identifier that is not canonical is 400 invalid_id, and one that matches nothing is 404`() =
        withBooks { world ->
            val malformed = world.get("/books/not-an-id").problem(400)

            assertThat(malformed["code"].asString()).isEqualTo("invalid_id")
            assertThat(malformed.pointedCodes()).containsExactly("/id invalid_id")
            assertThat(world.get("/books/0192f1c0-0000-7000-8000-000000000001").problem(404)["code"].asString()).isEqualTo("not_found")
        }

    @Test
    fun `a delete answers deleted, and the same delete again is 404`() =
        withBooks { world ->
            val id = world.three().first()

            val deleted =
                world.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/books/$id"),
                )

            assertThat(deleted.json().toString()).isEqualTo("""{"deleted":1}""")
            assertThat(
                world
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/books/$id"),
                    ).problem(404),
            ).isNotNull()
        }

    @Test
    fun `a bulk delete reads exactly an ids array of strings`() =
        withBooks { world ->
            val ids = world.three()

            fun bulk(body: String) = world.perform(post("/books/bulk-delete").contentType(MediaType.APPLICATION_JSON).content(body))

            assertThat(bulk("""{"ids":["${ids[0]}","${ids[1]}"]}""").json().toString()).isEqualTo("""{"deleted":2}""")
            listOf("""{"ids":[1]}""", """{"ids":[],"force":true}""", """["${ids[2]}"]""", """{"ids":["a"],"ids":["b"]}""").forEach { body ->
                assertThat(bulk(body).problem(400)["code"].asString()).describedAs(body).isEqualTo("malformed_body")
            }
            assertThat(bulk("""{"ids":["nope"]}""").problem(400).pointedCodes()).containsExactly("/ids/0 invalid_id")
        }

    @Test
    fun `an anonymous caller is 401 before the query is read, and a caller without the permission 403`() =
        withBooks { world ->
            world.callers.caller = Caller.Anonymous
            assertThat(world.get("/books", "limt" to "1").problem(401)["code"].asString()).isEqualTo("unauthenticated")

            world.callers.caller = TestCaller.of("a", Books.WRITE)
            assertThat(world.get("/books").problem(403)["code"].asString()).isEqualTo("forbidden")
        }
}
