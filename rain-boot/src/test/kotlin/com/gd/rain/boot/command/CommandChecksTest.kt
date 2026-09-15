package com.gd.rain.boot.command

import com.gd.rain.boot.config.ConfigurationProblemsFailureAnalyzer
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.boot.seed.Seeder
import com.gd.rain.boot.seed.SeedersCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments

class CommandChecksTest {
    private val report =
        object : CommandDeclaration {
            override val name = "report"
            override val description = "report"
            override val roles = emptySet<RuntimeRole>()
            override val properties = emptyMap<String, String>()
        }

    private fun command(name: String): RainCommand =
        object : RainCommand {
            override val name = name

            override fun run(
                arguments: ApplicationArguments,
                output: CommandOutput,
            ): Int = 0
        }

    @Test
    fun `a declared command answered by exactly one bean passes`() {
        assertThat(CommandHandlersCheck(report, listOf(command("report"), command("other"))).problems()).isEmpty()
    }

    @Test
    fun `a declared command nobody answers is a problem`() {
        assertThat(CommandHandlersCheck(report, listOf(command("other"))).problems().single().code).isEqualTo(ProblemCode.INVALID)
    }

    @Test
    fun `a declared command answered twice is a problem`() {
        assertThat(
            CommandHandlersCheck(report, listOf(command("report"), command("report"))).problems().single().code,
        ).isEqualTo(ProblemCode.CONTRADICTS)
    }

    @Test
    fun `seeder names are unique and well formed`() {
        fun seeder(name: String) =
            object : Seeder {
                override val name = name
                override val order = 0

                override fun seed() = Unit
            }

        val problems = SeedersCheck(listOf(seeder("roles"), seeder("roles"), seeder("Bad Name"))).problems()

        assertThat(problems.map { it.path to it.code }).containsExactlyInAnyOrder(
            "seeder:Bad Name" to ProblemCode.INVALID,
            "seeder:roles" to ProblemCode.CONTRADICTS,
        )
    }

    @Test
    fun `the failure analyzer prints every problem and no stack trace description`() {
        val refusal =
            ConfigurationProblemsException(
                listOf(
                    ConfigurationProblem("rain.deployment.stage", ProblemCode.REQUIRED, "no value is provided"),
                    ConfigurationProblem("rain.web.body-limit", ProblemCode.INVALID, "is zero"),
                ),
            )

        val analysis = ConfigurationProblemsFailureAnalyzer().analyze(IllegalStateException("wrapped", refusal))

        assertThat(analysis).isNotNull
        assertThat(analysis!!.description).contains("rain.deployment.stage [required]").contains("rain.web.body-limit [invalid]")
        assertThat(analysis.action).contains("start the application again")
    }
}
