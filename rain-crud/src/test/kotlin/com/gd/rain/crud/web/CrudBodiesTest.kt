package com.gd.rain.crud.web

import com.gd.rain.core.error.Fault
import com.gd.rain.crud.Books
import com.gd.rain.crud.faultOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** How a write body is read: one JSON type and one spelling per kind, and violations that travel with the values. */
class CrudBodiesTest {
    private fun read(body: String) = CrudBodies.write(Books.VERSIONED, body)

    private fun refusals(body: String) = read(body).violations.map { "${it.pointer} ${it.code.value} ${it.message}" }

    @Test
    fun `each kind reads its JSON type with the dialect's spelling`() {
        val input =
            read(
                """{"id":"0192F1C0-0000-7000-8000-00000000000A","pages":-7,"copies":9223372036854775807,"price":-0.50,""" +
                    """"createdAt":"2026-09-15t12:00:00+02:00","available":false,"isbn":null,"version":3}""",
            )

        assertThat(input.violations).isEmpty()
        assertThat(input.values).containsExactly(
            org.assertj.core.api.Assertions
                .entry("id", UUID.fromString("0192f1c0-0000-7000-8000-00000000000a")),
            org.assertj.core.api.Assertions
                .entry("pages", -7),
            org.assertj.core.api.Assertions
                .entry("copies", Long.MAX_VALUE),
            org.assertj.core.api.Assertions
                .entry("price", BigDecimal("-0.50")),
            org.assertj.core.api.Assertions
                .entry("createdAt", Instant.parse("2026-09-15T10:00:00Z")),
            org.assertj.core.api.Assertions
                .entry("available", false),
            org.assertj.core.api.Assertions
                .entry("isbn", null),
            org.assertj.core.api.Assertions
                .entry("version", 3L),
        )
    }

    @Test
    fun `another JSON type or spelling is invalid_format at the member, and a stranger member unknown_field`() {
        assertThat(
            refusals(
                """{"pages":2147483648,"copies":"1","price":"1.5","title":7,"available":"true","id":42,""" +
                    """"publishedOn":"1965-08-01T00:00:00Z","createdAt":[1],"shelf":{"a":1},"Title":"x"}""",
            ),
        ).containsExactly(
            "/pages invalid_format the value is outside the range of this field",
            "/copies invalid_format the value is not a JSON number",
            "/price invalid_format the value is not a JSON number",
            "/title invalid_format the value is not a JSON string",
            "/available invalid_format the value is not true or false",
            "/id invalid_format the value is not a JSON string",
            "/publishedOn invalid_format the value is a date-time; this field is a date",
            "/createdAt invalid_format the value is not a JSON string",
            "/shelf invalid_format the value is not a JSON string",
            "/Title unknown_field is not a field of this resource",
        )
        assertThat(refusals("""{"pages":1.0,"copies":1e3,"price":1E-2}""")).containsExactly(
            "/pages invalid_format the value is not an integer",
            "/copies invalid_format the value is not an integer",
            "/price invalid_format the value is not a decimal number",
        )
    }

    @Test
    fun `anything but one object with members named once is malformed_body`() {
        listOf(
            null,
            "",
            "null",
            "[]",
            "1",
            "{",
            """{"title":"a"}x""",
            """{"title":"a"}{}""",
            """{"title":"a","title":"a"}""",
            "{'title':'a'}",
        ).forEach { body ->
            val refused: Fault = faultOf { CrudBodies.write(Books.SCHEMA, body) }
            assertThat(refused.code.value).describedAs(body).isEqualTo("malformed_body")
        }
        assertThat(CrudBodies.write(Books.SCHEMA, "{}").values).isEmpty()
    }

    @Test
    fun `a bulk body is exactly an ids array of strings`() {
        assertThat(CrudBodies.ids("""{"ids":["a","b"]}""")).containsExactly("a", "b")
        listOf(null, "{}", """{"ids":"a"}""", """{"ids":[1]}""", """{"ids":[],"x":1}""", "[]", "{")
            .forEach { body -> assertThat(faultOf { CrudBodies.ids(body) }.code.value).describedAs(body).isEqualTo("malformed_body") }
    }
}
