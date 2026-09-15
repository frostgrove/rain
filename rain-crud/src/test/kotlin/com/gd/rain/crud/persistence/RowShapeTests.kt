package com.gd.rain.crud.persistence

import com.gd.rain.crud.Books
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val create = DSL.using(SQLDialect.POSTGRES)
private val title = DSL.field(DSL.name("title"), SQLDataType.VARCHAR)
private val pages = DSL.field(DSL.name("pages"), SQLDataType.INTEGER)
private val createdAt = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)

/** Gap 29: a column was looked up case-insensitively as a fallback; now labels are exact. */
class RowColumnCaseMismatchThrowsTest {
    @Test
    fun `a column asked for in another case is not found, not folded`() {
        val row = Row(create.newRecord(title).values("dune"))

        assertThat(row.string("title")).isEqualTo("dune")
        assertThat(row.has("Title")).isFalse()
        assertThatThrownBy { row.string("Title") }
            .isInstanceOf(RowShapeException::class.java)
            .hasMessage("the row has no column \"Title\"; it has [title]")
        assertThatThrownBy { row.requiredString("TITLE") }.isInstanceOf(RowShapeException::class.java)
    }

    @Test
    fun `a column the statement did not select is a refusal, not a NULL`() {
        val row = Row(create.newRecord(title).values("dune"))

        assertThatThrownBy { row.string("isbn") }.isInstanceOf(RowShapeException::class.java).hasMessageContaining("no column \"isbn\"")
        assertThat(RowReader.fields(Books.SCHEMA).read(row)).isEqualTo(mapOf("title" to "dune"))
    }

    @Test
    fun `a column holding another type is refused rather than converted`() {
        val row = Row(create.newRecord(title).values("412"))

        assertThatThrownBy { row.int("title") }
            .isInstanceOf(RowShapeException::class.java)
            .hasMessage("column \"title\" holds a java.lang.String, not a java.lang.Integer")
    }
}

/** Gap 29: a NULL used to read as a zero value (`0`, `false`, `""`, the epoch); now it is `null`, or a refusal where one is required. */
class RowRequiredNullThrowsTest {
    @Test
    fun `a NULL is null to a nullable accessor and a refusal to a required one`() {
        val row = Row(create.newRecord(title, pages, createdAt))

        assertThat(row.string("title")).isNull()
        assertThat(row.int("pages")).isNull()
        assertThat(row.instant("created_at")).isNull()
        assertThatThrownBy { row.requiredString("title") }
            .isInstanceOf(RowShapeException::class.java)
            .hasMessage("column \"title\" is NULL where a value is required")
        assertThatThrownBy { row.requiredInt("pages") }.isInstanceOf(RowShapeException::class.java)
        assertThatThrownBy { row.requiredInstant("created_at") }.isInstanceOf(RowShapeException::class.java)
    }

    @Test
    fun `a present value is answered by both accessors`() {
        val at = OffsetDateTime.of(2026, 9, 15, 12, 0, 0, 0, ZoneOffset.ofHours(2))
        val row = Row(create.newRecord(title, pages, createdAt).values("dune", 412, at))

        assertThat(row.requiredString("title")).isEqualTo("dune")
        assertThat(row.int("pages")).isEqualTo(412)
        assertThat(row.requiredInt("pages")).isEqualTo(412)
        assertThat(row.requiredInstant("created_at")).isEqualTo(Books.T0)
    }
}
