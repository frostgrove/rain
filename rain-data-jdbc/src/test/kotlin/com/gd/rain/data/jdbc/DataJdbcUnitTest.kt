package com.gd.rain.data.jdbc

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.json.JsonbPayload
import com.gd.rain.persistence.json.WireEnum
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DataJdbcUnitTest {
    private val service = DefaultConversionService().also(JdbcCustomConversions(RainConverters.ALL)::registerConvertersIn)

    enum class Lifecycle(
        override val wire: String,
    ) : WireEnum {
        QUEUED("queued"),
        NEEDS_REVIEW("needs_review"),
    }

    data class Diagnostics(
        val entries: List<String>,
    ) : JsonbPayload

    @Table("tickets")
    data class Ticket(
        @Id val id: UUID? = null,
        val title: String,
    )

    @Table("positions")
    data class Position(
        @Id val scope: String,
    )

    @Test
    fun `a wire enum reads and writes by its declared string`() {
        assertThat(service.convert("needs_review", Lifecycle::class.java)).isEqualTo(Lifecycle.NEEDS_REVIEW)
        assertThat(service.convert(Lifecycle.NEEDS_REVIEW, String::class.java)).isEqualTo("needs_review")
    }

    @Test
    fun `a stored string no constant declares is refused, never read as the constant's name`() {
        assertThatThrownBy { service.convert("NEEDS_REVIEW", Lifecycle::class.java) }
            .rootCause()
            .hasMessageContaining("'NEEDS_REVIEW' is not one of Lifecycle")
    }

    @Test
    fun `a jsonb payload round-trips through its own type`() {
        val written = service.convert(Diagnostics(listOf("unbalanced")), PGobject::class.java)

        assertThat(written?.type).isEqualTo("jsonb")
        assertThat(service.convert(written, Diagnostics::class.java)).isEqualTo(Diagnostics(listOf("unbalanced")))
    }

    @Test
    fun `an offset timestamp reads as the instant it names`() {
        val moment = OffsetDateTime.of(2026, 9, 12, 14, 30, 0, 0, ZoneOffset.ofHours(3))

        assertThat(service.convert(moment, Instant::class.java)).isEqualTo(Instant.parse("2026-09-12T11:30:00Z"))
    }

    private val minted = UUID.fromString("01924f1e-0000-7000-8000-000000000001")
    private val context = JdbcMappingContext()
    private val callback =
        AssignIdCallback(
            IdGenerator { minted },
            object : ObjectProvider<RelationalMappingContext> {
                override fun getObject(): RelationalMappingContext = context
            },
        )

    @Test
    fun `a null UUID id is filled from the generator`() {
        assertThat((callback.onBeforeConvert(Ticket(title = "x")) as Ticket).id).isEqualTo(minted)
    }

    @Test
    fun `an assigned id and a non-UUID id are left alone`() {
        val chosen = UUID.randomUUID()

        assertThat((callback.onBeforeConvert(Ticket(id = chosen, title = "x")) as Ticket).id).isEqualTo(chosen)
        assertThat((callback.onBeforeConvert(Position(scope = "main")) as Position).scope).isEqualTo("main")
    }
}
