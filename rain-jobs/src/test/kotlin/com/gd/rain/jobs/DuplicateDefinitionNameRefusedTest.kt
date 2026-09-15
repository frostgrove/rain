package com.gd.rain.jobs

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Gap 9: two definitions under one name are a refusal, never a first-wins or last-wins choice. */
class DuplicateDefinitionNameRefusedTest {
    @Test
    fun `a definition name declared twice is a problem, and the catalogue answers no lookup`() {
        val catalog =
            JobCatalog(
                listOf(Fixtures.profile("standard"), Fixtures.profile("batch")),
                listOf(Fixtures.definition("notes.write", "standard"), JobDefinition.of<Note>("notes.write", "batch")),
            )

        assertThat(catalog.problems.map { it.path to it.code }).containsExactly("jobs:definition:notes.write" to ProblemCode.CONTRADICTS)
        assertThatThrownBy { catalog.definition("notes.write") }.isInstanceOf(ConfigurationProblemsException::class.java)
    }

    @Test
    fun `a profile declared twice, and a definition naming an undeclared profile, are problems`() {
        val catalog =
            JobCatalog(
                listOf(Fixtures.profile("standard"), Fixtures.profile("standard", retries = 1)),
                listOf(Fixtures.definition("notes.write", "nowhere")),
            )

        assertThat(catalog.problems.map { it.path to it.code }).containsExactly(
            "jobs:profile:standard" to ProblemCode.CONTRADICTS,
            "jobs:definition:notes.write" to ProblemCode.INVALID,
        )
    }
}
