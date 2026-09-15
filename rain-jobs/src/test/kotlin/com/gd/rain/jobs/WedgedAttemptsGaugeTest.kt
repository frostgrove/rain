package com.gd.rain.jobs

import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.execution.VirtualAttemptThreads
import com.gd.rain.jobs.internal.ledger.JooqAttemptLedger
import com.gd.rain.jobs.internal.worker.WorkerAssembly
import com.gd.rain.jobs.internal.worker.WorkerParts
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.lock.JooqAdvisoryLockStore
import com.gd.rain.persistence.tx.TransactionRetry
import com.gd.rain.test.MutableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Duration

/** Gap 6: wedged attempts are visible per profile as the gauge `rain.jobs.attempts.wedged`. Assembly only; nothing connects. */
class WedgedAttemptsGaugeTest {
    @Test
    fun `each profile publishes the attempts its scheduler threads stopped waiting for`() {
        val dataSource = PGSimpleDataSource().apply { setURL("jdbc:postgresql://127.0.0.1:1/none") }
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val statements = JooqAdvisoryLockStore(dsl)
        val interactive = Fixtures.definition("notes.interactive", "interactive")
        val batch = Fixtures.definition("notes.batch", "batch")
        val registry = SimpleMeterRegistry()
        val runtime =
            WorkerAssembly.assemble(
                WorkerParts(
                    catalog = JobCatalog(listOf(Fixtures.profile("interactive"), Fixtures.profile("batch")), listOf(interactive, batch)),
                    handlers = listOf(handler(interactive), handler(batch)),
                    recurring = emptyList(),
                    properties =
                        JobsProperties(
                            workers = mapOf("notes.interactive" to 2, "notes.batch" to 1),
                            requiredRecurring = emptyList(),
                            drainGrace = Duration.ofSeconds(10),
                            reservedConnections = 0,
                        ),
                    ledger = JooqAttemptLedger(dsl),
                    locks =
                        AdvisoryLocks(
                            DataSourceTransactionManager(dataSource),
                            statements,
                            TransactionRetry(1, Duration.ofMillis(10), Duration.ofMillis(10)),
                            Duration.ofSeconds(1),
                        ),
                    statements = statements,
                    threads = VirtualAttemptThreads,
                    codec = JacksonJobPayloadCodec(),
                    jitter = { 0 },
                    ids = Fixtures.ids,
                    clock = MutableClock(Fixtures.START),
                    host = "host",
                    meters = registry,
                ),
            )

        runtime.wedged.getValue("batch").incrementAndGet()

        assertThat(
            registry
                .get(WorkerAssembly.WEDGED_GAUGE)
                .tag("profile", "batch")
                .gauge()
                .value(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get(WorkerAssembly.WEDGED_GAUGE)
                .tag("profile", "interactive")
                .gauge()
                .value(),
        ).isEqualTo(0.0)
        assertThat(runtime.specs.map { it.name to it.threads }).containsExactly("batch" to 1, "interactive" to 2)
    }

    private fun handler(definition: JobDefinition<Note>): JobHandler<Note> =
        object : JobHandler<Note> {
            override val definition = definition

            override fun handle(
                payload: Note,
                attempt: Attempt,
            ) = Unit
        }
}
