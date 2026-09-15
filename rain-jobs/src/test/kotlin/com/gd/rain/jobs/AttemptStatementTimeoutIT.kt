package com.gd.rain.jobs

import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.persistence.lock.AdvisoryLockStore
import org.assertj.core.api.Assertions.assertThat
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * Every transaction an attempt's handler opens carries `SET LOCAL statement_timeout` from the attempt's bound, a
 * transaction outside any attempt carries none, and (gap 9) a bound that cannot be set is never swallowed.
 */
@Tag("integration")
class AttemptStatementTimeoutIT {
    @Test
    fun `a transaction opened inside an attempt is bounded, inside a step by the step, and outside any attempt not at all`() {
        val fixture = AttemptFixture("statement_bound", profile = Fixtures.profile(stepTimeout = Duration.ofMinutes(1)))
        val id = fixture.enqueue()
        val template = TransactionTemplate(fixture.database.transactions)
        var inAttempt = ""
        var inStep = ""

        fixture.run(id) { _, attempt ->
            inAttempt = requireNotNull(template.execute { currentTimeout(fixture) })
            inStep = attempt.step(Duration.ofSeconds(3)) { requireNotNull(template.execute { currentTimeout(fixture) }) }
        }

        assertThat(inAttempt).isEqualTo("1min")
        assertThat(inStep).isEqualTo("3s")
        assertThat(template.execute { currentTimeout(fixture) }).describedAs("a request's bound is its own").isEqualTo("0")
    }

    @Test
    fun `a bound that cannot be set refuses the transaction's commit and charges the attempt`() {
        val fixture =
            AttemptFixture("statement_bound_failure", decorateStatements = { real ->
                object : AdvisoryLockStore by real {
                    override fun statementTimeout(timeout: Duration): Unit = throw DataAccessResourceFailureException("SET refused")
                }
            })
        fixture.sql("CREATE TABLE public.writes (invocation UUID PRIMARY KEY)")
        val id = fixture.enqueue()
        val template = TransactionTemplate(fixture.database.transactions)

        fixture.run(id) { _, _ ->
            try {
                template.executeWithoutResult { fixture.database.dsl.execute("INSERT INTO public.writes VALUES ('$id')") }
            } catch (_: IllegalStateException) {
                // A handler that swallows the refusal is charged all the same.
            }
        }

        assertThat(fixture.database.count("SELECT count(*) FROM public.writes")).describedAs("the unbounded write did not commit").isZero()
        assertThat(fixture.row(id)["state"]).isEqualTo("queued")
        assertThat(fixture.row(id)["retry_spent"]).isEqualTo(1)
        assertThat(fixture.row(id)["failure_code"]).isEqualTo(FailureCode.STATEMENT_BOUND_FAILED)
    }

    private fun currentTimeout(fixture: AttemptFixture): String =
        requireNotNull(fixture.database.dsl.fetchValue(DSL.field("current_setting('statement_timeout')", String::class.java)))
}
