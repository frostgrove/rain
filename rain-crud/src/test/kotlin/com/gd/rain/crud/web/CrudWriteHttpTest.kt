package com.gd.rain.crud.web

import com.gd.rain.crud.Books
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.TestCaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * `CrudOperation.CREATE`, `UPDATE` and `REPLACE` used to have no handler, nothing read a write body, and `REPLACE`
 * meant a partial update. `CrudMvc.create`, `update` and `replace` now read strict JSON bodies: create is `POST`, a
 * partial update `PATCH`, and a replacement `PUT` stating every writable field; a versioned resource checks the version
 * a write states, and every write stays within the caller's scope.
 */
class CrudWriteHttpTest {
    private val everyField =
        """{"title":"dune","shelf":"a","pages":412,"price":12.50,"publishedOn":"1965-08-01","createdAt":"2026-09-15T10:00:00Z",""" +
            """"available":true,"isbn":null,"copies":5000000000}"""

    @Test
    fun `a create is 201 with the inserted item, every kind read from its JSON spelling`() =
        withBooks { world ->
            val response = world.post("/books", everyField)

            assertThat(response.status).isEqualTo(201)
            val id = UUID.fromString(response.json()["id"].asString())
            assertThat(world.store.stored(id))
                .containsEntry("title", "dune")
                .containsEntry("pages", 412)
                .containsEntry("price", BigDecimal("12.50"))
                .containsEntry("publishedOn", LocalDate.of(1965, 8, 1))
                .containsEntry("createdAt", T0)
                .containsEntry("available", true)
                .containsEntry("isbn", null)
                .containsEntry("copies", 5_000_000_000L)
        }

    @Test
    fun `every problem of a write body is refused together as 422`() =
        withBooks { world ->
            val body =
                """{"title":"dune","colour":"red","pages":"412","createdAt":"2026-09-15T10:00:00","copies":1.0,"shelf":null,""" +
                    """"available":1,"price":1e3,"publishedOn":{"y":1965}}"""

            val problem = world.post("/books", body).problem(422)

            assertThat(problem["code"].asString()).isEqualTo("validation_failed")
            assertThat(problem.pointedCodes()).containsExactly(
                "/available invalid_format",
                "/colour unknown_field",
                "/copies invalid_format",
                "/createdAt invalid_format",
                "/pages invalid_format",
                "/price invalid_format",
                "/publishedOn invalid_format",
                "/shelf required",
            )
            assertThat(world.store.operations).isEmpty()
        }

    @Test
    fun `a body that is not one JSON object of members named once is 400 malformed_body`() =
        withBooks { world ->
            listOf("", "[]", "\"dune\"", """{"title":"a"} {}""", """{"title":"a","title":"b"}""", """{"title":"a"""").forEach { body ->
                assertThat(world.post("/books", body).problem(400)["code"].asString()).describedAs(body).isEqualTo("malformed_body")
            }
        }

    @Test
    fun `a patch writes the fields it names, never the identifier, and names at least one`() =
        withBooks { world ->
            val id = world.store.add(book("dune", "a", 412, T0)).single()

            val body = world.patch("/books/$id", """{"pages":500,"isbn":"978"}""").json()

            assertThat(body["pages"].asInt()).isEqualTo(500)
            assertThat(body["title"].asString()).isEqualTo("dune")
            assertThat(world.store.stored(id)).containsEntry("isbn", "978")
            assertThat(
                world.patch("/books/$id", """{"id":"$id","pages":1}""").problem(422).pointedCodes(),
            ).containsExactly("/id field_not_granted")
            assertThat(world.patch("/books/$id", "{}").problem(422).pointedCodes()).containsExactly(" required")
            assertThat(world.patch("/books/${UUID.randomUUID()}", """{"pages":1}""").problem(404)["code"].asString()).isEqualTo("not_found")
        }

    @Test
    fun `a put replaces every writable field, and leaving one out is required, never NULL`() =
        withBooks { world ->
            val id = world.store.add(book("dune", "a", 412, T0, isbn = "978")).single()

            val partial = world.put("/books/$id", """{"title":"emma"}""").problem(422)

            assertThat(partial.pointedCodes()).containsExactly(
                "/available required",
                "/copies required",
                "/createdAt required",
                "/isbn required",
                "/pages required",
                "/price required",
                "/publishedOn required",
                "/shelf required",
            )
            assertThat(world.store.stored(id)).containsEntry("title", "dune").containsEntry("isbn", "978")

            val replaced = world.put("/books/$id", everyField.replace("\"dune\"", "\"emma\"")).json()
            assertThat(replaced["title"].asString()).isEqualTo("emma")
            assertThat(world.store.stored(id)).containsEntry("isbn", null).containsEntry("copies", 5_000_000_000L)
        }

    @Test
    fun `a versioned write states the version it read, and another version is 409 stale_version`() =
        withBooks { world ->
            val id = world.editions.add(book("dune", "a", 412, T0)).single()

            assertThat(world.patch("/editions/$id", """{"pages":5}""").problem(422).pointedCodes()).containsExactly("/version required")
            assertThat(world.patch("/editions/$id", """{"version":null,"pages":5}""").problem(422).pointedCodes())
                .containsExactly("/version required")
            assertThat(world.patch("/editions/$id", """{"version":1,"pages":5}""").json()["version"].asLong()).isEqualTo(2)
            assertThat(
                world.patch("/editions/$id", """{"version":1,"pages":6}""").problem(409)["code"].asString(),
            ).isEqualTo("stale_version")
            assertThat(world.put("/editions/$id", everyField.dropLast(1) + ""","version":1}""").problem(409)).isNotNull()
            assertThat(world.editions.stored(id)).containsEntry("pages", 5).containsEntry("version", 2L)
            assertThat(world.post("/editions", everyField.dropLast(1) + ""","version":7}""").problem(422).pointedCodes())
                .containsExactly("/version field_not_granted")
        }

    @Test
    fun `writes stay within the caller's scope`() =
        withBooks { world ->
            val (mine, theirs) = world.editions.add(book("mine", "a", 1, T0), book("theirs", "b", 1, T0))

            assertThat(world.post("/editions", everyField.replace("\"shelf\":\"a\"", "\"shelf\":\"b\"")).problem(403)["code"].asString())
                .isEqualTo("outside_scope")
            assertThat(world.patch("/editions/$mine", """{"version":1,"shelf":"b"}""").problem(403)["code"].asString())
                .isEqualTo("outside_scope")
            assertThat(world.patch("/editions/$theirs", """{"version":1,"pages":9}""").problem(404)).isNotNull()
            assertThat(world.editions.stored(mine)).containsEntry("shelf", "a").containsEntry("version", 1L)
            assertThat(world.editions.stored(theirs)).containsEntry("pages", 1)
            assertThat(world.post("/editions", everyField).status).isEqualTo(201)
        }

    @Test
    fun `each write needs its action's permission`() =
        withBooks { world ->
            val id = world.store.add(book("dune", "a", 412, T0)).single()
            world.callers.caller = TestCaller.of("a", Books.READ, Books.DELETE)

            assertThat(world.post("/books", everyField).problem(403)).isNotNull()
            assertThat(world.patch("/books/$id", """{"pages":1}""").problem(403)).isNotNull()
            assertThat(world.put("/books/$id", everyField).problem(403)).isNotNull()
            assertThat(world.store.stored(id)).containsEntry("pages", 412)
        }
}
