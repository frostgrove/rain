package com.gd.rain.jobs

import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.jobs.support.WorkerFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Gap 4: among due work, a higher [JobPriority] runs first — db-scheduler's own order, not an inverted one. */
@Tag("integration")
class PriorityOrderIT {
    @Test
    fun `due work runs in descending priority on a one-thread scheduler`() {
        val ran = CopyOnWriteArrayList<String>()
        val definition = Fixtures.definition()
        val fixture =
            WorkerFixture(
                "priority_order",
                listOf(Fixtures.profile()),
                listOf(definition),
                mapOf(definition.name to 1),
                handlers = { _ ->
                    listOf(
                        object : JobHandler<Note> {
                            override val definition = definition

                            override fun handle(
                                payload: Note,
                                attempt: Attempt,
                            ) {
                                ran += payload.text
                            }
                        },
                    )
                },
            )
        listOf("low" to 10, "high" to 90, "middle" to 50, "lowest" to -5).forEach { (text, priority) ->
            fixture.queue.queue.enqueue(definition, Note(text), Fixtures.options(priority = priority))
        }

        fixture.worker.start()
        try {
            Awaits.until("all four to run") { ran.size == 4 }
        } finally {
            fixture.worker.stop()
        }

        assertThat(ran).containsExactly("high", "middle", "low", "lowest")
    }
}
