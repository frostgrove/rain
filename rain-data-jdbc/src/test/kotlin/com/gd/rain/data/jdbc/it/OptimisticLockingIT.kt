package com.gd.rain.data.jdbc.it

import com.gd.rain.core.error.FaultKind
import com.gd.rain.persistence.fault.DataAccessFaultTranslator
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A stale `@Version` is refused by the store and reaches a client as a conflict it can act on. */
@Tag("integration")
class OptimisticLockingIT {
    @Test
    fun `saving a copy that another writer already changed is a stale version conflict`() {
        val database = RainPostgres.freshDatabase("optimistic")
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
                val body = NoteBody(listOf("draft"), pinned = false)
                val created =
                    notes.save(
                        Note(status = NoteStatus.DRAFT, body = body, createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)),
                    )
                val id = requireNotNull(created.id)
                val first = notes.findById(id).orElseThrow()
                val second = notes.findById(id).orElseThrow()

                notes.save(first.copy(status = NoteStatus.PUBLISHED))
                val stale = runCatching { notes.save(second.copy(body = NoteBody(listOf("late"), pinned = true))) }.exceptionOrNull()

                assertThat(stale).isInstanceOf(OptimisticLockingFailureException::class.java)
                val fault = requireNotNull(DataAccessFaultTranslator.translate(requireNotNull(stale)))
                assertThat(fault.kind).isEqualTo(FaultKind.CONFLICT)
                assertThat(fault.code.value).isEqualTo("stale_version")
                assertThat(notes.findById(id).orElseThrow().status).isEqualTo(NoteStatus.PUBLISHED)
            }
    }
}
