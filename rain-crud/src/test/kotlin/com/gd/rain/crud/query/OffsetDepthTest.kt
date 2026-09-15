package com.gd.rain.crud.query

import com.gd.rain.crud.Books
import com.gd.rain.crud.faultOf
import com.gd.rain.crud.pointedCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Gap 26: the offset cap could be passed through `page` and overflowed in `Int`; now `offset + limit ≤ maxOffset` in `Long`, and there is no `page`. */
class OffsetDepthTest {
    private val compiler = QueryCompiler(Books.SCHEMA, Books.rules(maxOffset = 100), setOf("reviews"))

    private fun list(vararg parameters: Pair<String, String>): ListPlan =
        compiler.list(DialectV1.parse(parameters.associate { (name, value) -> name to listOf(value) }))

    @Test
    fun `offset plus limit at the maximum depth is served`() {
        val window = list("offset" to "90", "limit" to "10").window as Window.Offset

        assertThat(window.offset).isEqualTo(90)
        assertThat(window.limit).isEqualTo(10)
    }

    @Test
    fun `one row deeper is refused`() {
        val refused = faultOf { list("offset" to "91", "limit" to "10") }

        assertThat(refused.code.value).isEqualTo("bad_query")
        assertThat(refused.pointedCodes()).containsExactly("/offset out_of_range")
        assertThat(refused.violations.single().message).isEqualTo("offset + limit is above this resource's maximum of 100")
    }

    @Test
    fun `the default limit counts toward the depth`() {
        assertThat((list("offset" to "90").window as Window.Offset).limit).isEqualTo(10)
        assertThat(faultOf { list("offset" to "91") }.pointedCodes()).containsExactly("/offset out_of_range")
    }

    @Test
    fun `an offset at the edge of Long neither overflows into a pass nor fails to parse into one`() {
        listOf(Long.MAX_VALUE.toString(), "9223372036854775808", "99999999999999999999999").forEach { offset ->
            assertThat(
                faultOf { list("offset" to offset, "limit" to "50") }.pointedCodes(),
            ).describedAs(offset).containsExactly("/offset out_of_range")
        }
    }

    @Test
    fun `an offset has one spelling`() {
        listOf("-1", "+5", "05", "1.0", "").forEach { offset ->
            assertThat(faultOf { list("offset" to offset) }.pointedCodes()).describedAs(offset).containsExactly("/offset invalid_format")
        }
    }

    @Test
    fun `offset cannot be combined with a cursor`() {
        assertThat(faultOf { list("offset" to "0", "cursor" to "abc") }.pointedCodes()).containsExactly("/offset bad_query")
    }

    @Test
    fun `there is no page parameter to reach past the depth with`() {
        val refused = faultOf { list("page" to "1000", "limit" to "50") }

        assertThat(refused.code.value).isEqualTo("unknown_parameter")
        assertThat(refused.pointedCodes()).containsExactly("/page unknown_parameter")
    }

    @Test
    fun `offset and cursor pages follow the same declared shapes`() {
        assertThat(list("sort" to "pages", "offset" to "0").window).isInstanceOf(Window.Offset::class.java)
        assertThat(list("sort" to "pages").window).isInstanceOf(Window.Cursor::class.java)
        assertThat(faultOf { list("sort" to "shelf", "offset" to "0") }.code.value).isEqualTo("not_offered")
        assertThat(faultOf { list("sort" to "shelf") }.code.value).isEqualTo("not_offered")
    }
}
