package com.gd.rain.crud.web

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Gap 28: `fields` names only selectable fields; anything else is a 400, never silently dropped. */
class UndeclaredSelectIs400Test {
    @Test
    fun `selecting a declared field the resource does not grant is 400 field_not_granted`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0, isbn = "978"))

            val problem = world.get("/books", "fields" to "title,isbn").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("bad_query")
            assertThat(problem.pointedCodes()).containsExactly("/fields field_not_granted")
            assertThat(problem["errors"][0]["message"].asString()).isEqualTo("isbn is not selectable")
        }

    @Test
    fun `selecting a field nothing declares is 400 unknown_field`() =
        withBooks { world ->
            assertThat(world.get("/books", "fields" to "secret").problem(400).pointedCodes()).containsExactly("/fields unknown_field")
        }

    @Test
    fun `a granted selection answers the identifier and those fields only`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0))

            val body = world.get("/books", "fields" to "title").json()

            assertThat(body["items"][0].propertyNames().toList()).containsExactly("id", "title")
        }

    @Test
    fun `one item follows the same grant`() =
        withBooks { world ->
            val id = world.store.add(book("dune", "a", 412, T0)).single()

            assertThat(world.get("/books/$id", "fields" to "isbn").problem(400).pointedCodes()).containsExactly("/fields field_not_granted")
            assertThat(
                world
                    .get("/books/$id", "fields" to "pages")
                    .json()
                    .propertyNames()
                    .toList(),
            ).containsExactly("id", "pages")
        }
}

/** Gap 29: an unknown parameter used to be ignored unless one typo from a known one; now every unknown name is 400. */
class TypoParameterIs400Test {
    @Test
    fun `a parameter one letter away from limit is 400 unknown_parameter naming it`() =
        withBooks { world ->
            val problem = world.get("/books", "limt" to "5").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("unknown_parameter")
            assertThat(problem.pointedCodes()).containsExactly("/limt unknown_parameter")
        }

    @Test
    fun `a parameter name in another case is not the parameter`() =
        withBooks { world ->
            assertThat(world.get("/books", "Limit" to "5").problem(400).pointedCodes()).containsExactly("/Limit unknown_parameter")
        }

    @Test
    fun `a parameter nobody knows is refused rather than ignored, and every one is named`() =
        withBooks { world ->
            val problem = world.get("/books", "utm_source" to "mail", "page" to "2", "limit" to "5").problem(400)

            assertThat(problem.pointedCodes()).containsExactly("/page unknown_parameter", "/utm_source unknown_parameter")
        }

    @Test
    fun `unknown names are refused before anything else about the query is read`() =
        withBooks { world ->
            assertThat(
                world.get("/books", "limt" to "5", "limit" to "999").problem(400).pointedCodes(),
            ).containsExactly("/limt unknown_parameter")
        }

    @Test
    fun `a filter parameter without both its field and operator parts is an unknown parameter`() =
        withBooks { world ->
            assertThat(
                world.get("/books", "filter[title]" to "dune").problem(400).pointedCodes(),
            ).containsExactly("/filter[title] unknown_parameter")
            assertThat(world.get("/books", "filters[title][eq]" to "dune").problem(400).pointedCodes())
                .containsExactly("/filters[title][eq] unknown_parameter")
        }
}

/** Gap 29: an operator used to be retried lower-cased; now `EQ` is not `eq`, and a field name is matched exactly too. */
class UppercaseOperatorIs400Test {
    @Test
    fun `EQ is not an operator`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0))

            val problem = world.get("/books", "filter[title][EQ]" to "dune").problem(400)

            assertThat(problem["code"].asString()).isEqualTo("bad_query")
            assertThat(problem.pointedCodes()).containsExactly("/filter/title/EQ unknown_operator")
        }

    @Test
    fun `the operator as the dialect spells it is served`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0), book("emma", "a", 1, T0))

            assertThat(world.get("/books", "filter[title][eq]" to "dune").json()["items"].size()).isEqualTo(1)
        }

    @Test
    fun `a field name in another case names no field`() =
        withBooks { world ->
            assertThat(
                world.get("/books", "filter[Title][eq]" to "dune").problem(400).pointedCodes(),
            ).containsExactly("/filter/Title/eq unknown_field")
        }

    @Test
    fun `a spelling other tools use for an operator is not an alias`() =
        withBooks { world ->
            assertThat(world.get("/books", "filter[pages][>=]" to "1", "filter[pages][\$gte]" to "1").problem(400).pointedCodes())
                .containsExactly("/filter/pages/\$gte unknown_operator", "/filter/pages/>= unknown_operator")
        }
}

/** Gap 29: a timestamp without an offset used to be read in UTC; now it is 400. */
class ZonelessTimestampIs400Test {
    @Test
    fun `a date-time without an offset is 400 invalid_format`() =
        withBooks { world ->
            val problem = world.get("/books", "filter[createdAt][gte]" to "2026-09-15T10:00:00").problem(400)

            assertThat(problem.pointedCodes()).containsExactly("/filter/createdAt/gte invalid_format")
            assertThat(problem["errors"][0]["message"].asString()).isEqualTo("the value has no offset; a timestamp ends in Z or ±hh:mm")
        }

    @Test
    fun `a date-time with a space instead of T is refused`() =
        withBooks { world ->
            assertThat(world.get("/books", "filter[createdAt][gte]" to "2026-09-15 10:00:00").problem(400).pointedCodes())
                .containsExactly("/filter/createdAt/gte invalid_format")
        }

    @Test
    fun `the same instant with Z or with an offset is served`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0))

            fun served(
                operator: String,
                value: String,
            ) = world.get("/books", "filter[createdAt][$operator]" to value, "sort" to "-createdAt").json()["items"].size()

            assertThat(served("gte", "2026-09-15T10:00:00Z")).isEqualTo(1)
            assertThat(served("gte", "2026-09-15T12:00:00+02:00")).isEqualTo(1)
            assertThat(served("gt", "2026-09-15T12:00:00+02:00")).isZero()
        }
}

/** Gap 29: a bare date used to be midnight UTC on a timestamp field; now a date is accepted only by a date field. */
class DateOnlyOnTimestampIs400Test {
    @Test
    fun `a date alone on a timestamp field is 400 invalid_format`() =
        withBooks { world ->
            val problem = world.get("/books", "filter[createdAt][gte]" to "2026-09-15").problem(400)

            assertThat(problem.pointedCodes()).containsExactly("/filter/createdAt/gte invalid_format")
            assertThat(
                problem["errors"][0]["message"].asString(),
            ).isEqualTo("the value is a date; this field is a timestamp and needs a time and an offset")
        }

    @Test
    fun `a date alone on a date field is served`() =
        withBooks { world ->
            world.store.add(book("dune", "a", 412, T0, publishedOn = LocalDate.of(1965, 8, 1)))

            assertThat(world.get("/books", "filter[publishedOn][eq]" to "1965-08-01").json()["items"].size()).isEqualTo(1)
        }

    @Test
    fun `a date-time on a date field is refused`() =
        withBooks { world ->
            assertThat(world.get("/books", "filter[publishedOn][eq]" to "1965-08-01T00:00:00Z").problem(400).pointedCodes())
                .containsExactly("/filter/publishedOn/eq invalid_format")
        }
}

/** Gap 29: a limit above the maximum used to be clamped; now it is 400. */
class LimitAboveMaxIs400Test {
    @Test
    fun `a limit above the maximum is 400 out_of_range, not clamped`() =
        withBooks { world ->
            val problem = world.get("/books", "limit" to "51").problem(400)

            assertThat(problem.pointedCodes()).containsExactly("/limit out_of_range")
            assertThat(problem["errors"][0]["message"].asString()).isEqualTo("is above this resource's maximum of 50")
        }

    @Test
    fun `the maximum itself is served as asked, and no limit serves the declared default`() =
        withBooks { world ->
            assertThat(world.get("/books", "limit" to "50").json()["page"]["limit"].asInt()).isEqualTo(50)
            assertThat(world.get("/books").json()["page"]["limit"].asInt()).isEqualTo(10)
        }

    @Test
    fun `zero, negative, oversized and non-numeric limits are refused`() =
        withBooks { world ->
            assertThat(world.get("/books", "limit" to "0").problem(400).pointedCodes()).containsExactly("/limit out_of_range")
            assertThat(world.get("/books", "limit" to "99999999999").problem(400).pointedCodes()).containsExactly("/limit out_of_range")
            assertThat(world.get("/books", "limit" to "-1").problem(400).pointedCodes()).containsExactly("/limit invalid_format")
            assertThat(world.get("/books", "limit" to "ten").problem(400).pointedCodes()).containsExactly("/limit invalid_format")
        }
}
