package com.gd.rain.crud.web

import com.gd.rain.crud.Books
import com.gd.rain.crud.Caller
import com.gd.rain.crud.TestCaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse

/**
 * `CrudMvc.bulkDelete` used to read the body before the resource checked the caller, so an anonymous caller with a
 * malformed body was `400 malformed_body` instead of `401`. Every handler now hands the resource its input unread, and
 * the resource authorizes first: an identifier or a body is read only for a caller who may do the operation.
 */
class UnauthenticatedMalformedBodyIs401Test {
    private val malformed = "{not json"

    private fun BooksWorld.writes(id: String): Map<String, () -> MockHttpServletResponse> =
        linkedMapOf(
            "POST /books" to { post("/books", malformed) },
            "POST /books/bulk-delete" to { post("/books/bulk-delete", malformed) },
            "PATCH /books/{id}" to { patch("/books/$id", malformed) },
            "PUT /books/{id}" to { put("/books/$id", malformed) },
        )

    @Test
    fun `an anonymous caller with a malformed body and identifier is 401 on every write route, and reaches no row`() =
        withBooks { world ->
            world.callers.caller = Caller.Anonymous

            world.writes("not-an-id").forEach { (route, send) ->
                assertThat(send().problem(401)["code"].asString()).describedAs(route).isEqualTo("unauthenticated")
            }
            assertThat(world.store.operations).isEmpty()
        }

    @Test
    fun `a caller without the permission is 403 whatever it sent`() =
        withBooks { world ->
            world.callers.caller = TestCaller.of("a", Books.READ)

            world.writes("not-an-id").forEach { (route, send) ->
                assertThat(send().problem(403)["code"].asString()).describedAs(route).isEqualTo("forbidden")
            }
            assertThat(world.store.operations).isEmpty()
        }

    @Test
    fun `an authorized caller is told what it sent cannot be read, the identifier before the body`() =
        withBooks { world ->
            val id = "0192f1c0-0000-7000-8000-000000000001"

            world.writes(id).forEach { (route, send) ->
                assertThat(send().problem(400)["code"].asString()).describedAs(route).isEqualTo("malformed_body")
            }
            world.writes("not-an-id").filterKeys { it.endsWith("{id}") }.forEach { (route, send) ->
                assertThat(send().problem(400).pointedCodes()).describedAs(route).containsExactly("/id invalid_id")
            }
            assertThat(world.store.operations).isEmpty()
        }
}
