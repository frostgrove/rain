package com.gd.rain.jobs

import com.gd.rain.jobs.admin.CancelOutcome
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.support.AdminFixture
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Tag("integration")
class CancelBySubjectIT {
    private val ticket = SubjectKey("ticket:7")

    private fun options(
        subject: SubjectKey?,
        dedupe: Dedupe = Dedupe.None,
    ) = EnqueueOptions(dedupe, JobPriority(50), subjectKey = subject)

    @Test
    fun `every live invocation about the subject is cancelled in fixed batches and its reservations released, and nothing else`() {
        val fixture = AdminFixture("cancel_subject")
        val count = JobAdministration.CANCEL_BATCH + 3
        repeat(count - 1) { fixture.queue.queue.enqueue(Fixtures.definition(), Note("$it"), options(ticket)) }
        val reserved =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("r"), options(ticket, Dedupe.Unique("k")))
                .invocation
        val otherSubject =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("o"), options(SubjectKey("ticket:8")))
                .invocation
        val noSubject =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), options(null))
                .invocation
        val finished =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("f"), options(ticket))
                .invocation
        fixture.terminal(finished, JobState.SUCCEEDED, Fixtures.START)

        val outcome = fixture.administration.cancelBySubject(ticket)

        assertThat(outcome.cancelled).isEqualTo(count.toLong())
        assertThat(outcome.batches).isEqualTo(2)
        assertThat(fixture.row(reserved)["state"]).isEqualTo("cancelled")
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL")).isZero()
        assertThat(fixture.row(otherSubject)["state"]).isEqualTo("queued")
        assertThat(fixture.row(noSubject)["state"]).isEqualTo("queued")
        assertThat(fixture.row(finished)["state"]).isEqualTo("succeeded")
        assertThat(fixture.administration.cancelBySubject(ticket)).describedAs("nothing is left to cancel").isEqualTo(CancelOutcome(0, 1))
    }

    @Test
    fun `a running attempt about the subject loses its lease at the next renewal and its execution is removed`() {
        val attempts = AttemptFixture("cancel_running")
        val admin = AdminFixture(attempts.queue)
        val id =
            attempts.queue.queue
                .enqueue(attempts.definition, Note("n"), options(ticket))
                .invocation
        val running = CountDownLatch(1)
        val attempt =
            attempts.start(id) { _, _ ->
                running.countDown()
                CountDownLatch(1).await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
            }
        Awaits.latch(running, "the attempt to run")

        assertThat(admin.administration.cancelBySubject(ticket).cancelled).isEqualTo(1)
        val report = attempts.renewer.renewOnce()

        assertThat(report.lost).containsExactly(id)
        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(Completion.Remove)
        assertThat(admin.row(id)["state"]).isEqualTo("cancelled")
        assertThat(admin.row(id)["retry_spent"]).isEqualTo(0)
    }

    @Test
    fun `the cancellation batch seeks the subject index under a limit`() {
        val fixture = AdminFixture("cancel_plan")

        val plan =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(fixture.jooq.cancelQuery(ticket, JobAdministration.CANCEL_BATCH, Fixtures.START)),
                generic = false,
            )

        assertThat(plan.usesIndex("ix_job_invocation_subject")).describedAs(plan.json).isTrue()
        assertThat(plan.usesIndex("job_invocation_pkey")).describedAs("the picked ids are updated by key").isTrue()
        assertThat(plan.boundedScan("job_invocation")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }
}
