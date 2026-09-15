package com.gd.rain.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * No rain statement names `scheduled_tasks`: that table belongs to db-scheduler's poller, heartbeat writer and
 * dead-execution detector, and a row lock rain took on it would stall a scheduler's whole heartbeat batch. The fence
 * and the reaper read `job_invocation`. The table name reaches db-scheduler through its builders only.
 */
class ScheduledTasksIsolationTest {
    @Test
    fun `no Kotlin source issues a statement against the db-scheduler table`() {
        assertThat(statementsNaming(Path("src/main/kotlin"))).isEmpty()
    }

    @Test
    fun `the scanner finds such a statement`() {
        val leak = "\"SELECT 1 FROM rain_jobs.scheduled_tasks WHERE picked FOR UPDATE\""

        assertThat(
            LITERAL
                .findAll(leak)
                .map { it.groupValues.drop(1).joinToString("") }
                .filter(::isStatementOnTable)
                .toList(),
        ).hasSize(1)
    }

    @Test
    fun `the table name reaches db-scheduler through the builders and the migration only`() {
        val factory = Path("src/main/kotlin/com/gd/rain/jobs/internal/worker/ManagedScheduler.kt").readText()

        assertThat(factory).contains("const val TABLE: String = \"rain_jobs.scheduled_tasks\"").contains(".tableName(TABLE)")
        assertThat(Path("src/main/resources/db/rain/jobs/V1__jobs.sql").readText()).contains("CREATE TABLE scheduled_tasks (")
    }

    @Test
    fun `the db-scheduler table has no generated table object`() {
        val generated = Path("build/generated/sources/jooq/com/gd/rain/jobs/jooq/tables")
        check(generated.toFile().isDirectory) { "$generated does not exist; generateJooq has not run" }

        assertThat(generated.listDirectoryEntries("*.java").map { it.fileName.toString() })
            .contains("JobInvocation.java", "JobIntent.java")
            .doesNotContain("ScheduledTasks.java")
    }

    private fun statementsNaming(root: Path): List<String> {
        check(root.toFile().isDirectory) { "$root does not exist; the scan would pass vacuously" }
        return root
            .walk()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                LITERAL
                    .findAll(text)
                    .filter { isStatementOnTable(it.groupValues.drop(1).joinToString("")) }
                    .map { "${file.invariantSeparatorsPathString}:${text.take(it.range.first).count { c -> c == '\n' } + 1}" }
            }.toList()
    }

    private fun isStatementOnTable(literal: String): Boolean = literal.contains("scheduled_tasks") && STATEMENT.containsMatchIn(literal)

    private companion object {
        val LITERAL = Regex("\"{3}([\\s\\S]*?)\"{3}|\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val STATEMENT = Regex("\\b(select|insert|update|delete)\\b[\\s\\S]*\\b(from|into|set|where)\\b", RegexOption.IGNORE_CASE)
    }
}
