package com.gd.rain.jobs

import com.gd.rain.jobs.admin.DeadLetter
import com.gd.rain.jobs.admin.DeadLetterCursor
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.jobs.support.AdminFixture
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/** Gap 5: dead letters page by keyset on `(finished_at DESC, id DESC)` — no OFFSET, no count, bounded per page. */
@Tag("integration")
class DeadLetterKeysetIT {
    private val write = Fixtures.definition("notes.write")
    private val index = Fixtures.definition("notes.index")

    @Test
    fun `pages are complete, disjoint and newest first, including across equal finish times`() {
        val fixture = AdminFixture("dead_letters_pages", definitions = listOf(write, index))
        val ids =
            (1..11).map {
                fixture.queue.queue
                    .enqueue(write, Note("$it"), Fixtures.options())
                    .invocation
            }
        ids.forEachIndexed { position, id ->
            // Four rows share each finish time, so the id is what orders them.
            val state = if (position % 2 == 0) JobState.DEAD else JobState.FAILED
            fixture.terminal(id, state, Fixtures.START.plusSeconds((position / 4).toLong()))
        }
        val live =
            fixture.queue.queue
                .enqueue(write, Note("live"), Fixtures.options())
                .invocation

        val seen = mutableListOf<DeadLetter>()
        var cursor: DeadLetterCursor? = null
        var pages = 0
        do {
            val page = fixture.administration.deadLetters(null, cursor, limit = 4)
            seen += page.items
            cursor = page.next
            pages++
        } while (cursor != null)

        assertThat(pages).isEqualTo(3)
        assertThat(seen.map { it.id }).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids).doesNotContain(live)
        val databaseOrder =
            fixture.database.jdbc.queryForList(
                "SELECT id FROM rain_jobs.job_invocation WHERE state IN ('failed', 'dead') ORDER BY finished_at DESC, id DESC",
                UUID::class.java,
            )
        assertThat(seen.map { it.id }).isEqualTo(databaseOrder)
        assertThat(seen.first().failureMessage).isEqualTo("it broke")
    }

    @Test
    fun `a page of one definition holds only that definition's dead letters`() {
        val fixture = AdminFixture("dead_letters_definition", definitions = listOf(write, index))
        val mine =
            fixture.queue.queue
                .enqueue(index, Note("i"), Fixtures.options())
                .invocation
        val other =
            fixture.queue.queue
                .enqueue(write, Note("w"), Fixtures.options())
                .invocation
        fixture.terminal(mine, JobState.DEAD, Fixtures.START)
        fixture.terminal(other, JobState.DEAD, Fixtures.START)

        val page = fixture.administration.deadLetters("notes.index", null, 10)

        assertThat(page.items.map { it.id }).containsExactly(mine)
        assertThat(page.next).isNull()
    }

    @Test
    fun `a page size outside the declared bound is refused`() {
        val fixture = AdminFixture("dead_letters_limit")

        assertThatThrownBy { fixture.administration.deadLetters(null, null, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            fixture.administration.deadLetters(null, null, JobAdministration.MAX_PAGE + 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `both page shapes seek their dead-letter index under a limit`() {
        val fixture = AdminFixture("dead_letters_plan")
        val cursor = DeadLetterCursor(Fixtures.START, UUID(0, 1))

        val all =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(fixture.jooq.deadLettersQuery(null, cursor, 50)),
                false,
            )
        val one =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(fixture.jooq.deadLettersQuery("notes.write", cursor, 50)),
                false,
            )

        assertThat(all.usesIndex("ix_job_invocation_dead_letters")).describedAs(all.json).isTrue()
        assertThat(one.usesIndex("ix_job_invocation_dead_letters_definition")).describedAs(one.json).isTrue()
        listOf(all, one).forEach { plan ->
            assertThat(plan.boundedScan("rain_jobs", "job_invocation")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
        }
    }
}
