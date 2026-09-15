package com.gd.rain.data.jdbc.it

import com.gd.rain.persistence.json.JsonbPayload
import com.gd.rain.persistence.json.WireEnum
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class NoteStatus(
    override val wire: String,
) : WireEnum {
    DRAFT("draft"),
    PUBLISHED("published"),
}

data class NoteBody(
    val lines: List<String>,
    val pinned: Boolean,
) : JsonbPayload

@Table("notes")
data class Note(
    @Id val id: UUID? = null,
    val status: NoteStatus,
    val body: NoteBody,
    val createdAt: Instant,
    @Version val version: Int? = null,
)

interface NoteRepository : CrudRepository<Note, UUID>

@SpringBootApplication
class DataJdbcApplication

/** The application's own Spring Data repository, next to rain: registered by Boot, fed rain's ids and conversions. */
@Tag("integration")
class DataJdbcIT {
    @Test
    fun `an application repository round-trips an aggregate with a minted id, a wire enum, a jsonb payload and an instant`() {
        val database = RainPostgres.freshDatabase("datajdbc")
        val properties =
            database.springProperties() +
                listOf(
                    "spring.application.name=sample",
                    "rain.runtime.roles=api",
                    "rain.deployment.stage=test",
                    "rain.persistence.statement-timeout=30s",
                )

        SpringApplicationBuilder(DataJdbcApplication::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .properties(*properties.toTypedArray())
            .run()
            .use { context ->
                val notes = context.getBean(NoteRepository::class.java)
                val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)

                val body = NoteBody(listOf("first", "second"), pinned = true)
                val saved = notes.save(Note(status = NoteStatus.PUBLISHED, body = body, createdAt = createdAt))

                val id = requireNotNull(saved.id)
                assertThat(id.version()).isEqualTo(7)
                assertThat(notes.findById(id)).hasValueSatisfying { read ->
                    assertThat(read.status).isEqualTo(NoteStatus.PUBLISHED)
                    assertThat(read.body).isEqualTo(NoteBody(listOf("first", "second"), pinned = true))
                    assertThat(read.createdAt).isEqualTo(createdAt)
                }
            }
    }
}
