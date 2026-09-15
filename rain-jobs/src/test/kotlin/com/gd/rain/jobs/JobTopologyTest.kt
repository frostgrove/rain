package com.gd.rain.jobs

import com.gd.rain.core.config.ProblemCode
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.JobTopology
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class JobTopologyTest {
    private val interactive = Fixtures.profile("interactive")
    private val batch = Fixtures.profile("batch")
    private val summarize = Fixtures.definition("tickets.summarize", "interactive")
    private val index = Fixtures.definition("tickets.index", "interactive")
    private val export = Fixtures.definition("tickets.export", "batch")
    private val catalog = JobCatalog(listOf(interactive, batch), listOf(summarize, index, export))

    private fun properties(
        workers: Map<String, Int> = mapOf("tickets.summarize" to 3, "tickets.index" to 1, "tickets.export" to 2),
        required: List<String> = listOf("tickets.sweep"),
    ) = JobsProperties(workers = workers, requiredRecurring = required, drainGrace = Duration.ofSeconds(20), reservedConnections = 10)

    private fun handler(definition: JobDefinition<Note>): JobHandler<Note> =
        object : JobHandler<Note> {
            override val definition = definition

            override fun handle(
                payload: Note,
                attempt: Attempt,
            ) = Unit
        }

    private fun recurring(
        name: String,
        interval: Duration = Duration.ofMinutes(1),
    ): RecurringWork =
        object : RecurringWork {
            override val name = name
            override val interval = interval

            override fun run() = Unit
        }

    @Test
    fun `each profile's scheduler has the sum of its definitions' ceilings`() {
        val plans = JobTopology.profilePlans(catalog, properties())

        assertThat(plans.map { it.profile.id to it.threads }).containsExactly("batch" to 2, "interactive" to 4)
        assertThat(plans.last().ceilings).containsExactlyInAnyOrderEntriesOf(mapOf("tickets.summarize" to 3, "tickets.index" to 1))
    }

    @Test
    fun `a declared definition without a ceiling, a ceiling for nothing, and a ceiling below one are each a problem`() {
        val problems =
            JobTopology.catalogProblems(
                catalog,
                properties(
                    mapOf(
                        "tickets.summarize" to 0,
                        "tickets.gone" to 2,
                        "tickets.export" to 1,
                    ),
                ),
            )

        assertThat(problems.map { it.path to it.code }).containsExactly(
            "rain.jobs.workers.tickets.index" to ProblemCode.REQUIRED,
            "rain.jobs.workers.tickets.gone" to ProblemCode.UNKNOWN_KEY,
            "rain.jobs.workers.tickets.summarize" to ProblemCode.INVALID,
        )
    }

    @Test
    fun `a consistent worker declaration has no problems`() {
        val problems =
            JobTopology.workerProblems(
                catalog,
                listOf(handler(summarize), handler(index), handler(export)),
                listOf(recurring("tickets.sweep")),
                properties(),
            )

        assertThat(problems).isEmpty()
    }

    @Test
    fun `a missing handler, two handlers for one definition, and a handler for an undeclared definition are problems`() {
        val stranger = Fixtures.definition("tickets.summarize", "batch")

        val problems =
            JobTopology.workerProblems(
                catalog,
                listOf(handler(summarize), handler(stranger), handler(export)),
                listOf(recurring("tickets.sweep")),
                properties(),
            )

        assertThat(problems.map { it.path to it.code }).containsExactly(
            "jobs:definition:tickets.summarize" to ProblemCode.CONTRADICTS,
            "jobs:definition:tickets.summarize" to ProblemCode.INVALID,
            "jobs:definition:tickets.index" to ProblemCode.REQUIRED,
        )
    }

    @Test
    fun `recurring work the deployment requires must be contributed, and contributed work must be required`() {
        val problems =
            JobTopology.workerProblems(
                catalog,
                listOf(handler(summarize), handler(index), handler(export)),
                listOf(recurring("tickets.unlisted")),
                properties(required = listOf("tickets.sweep")),
            )

        assertThat(problems.map { it.message }).containsExactly(
            "names tickets.sweep, which no RecurringWork bean contributes",
            "does not name tickets.unlisted, which a RecurringWork bean contributes",
        )
    }

    @Test
    fun `recurring names that are malformed, doubled, rain's own, a definition's, or with no interval are problems`() {
        val problems =
            JobTopology.workerProblems(
                catalog,
                listOf(handler(summarize), handler(index), handler(export)),
                listOf(
                    recurring("Bad Name"),
                    recurring("tickets.sweep"),
                    recurring("tickets.sweep"),
                    recurring(JobTopology.REAPER),
                    recurring("tickets.export"),
                    recurring("tickets.idle", Duration.ZERO),
                ),
                properties(required = listOf("Bad Name", "tickets.sweep", "tickets.export", "tickets.idle")),
            )

        assertThat(problems.map { it.path to it.code }).containsExactly(
            "jobs:recurring:Bad Name" to ProblemCode.INVALID,
            "jobs:recurring:tickets.sweep" to ProblemCode.CONTRADICTS,
            "jobs:recurring:rain.jobs.reaper" to ProblemCode.CONTRADICTS,
            "jobs:recurring:tickets.export" to ProblemCode.CONTRADICTS,
            "jobs:recurring:tickets.idle" to ProblemCode.INVALID,
        )
    }
}
