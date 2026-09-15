package com.gd.rain.sample.ticket

import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.crud.WriteInput
import com.gd.rain.crud.error.RainCrudErrorCodes
import com.gd.rain.crud.web.CrudBodies
import java.time.Clock

/**
 * The write bodies of the ticket routes. rain-crud reads a body strictly (`CrudBodies`) and refuses what its policy
 * refuses; on top of that a client states only [CLIENT_FIELDS] — the helpdesk states status and timestamps itself — and
 * the values keep the table's own `CHECK` constraints, so a body refused here names its field instead of reaching the
 * database. Every problem of a body is refused together.
 *
 * Each input is a function the resource calls once the caller is authorized, so a body is never read for a caller who
 * may not write.
 */
object TicketInputs {
    const val TITLE_MAX_LENGTH = 200
    const val BODY_MAX_LENGTH = 20_000
    val PRIORITIES: IntRange = 0..100

    val CLIENT_FIELDS: Set<String> =
        setOf(TicketFields.TITLE.name, TicketFields.BODY.name, TicketFields.PRIORITY.name, TicketFields.ASSIGNEE.name)

    private val REQUIRED_ON_CREATE = listOf(TicketFields.TITLE.name, TicketFields.BODY.name, TicketFields.PRIORITY.name)

    /** A new ticket: title, body and priority stated, an assignee optional; opened now. */
    class Create(
        private val body: String?,
        private val clock: Clock,
    ) : () -> WriteInput {
        override fun invoke(): WriteInput {
            val read = CrudBodies.write(TicketFields.SCHEMA, body)
            val violations = read.violations.toMutableList()
            val values = clientValues(read, allowed = CLIENT_FIELDS, violations)
            REQUIRED_ON_CREATE.filterNot(read.values::containsKey).forEach {
                violations += Violation.at(path(it), RainErrorCodes.REQUIRED, "a new ticket states it")
            }
            val now = clock.instant()
            return WriteInput(
                values +
                    mapOf(
                        TicketFields.STATUS.name to TicketStatus.OPEN,
                        TicketFields.CREATED_AT.name to now,
                        TicketFields.UPDATED_AT.name to now,
                    ),
                violations,
            )
        }
    }

    /** A change to a ticket: the version it was read at, and at least one of the client's fields. */
    class Change(
        private val body: String?,
        private val clock: Clock,
    ) : () -> WriteInput {
        /** The client's fields the change states, sorted; known once the resource has read the input. */
        var fields: List<String> = emptyList()
            private set

        override fun invoke(): WriteInput {
            val read = CrudBodies.write(TicketFields.SCHEMA, body)
            val violations = read.violations.toMutableList()
            val values = clientValues(read, allowed = CLIENT_FIELDS + TicketFields.VERSION.name, violations)
            fields = values.keys.filter(CLIENT_FIELDS::contains).sorted()
            if (fields.isEmpty() && violations.isEmpty()) {
                violations +=
                    Violation(
                        emptyList(),
                        RainErrorCodes.REQUIRED,
                        "a change states at least one of ${CLIENT_FIELDS.sorted().joinToString(", ")}",
                    )
            }
            return WriteInput(values + (TicketFields.UPDATED_AT.name to clock.instant()), violations)
        }
    }

    private fun clientValues(
        read: WriteInput,
        allowed: Set<String>,
        violations: MutableList<Violation>,
    ): Map<String, Any?> {
        read.values.keys.filterNot(allowed::contains).sorted().forEach {
            violations += Violation.at(path(it), RainCrudErrorCodes.FIELD_NOT_GRANTED, "is written by the helpdesk, not by a client")
        }
        val values = read.values.filterKeys(allowed::contains)
        text(values, TicketFields.TITLE.name, TITLE_MAX_LENGTH, violations)
        text(values, TicketFields.BODY.name, BODY_MAX_LENGTH, violations)
        val priority = values[TicketFields.PRIORITY.name]
        if (priority is Int && priority !in PRIORITIES) {
            violations +=
                Violation.at(
                    path(TicketFields.PRIORITY.name),
                    RainErrorCodes.OUT_OF_RANGE,
                    "is within ${PRIORITIES.first}..${PRIORITIES.last}",
                )
        }
        return values
    }

    private fun text(
        values: Map<String, Any?>,
        field: String,
        maxLength: Int,
        violations: MutableList<Violation>,
    ) {
        val value = values[field] as? String ?: return
        when {
            value.isBlank() -> {
                violations += Violation.at(path(field), RainErrorCodes.REQUIRED, "is blank")
            }

            value.length > maxLength -> {
                violations +=
                    Violation.at(path(field), RainErrorCodes.TOO_LONG, "is longer than $maxLength characters")
            }
        }
    }
}
