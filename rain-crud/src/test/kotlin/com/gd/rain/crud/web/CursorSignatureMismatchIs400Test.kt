package com.gd.rain.crud.web

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

/** Gaps 25/27: a cursor carries the SHA-256 of the effective order it was minted for; presented with another order it is 400. */
class CursorSignatureMismatchIs400Test {
    private val json = JsonMapper.builder().build()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun BooksWorld.firstCursor(): String {
        repeat(3) { store.add(book("t$it", "a", 1, T0.plusSeconds(it.toLong()))) }
        return get("/books", "sort" to "-createdAt", "limit" to "2").json()["page"]["next"].asString()
    }

    @Test
    fun `a cursor presented with another sort is 400 invalid_cursor`() =
        withBooks { world ->
            val next = world.firstCursor()

            val problem = world.get("/books", "sort" to "title", "limit" to "2", "cursor" to next).problem(400)

            assertThat(problem["code"].asString()).isEqualTo("invalid_cursor")
            assertThat(problem.pointedCodes()).containsExactly("/cursor invalid_cursor")
            assertThat(problem["errors"][0]["message"].asString()).isEqualTo("the cursor was made for a different sort order")
            assertThat(world.get("/books", "limit" to "2", "cursor" to next).problem(400)["code"].asString()).isEqualTo("invalid_cursor")
            assertThat(world.get("/books", "sort" to "-createdAt", "limit" to "2", "cursor" to next).status).isEqualTo(200)
        }

    @Test
    fun `a cursor whose signature was rewritten is refused`() =
        withBooks { world ->
            val next = world.firstCursor()
            val payload = json.readTree(Base64.getUrlDecoder().decode(next)) as ObjectNode
            payload.put("sig", encoder.encodeToString(ByteArray(32)))

            val forged = encoder.encodeToString(json.writeValueAsBytes(payload))

            assertThat(world.get("/books", "sort" to "-createdAt", "cursor" to forged).problem(400)["errors"][0]["message"].asString())
                .isEqualTo("the cursor was made for a different sort order")
        }

    @Test
    fun `a token that is not a version 1 cursor is 400 invalid_cursor`() =
        withBooks { world ->
            val next = world.firstCursor()
            // Trailing JSON whitespace until the byte count needs padding: the same object, spelled with '=' at the end.
            var bytes = Base64.getUrlDecoder().decode(next)
            while (bytes.size % 3 != 1) bytes += ' '.code.toByte()
            val padded = Base64.getUrlEncoder().encodeToString(bytes)

            listOf(
                "abc!",
                encoder.encodeToString("{}".toByteArray()),
                encoder.encodeToString("[1]".toByteArray()),
                padded,
            ).forEach { token ->
                val problem = world.get("/books", "sort" to "-createdAt", "cursor" to token).problem(400)
                assertThat(problem.pointedCodes()).describedAs(token).containsExactly("/cursor invalid_cursor")
            }
        }

    @Test
    fun `the rest of the query is refused before the cursor is read`() =
        withBooks { world ->
            val problem = world.get("/books", "sort" to "-createdAt", "limit" to "0", "cursor" to "abc!").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("bad_query")
            assertThat(problem.pointedCodes()).containsExactly("/limit out_of_range")
        }
}
