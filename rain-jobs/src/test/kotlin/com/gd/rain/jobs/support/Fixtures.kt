package com.gd.rain.jobs.support

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.BackoffLadder
import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.JacksonJobPayloadCodec
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.internal.Jackson3TaskSerializer
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.ledger.IntentLedger
import com.gd.rain.jobs.internal.ledger.JooqIntentLedger
import com.gd.rain.jobs.internal.queue.SchedulerWorkQueue
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.test.MutableClock
import com.github.kagkarlsson.scheduler.SchedulerClient
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Note(
    val text: String,
)

internal object Fixtures {
    val START: Instant = Instant.parse("2026-09-15T10:00:00Z")

    const val TABLE: String = "rain_jobs.scheduled_tasks"

    val ids: IdGenerator = UuidV7Ids

    fun profile(
        id: String = "standard",
        attemptTimeout: Duration = Duration.ofMinutes(10),
        stepTimeout: Duration = Duration.ofMinutes(1),
        retries: Int = 4,
        deferrals: Int = 3,
        retention: Duration = Duration.ofDays(7),
        backoff: BackoffLadder = BackoffLadder(Duration.ofSeconds(5), Duration.ofMinutes(5)),
    ): JobProfile = JobProfile(id, attemptTimeout, stepTimeout, backoff, retries, deferrals, retention)

    fun definition(
        name: String = "notes.write",
        profile: String = "standard",
    ): JobDefinition<Note> = JobDefinition.of(name, profile)

    fun options(
        dedupe: Dedupe = Dedupe.None,
        priority: Int = 50,
    ): EnqueueOptions = EnqueueOptions(dedupe, JobPriority(priority))

    fun client(database: JobsDatabase): SchedulerClient =
        SchedulerClient.Builder
            .create(TransactionAwareDataSourceProxy(database.dataSource), emptyList())
            .serializer(Jackson3TaskSerializer())
            .tableName(TABLE)
            .enablePriority()
            .build()
}

/** The enqueue side, wired over one fresh database the way the auto-configuration wires it. */
internal class QueueFixture(
    prefix: String,
    val profiles: List<JobProfile> = listOf(Fixtures.profile()),
    val definitions: List<JobDefinition<*>> = listOf(Fixtures.definition()),
    decorate: (IntentLedger) -> IntentLedger = { it },
) {
    val database: JobsDatabase = JobsDatabase.fresh(prefix)
    val clock: MutableClock = MutableClock(Fixtures.START)
    val catalog: JobCatalog = JobCatalog(profiles, definitions)
    val intents: IntentLedger = decorate(JooqIntentLedger(database.dsl, Fixtures.ids))
    val queue: SchedulerWorkQueue =
        SchedulerWorkQueue(
            catalog,
            intents,
            Fixtures.client(database),
            JacksonJobPayloadCodec(),
            TransactionTemplate(database.transactions),
            Fixtures.ids,
            clock,
        )

    fun invocations(): Long = database.count("SELECT count(*) FROM rain_jobs.job_invocation")

    fun executions(): Long = database.count("SELECT count(*) FROM rain_jobs.scheduled_tasks")

    fun heldIntents(): Long = database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL")
}

/** Bounded waits that fail the test instead of hanging it. */
internal object Awaits {
    const val BOUND_SECONDS: Long = 30

    fun latch(
        latch: CountDownLatch,
        what: String,
    ) {
        check(latch.await(BOUND_SECONDS, TimeUnit.SECONDS)) { "timed out waiting for $what" }
    }

    /** Re-evaluates [condition] until it holds; each wait between probes is itself bounded, and so is the whole. */
    fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        val pause = CountDownLatch(1)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            pause.await(PROBE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private const val PROBE_MILLIS = 5L
}
