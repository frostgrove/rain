package com.gd.rain.jobs.support

import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.ReaperProperties
import com.gd.rain.jobs.RetentionProperties
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.housekeeping.JobReaper
import com.gd.rain.jobs.internal.housekeeping.JobRetention
import com.gd.rain.jobs.internal.ledger.HousekeepingLedger
import com.gd.rain.jobs.internal.ledger.JooqAttemptLedger
import com.gd.rain.jobs.internal.ledger.JooqHousekeepingLedger
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Invocations placed directly in the states housekeeping reads, and the reaper and retention over them. */
internal class HousekeepingFixture(
    prefix: String,
    profiles: List<JobProfile> = listOf(Fixtures.profile()),
    definitions: List<JobDefinition<*>> = listOf(Fixtures.definition()),
    decorate: (HousekeepingLedger) -> HousekeepingLedger = { it },
) {
    val queue = QueueFixture(prefix, profiles, definitions)
    val database: JobsDatabase get() = queue.database
    val clock get() = queue.clock
    val jooq = JooqHousekeepingLedger(database.dsl, JooqAttemptLedger(database.dsl))
    val ledger: HousekeepingLedger = decorate(jooq)

    fun reaper(batch: Int = 100): JobReaper =
        JobReaper(
            queue.catalog,
            ledger,
            TransactionTemplate(database.transactions),
            ReaperProperties(Duration.ofSeconds(15), batch),
            { 0 },
            clock,
        )

    fun retention(
        batch: Int = 1_000,
        budget: Duration = Duration.ofSeconds(30),
        catalog: JobCatalog = queue.catalog,
    ): JobRetention = JobRetention(catalog, ledger, RetentionProperties(Duration.ofHours(1), batch, budget), clock)

    fun enqueue(
        definition: JobDefinition<Note> = Fixtures.definition(),
        dedupe: com.gd.rain.jobs.Dedupe = com.gd.rain.jobs.Dedupe.None,
    ): UUID = queue.queue.enqueue(definition, Note("n"), Fixtures.options(dedupe)).invocation

    /** A running invocation whose lease expires at [leaseExpiresAt]. */
    fun running(
        id: UUID,
        leaseExpiresAt: Instant,
    ) {
        database.jdbc.update(
            "UPDATE rain_jobs.job_invocation SET state = 'running', lease_token = gen_random_uuid(), lease_expires_at = ?, " +
                "attempts = attempts + 1 WHERE id = ?",
            Timestamp.from(leaseExpiresAt),
            id,
        )
    }

    fun terminal(
        id: UUID,
        state: JobState,
        finishedAt: Instant,
    ) {
        database.jdbc.update(
            "UPDATE rain_jobs.job_invocation SET state = ?, finished_at = ? WHERE id = ?",
            state.wire,
            Timestamp.from(finishedAt),
            id,
        )
        database.jdbc.update(
            "UPDATE rain_jobs.job_intent SET released_at = ? WHERE invocation_id = ? AND released_at IS NULL",
            Timestamp.from(finishedAt),
            id,
        )
    }

    fun state(id: UUID): String? =
        database.jdbc.queryForList("SELECT state FROM rain_jobs.job_invocation WHERE id = ?", String::class.java, id).singleOrNull()

    fun row(id: UUID): Map<String, Any?> = database.jdbc.queryForMap("SELECT * FROM rain_jobs.job_invocation WHERE id = ?", id)
}
