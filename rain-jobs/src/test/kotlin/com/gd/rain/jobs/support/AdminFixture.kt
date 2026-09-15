package com.gd.rain.jobs.support

import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.JobsProperties
import com.gd.rain.jobs.internal.admin.LedgerJobAdministration
import com.gd.rain.jobs.internal.ledger.JooqAdministrationLedger
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Administration over a [QueueFixture]'s database, with dead letters placed directly. */
internal class AdminFixture(
    val queue: QueueFixture,
) {
    constructor(
        prefix: String,
        profiles: List<JobProfile> = listOf(Fixtures.profile()),
        definitions: List<JobDefinition<*>> = listOf(Fixtures.definition()),
    ) : this(QueueFixture(prefix, profiles, definitions))

    val database: JobsDatabase get() = queue.database
    val jooq = JooqAdministrationLedger(database.dsl)
    val administration =
        LedgerJobAdministration(
            catalog = queue.catalog,
            properties =
                JobsProperties(
                    workers = queue.definitions.associate { it.name to 2 },
                    requiredRecurring = emptyList(),
                    drainGrace = Duration.ofSeconds(10),
                    reservedConnections = 0,
                ),
            ledger = jooq,
            intents = queue.intents,
            client = Fixtures.client(database),
            transactions = TransactionTemplate(database.transactions),
            clock = queue.clock,
        )

    /** A failed or dead invocation that finished at [finishedAt], its reservation released as a terminal write releases it. */
    fun terminal(
        id: UUID,
        state: JobState,
        finishedAt: Instant,
        code: String = "failed",
    ) {
        database.jdbc.update(
            "UPDATE rain_jobs.job_invocation SET state = ?, finished_at = ?, failure_code = ?, failure_message = 'it broke', " +
                "attempts = 1, retry_spent = retry_limit WHERE id = ?",
            state.wire,
            Timestamp.from(finishedAt),
            code,
            id,
        )
        database.jdbc.update(
            "UPDATE rain_jobs.job_intent SET released_at = ? WHERE invocation_id = ? AND released_at IS NULL",
            Timestamp.from(finishedAt),
            id,
        )
        database.jdbc.update("DELETE FROM rain_jobs.scheduled_tasks WHERE task_instance LIKE ?", "$id/%")
    }

    fun row(id: UUID): Map<String, Any?> = database.jdbc.queryForMap("SELECT * FROM rain_jobs.job_invocation WHERE id = ?", id)
}
