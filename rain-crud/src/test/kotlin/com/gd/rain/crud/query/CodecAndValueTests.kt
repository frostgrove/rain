package com.gd.rain.crud.query

import com.gd.rain.crud.Books
import com.gd.rain.crud.CrudStoreContract
import com.gd.rain.crud.MemoryStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** Cursor format v1: what is minted, and how strictly it is read back. */
class CursorCodecTest {
    private val order =
        listOf(
            Order(Books.TITLE, Direction.ASC),
            Order(Books.AVAILABLE, Direction.DESC),
            Order(Books.PAGES, Direction.ASC),
            Order(Books.COPIES, Direction.ASC),
            Order(Books.PRICE, Direction.ASC),
            Order(Books.CREATED_AT, Direction.DESC),
            Order(Books.PUBLISHED_ON, Direction.ASC),
            Order(Books.ID, Direction.DESC),
        )
    private val keys: List<Any> =
        listOf(
            "Dune, \"the\" novel",
            true,
            412,
            5_000_000_000L,
            BigDecimal("12.50"),
            Instant.parse("2026-09-15T10:00:00.123456Z"),
            LocalDate.of(1965, 8, 1),
            UUID.fromString("0192f1c0-0000-7000-8000-00000000000a"),
        )
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun token(json: String) = encoder.encodeToString(json.toByteArray())

    private fun refusal(token: String) = (CursorCodec.decode(token, order) as? CursorDecoding.Refused)?.reason

    @Test
    fun `every kind round-trips exactly`() {
        val token = CursorCodec.encode(order, CursorPosition(CursorDirection.PREV, keys))

        val decoded = CursorCodec.decode(token, order) as CursorDecoding.Decoded

        assertThat(decoded.position.direction).isEqualTo(CursorDirection.PREV)
        assertThat(decoded.position.keys).isEqualTo(keys)
    }

    @Test
    fun `a cursor is unpadded base64url of the version 1 object with canonical keys`() {
        val single = listOf(Order(Books.PRICE, Direction.ASC), Order(Books.ID, Direction.ASC))
        val token = CursorCodec.encode(single, CursorPosition(CursorDirection.NEXT, listOf(BigDecimal("12.50"), keys.last())))

        assertThat(token).doesNotContain("=", "+", "/")
        assertThat(String(Base64.getUrlDecoder().decode(token))).isEqualTo(
            """{"v":1,"sig":"${CursorCodec.signature(single)}","dir":"next","k":["12.50","0192f1c0-0000-7000-8000-00000000000a"]}""",
        )
    }

    @Test
    fun `the signature changes with any field or direction of the order`() {
        val base = CursorCodec.signature(order)

        assertThat(CursorCodec.signature(order.dropLast(1))).isNotEqualTo(base)
        assertThat(CursorCodec.signature(order.map { Order(it.field, it.direction.inverted()) })).isNotEqualTo(base)
        assertThat(CursorCodec.signature(order.toList())).isEqualTo(base)
    }

    @Test
    fun `a key spelled other than canonically is refused`() {
        val sig = CursorCodec.signature(listOf(Order(Books.PAGES, Direction.ASC)))
        val pages = listOf(Order(Books.PAGES, Direction.ASC))

        listOf("007", "+7", "7.0", " 7").forEach { spelled ->
            val token = token("""{"v":1,"sig":"$sig","dir":"next","k":["$spelled"]}""")
            assertThat(
                (CursorCodec.decode(token, pages) as? CursorDecoding.Refused)?.reason,
            ).describedAs(spelled).isEqualTo("holds a key that does not fit pages")
        }
        assertThat(
            CursorCodec.decode(token("""{"v":1,"sig":"$sig","dir":"next","k":["7"]}"""), pages),
        ).isInstanceOf(CursorDecoding.Decoded::class.java)
    }

    @Test
    fun `anything but exactly the version 1 object is refused`() {
        val sig = CursorCodec.signature(order)
        val good = CursorCodec.encode(order, CursorPosition(CursorDirection.NEXT, keys))

        assertThat(refusal(Base64.getUrlEncoder().encodeToString("{}".toByteArray()))).isEqualTo("is not unpadded base64url")
        assertThat(refusal("$good=")).isNotNull()
        assertThat(refusal("***")).isEqualTo("is not base64url")
        assertThat(refusal(token("not json"))).isEqualTo("does not hold a JSON object")
        assertThat(refusal(token("""{"v":2,"sig":"$sig","dir":"next","k":[]}"""))).isEqualTo("is not a version 1 cursor")
        assertThat(refusal(token("""{"v":1,"sig":"$sig","dir":"next","k":[],"x":0}"""))).isEqualTo("is not a version 1 cursor")
        assertThat(refusal(token("""{"v":1,"sig":"$sig","dir":"up","k":[]}"""))).isEqualTo("names no direction")
        assertThat(refusal(token("""{"v":1,"sig":"$sig","dir":"next","k":["a"]}"""))).isEqualTo("does not hold one key per sort term")
        assertThat(refusal(token("""{"v":1,"v":1,"sig":"$sig","dir":"next","k":[]}"""))).isEqualTo("does not hold a JSON object")
    }
}

/** Dialect v1 values: one accepted spelling per kind. */
class WireValuesTest {
    private fun read(
        raw: String,
        kind: FieldKind,
    ): Any? = (WireValues.read(raw, kind) as? WireValue.Read)?.value

    private fun refused(
        kind: FieldKind,
        vararg raws: String,
    ) = raws.forEach { assertThat(WireValues.read(it, kind)).describedAs("$kind $it").isInstanceOf(WireValue.Refused::class.java) }

    @Test
    fun `timestamps are RFC 3339 date-times with seconds and an offset`() {
        assertThat(read("2026-09-15T10:00:00Z", FieldKind.TIMESTAMP)).isEqualTo(Books.T0)
        assertThat(read("2026-09-15t10:00:00z", FieldKind.TIMESTAMP)).isEqualTo(Books.T0)
        assertThat(read("2026-09-15T12:00:00+02:00", FieldKind.TIMESTAMP)).isEqualTo(Books.T0)
        assertThat(read("2026-09-15T10:00:00.123456789Z", FieldKind.TIMESTAMP)).isEqualTo(Books.T0.plusNanos(123_456_789))
        refused(
            FieldKind.TIMESTAMP,
            "2026-09-15",
            "2026-09-15T10:00:00",
            "2026-09-15 10:00:00Z",
            "2026-09-15T10:00Z",
            "2026-02-30T10:00:00Z",
            "2026-09-15T24:00:00Z",
            "2026-09-15T10:00:60Z",
            "2026-09-15T10:00:00+0200",
            "1789466400",
        )
    }

    @Test
    fun `dates, booleans and UUIDs have exactly one spelling`() {
        assertThat(read("1965-08-01", FieldKind.DATE)).isEqualTo(LocalDate.of(1965, 8, 1))
        refused(FieldKind.DATE, "1965-8-1", "1965-02-30", "1965-08-01T00:00:00Z", "19650801")
        assertThat(read("true", FieldKind.BOOLEAN)).isEqualTo(true)
        assertThat(read("false", FieldKind.BOOLEAN)).isEqualTo(false)
        refused(FieldKind.BOOLEAN, "TRUE", "True", "1", "t", "yes", "")
        assertThat(
            read("0192F1C0-0000-7000-8000-00000000000A", FieldKind.UUID),
        ).isEqualTo(UUID.fromString("0192f1c0-0000-7000-8000-00000000000a"))
        refused(FieldKind.UUID, "0192f1c0000070008000000000000001", "1-1-1-1-1", "{0192f1c0-0000-7000-8000-000000000001}")
    }

    @Test
    fun `numbers are plain decimal notation within their kind's range`() {
        assertThat(read("-12", FieldKind.INT)).isEqualTo(-12)
        assertThat(read("2147483647", FieldKind.INT)).isEqualTo(Int.MAX_VALUE)
        refused(FieldKind.INT, "2147483648", "01", "+1", "1.0", "", "1e3")
        assertThat(read("9223372036854775807", FieldKind.LONG)).isEqualTo(Long.MAX_VALUE)
        refused(FieldKind.LONG, "9223372036854775808", "-")
        assertThat(read("12.50", FieldKind.DECIMAL)).isEqualTo(BigDecimal("12.50"))
        assertThat(read("-0.5", FieldKind.DECIMAL)).isEqualTo(BigDecimal("-0.5"))
        refused(FieldKind.DECIMAL, "1e3", ".5", "5.", "01.5", "NaN")
        assertThat(read("", FieldKind.TEXT)).isEqualTo("")
    }
}

/** Declarations are checked where they are written. */
class DeclarationProblemsTest {
    private fun rules(
        shapes: List<QueryShape>,
        includable: FieldGrant = FieldGrant.None,
    ) = QueryRules(shapes, FieldGrant.All, includable, Pagination(10, 50, 100, null))

    @Test
    fun `a shape sorted by a nullable column is refused at declaration`() {
        val problems = rules(listOf(QueryShape.of(SortKey.parse("-price")))).problems(Books.SCHEMA, emptySet())

        assertThat(problems.map { it.path to it.message })
            .containsExactly(
                "crud:books.shapes" to "shape filters [], sort -price sorts by price, which is nullable; a cursor cannot page by it",
            )
    }

    @Test
    fun `a shape over undeclared fields, with inapplicable operators or too many terms, is refused`() {
        val problems =
            rules(
                listOf(
                    QueryShape.of(SortKey.parse("nope")),
                    QueryShape.of(SortKey.parse("title,-id,pages,shelf,copies")),
                    QueryShape.of(SortKey.NONE, "colour" to Operator.EQ, "available" to Operator.GT, "title" to Operator.IS_NULL),
                ),
                FieldGrant.only("x"),
            ).problems(Books.SCHEMA, emptySet())

        assertThat(problems.map { it.message }).containsExactlyInAnyOrder(
            "grants x, which is not a relation",
            "shape filters [], sort nope sorts by nope, which is not a field",
            "shape filters [], sort title,-id,pages,shelf,copies sorts by more than maxSortTerms (4) terms",
            "shape filters [filter[available][gt], filter[colour][eq], filter[title][isnull]], sort (none) cannot filter: " +
                "gt applies to ordered fields; available is BOOLEAN",
            "shape filters [filter[available][gt], filter[colour][eq], filter[title][isnull]], sort (none) filters by colour, " +
                "which is not a field",
            "shape filters [filter[available][gt], filter[colour][eq], filter[title][isnull]], sort (none) cannot filter: " +
                "isnull applies to nullable fields; title is not nullable",
        )
    }

    @Test
    fun `the compiler refuses rules with problems`() {
        assertThatThrownBy { QueryCompiler(Books.SCHEMA, rules(listOf(QueryShape.of(SortKey.parse("nope")))), emptySet(), setOf(Books.ID)) }
            .isInstanceOf(com.gd.rain.core.config.ConfigurationProblemsException::class.java)
    }

    @Test
    fun `pagination numbers, limits, sort keys and shapes are checked where they are written`() {
        listOf<() -> Any>(
            { Pagination(0, 50, 100, null) },
            { Pagination(51, 50, 100, null) },
            { Pagination(1, Int.MAX_VALUE, 100, null) },
            { Pagination(1, 50, -1, null) },
            { Pagination(1, 50, 100, 0) },
            { QueryLimits(maxInValues = 0) },
            { SortKey.parse("") },
            { SortKey.parse("title,-title") },
            { QueryShape.of(SortKey.NONE, "title" to Operator.EQ, "title" to Operator.EQ) },
            { ShapeFilter("a[b]", Operator.EQ) },
        ).forEachIndexed { index, declaration ->
            assertThatThrownBy { declaration() }.describedAs("declaration $index").isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a schema's identifier is a non-null UUID and its names and columns are unique`() {
        val table = TableName("public", "books")
        assertThatThrownBy { ResourceSchema("books", table, SchemaField("id", "id", FieldKind.TEXT, false), emptyList(), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ResourceSchema(
                "books",
                table,
                Books.ID,
                listOf(Books.TITLE, SchemaField("title", "other", FieldKind.TEXT, false)),
                null,
            )
        }.hasMessageContaining("field names [title]")
        assertThatThrownBy {
            ResourceSchema(
                "books",
                table,
                Books.ID,
                listOf(Books.TITLE, SchemaField("heading", "title", FieldKind.TEXT, false)),
                null,
            )
        }.hasMessageContaining("columns [title]")
        assertThatThrownBy { SchemaField("a[b]", "a", FieldKind.TEXT, false) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

/** The contract every store keeps, run against the in-memory store. */
class MemoryStoreContractTest : CrudStoreContract() {
    override fun freshStore(schema: ResourceSchema): MemoryStore = MemoryStore(schema)
}
