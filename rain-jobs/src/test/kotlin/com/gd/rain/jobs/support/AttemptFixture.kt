package com.gd.rain.jobs.support

import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.JacksonJobPayloadCodec
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.execution.AttemptStatementTimeout
import com.gd.rain.jobs.internal.execution.AttemptThreads
import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.internal.execution.DefinitionGate
import com.gd.rain.jobs.internal.execution.ExecutionSettings
import com.gd.rain.jobs.internal.execution.FencedEffects
import com.gd.rain.jobs.internal.execution.JobExecution
import com.gd.rain.jobs.internal.execution.LeaseRenewer
import com.gd.rain.jobs.internal.execution.VirtualAttemptThreads
import com.gd.rain.jobs.internal.ledger.AttemptLedger
import com.gd.rain.jobs.internal.ledger.JooqAttemptLedger
import com.gd.rain.persistence.lock.AdvisoryLockStore
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.lock.JooqAdvisoryLockStore
import com.gd.rain.persistence.tx.TransactionRetry
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** The attempt side over one fresh database, wired as the worker lifecycle wires it; attempts are driven by hand. */
internal class AttemptFixture(
    prefix: String,
    val profile: JobProfile = Fixtures.profile(),
    ceiling: Int = 1,
    val ttl: Duration = Duration.ofSeconds(30),
    decorateLedger: (AttemptLedger) -> AttemptLedger = { it },
    decorateStatements: (AdvisoryLockStore) -> AdvisoryLockStore = { it },
) {
    val definition = Fixtures.definition(profile = profile.id)
    val queue: QueueFixture = QueueFixture(prefix, listOf(profile), listOf(definition))
    val database: JobsDatabase get() = queue.database
    val clock get() = queue.clock
    val ledger: AttemptLedger = decorateLedger(JooqAttemptLedger(database.dsl))
    val statements: AdvisoryLockStore = decorateStatements(JooqAdvisoryLockStore(database.dsl))
    val renewer = LeaseRenewer(ledger, clock, ttl)
    val gate = DefinitionGate(mapOf(definition.name to ceiling))
    val wedged = AtomicInteger()
    val settings = ExecutionSettings(ttl, POLL, REAPER, "fixture#${profile.id}")
    private val locks =
        AdvisoryLocks(
            database.transactions,
            statements,
            TransactionRetry(1, Duration.ofMillis(10), Duration.ofMillis(10)),
            Duration.ofSeconds(5),
        )
    val fences = FencedEffects(locks, statements, ledger, clock)
    private val pool = Executors.newVirtualThreadPerTaskExecutor()

    init {
        database.transactions.setTransactionExecutionListeners(listOf(AttemptStatementTimeout({ statements }, clock)))
    }

    fun enqueue(dedupe: Dedupe = Dedupe.None): UUID =
        queue.queue.enqueue(definition, Note("payload"), EnqueueOptions(dedupe, JobPriority(50))).invocation

    fun execution(
        threads: AttemptThreads = VirtualAttemptThreads,
        body: (Note, Attempt) -> Unit,
    ): JobExecution =
        JobExecution(
            handler =
                object : JobHandler<Note> {
                    override val definition = this@AttemptFixture.definition

                    override fun handle(
                        payload: Note,
                        attempt: Attempt,
                    ) = body(payload, attempt)
                },
            profile = profile,
            gate = gate,
            ledger = ledger,
            renewer = renewer,
            fences = fences,
            threads = threads,
            codec = JacksonJobPayloadCodec(),
            settings = settings,
            wedged = wedged,
            jitter = { bound -> bound - 1 },
            ids = Fixtures.ids,
            clock = clock,
        )

    fun run(
        invocation: UUID,
        generation: Int = 0,
        body: (Note, Attempt) -> Unit,
    ): Completion = execution(body = body).attempt(JobTaskData(invocation, generation))

    /** Starts an attempt on another thread, for a test that acts while the body runs. */
    fun start(
        invocation: UUID,
        body: (Note, Attempt) -> Unit,
    ): Future<Completion> = pool.submit(Callable { run(invocation, body = body) })

    fun row(invocation: UUID): Map<String, Any?> =
        database.jdbc.queryForMap("SELECT * FROM rain_jobs.job_invocation WHERE id = ?", invocation)

    fun sql(
        statement: String,
        vararg arguments: Any,
    ): Int = database.jdbc.update(statement, *arguments)

    fun instant(
        invocation: UUID,
        column: String,
    ): java.time.Instant? =
        database.jdbc
            .queryForObject("SELECT $column FROM rain_jobs.job_invocation WHERE id = ?", java.time.OffsetDateTime::class.java, invocation)
            ?.toInstant()

    fun heldIntents(invocation: UUID): Long =
        database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL AND invocation_id = '$invocation'")

    companion object {
        val POLL: Duration = Duration.ofSeconds(2)
        val REAPER: Duration = Duration.ofSeconds(15)
    }
}
