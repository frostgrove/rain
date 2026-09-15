package com.gd.rain.boot.runtime

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class RuntimeSelectionTest {
    private val migrate = declaration("migrate", emptySet())
    private val seed = declaration("seed", setOf(RuntimeRole.SEEDER))
    private val declarations = listOf(migrate, seed)

    @Test
    fun `stated roles select those roles`() {
        assertThat(
            resolve("rain.runtime.roles" to "api,worker"),
        ).isEqualTo(RuntimeSelection.Roles(setOf(RuntimeRole.API, RuntimeRole.WORKER)))
    }

    @Test
    fun `an indexed list of roles is the same selection`() {
        assertThat(resolve("rain.runtime.roles[0]" to "worker")).isEqualTo(RuntimeSelection.Roles(setOf(RuntimeRole.WORKER)))
    }

    @Test
    fun `a stated command selects its declaration and the roles it declares`() {
        val selection = resolve("rain.runtime.command" to "seed")

        assertThat(selection).isEqualTo(RuntimeSelection.Command(seed))
        assertThat(RuntimeSelection.activeRoles(selection)).containsExactly(RuntimeRole.SEEDER)
    }

    @Test
    fun `neither roles nor a command is refused, naming both ways to start`() {
        val selection = resolve() as RuntimeSelection.Invalid

        val problem = selection.problems.single()
        assertThat(problem.code).isEqualTo(ProblemCode.REQUIRED)
        assertThat(problem.message).contains("api, worker, seeder").contains("migrate, seed")
    }

    @Test
    fun `roles and a command together are refused at both paths`() {
        val selection = resolve("rain.runtime.roles" to "api", "rain.runtime.command" to "seed") as RuntimeSelection.Invalid

        assertThat(selection.problems.map { it.path to it.code }).containsExactly(
            RuntimeSelection.ROLES to ProblemCode.EXCLUSIVE,
            RuntimeSelection.COMMAND to ProblemCode.EXCLUSIVE,
        )
    }

    @Test
    fun `an unknown role is refused rather than ignored`() {
        val selection = resolve("rain.runtime.roles" to "api,wrker") as RuntimeSelection.Invalid

        assertThat(selection.problems).containsExactly(
            ConfigurationProblem(RuntimeSelection.ROLES, ProblemCode.INVALID, "names \"wrker\", which is not one of api, worker, seeder"),
        )
    }

    @Test
    fun `a role spelled in another case is not that role`() {
        assertThat(resolve("rain.runtime.roles" to "API")).isInstanceOf(RuntimeSelection.Invalid::class.java)
    }

    @Test
    fun `a role named twice is refused`() {
        val selection = resolve("rain.runtime.roles" to "api,api") as RuntimeSelection.Invalid

        assertThat(selection.problems.map { it.message }).containsExactly("names \"api\" more than once")
    }

    @Test
    fun `an empty role list is refused`() {
        assertThat(resolve("rain.runtime.roles" to "")).isInstanceOf(RuntimeSelection.Invalid::class.java)
    }

    @Test
    fun `an undeclared command is refused, listing the declared ones`() {
        val selection = resolve("rain.runtime.command" to "migrat") as RuntimeSelection.Invalid

        assertThat(selection.problems.single().message).isEqualTo("is \"migrat\"; the declared commands are migrate, seed")
    }

    @Test
    fun `active roles of an invalid selection is a refusal, not an empty set`() {
        assertThatThrownBy { RuntimeSelection.activeRoles(resolve()) }.isInstanceOf(ConfigurationProblemsException::class.java)
    }

    @Test
    fun `a command declared twice or with a malformed name is a problem`() {
        val problems =
            CommandDeclarations.problems(
                listOf(migrate, declaration("migrate", emptySet()), declaration("Bad_Name", emptySet())),
            )

        assertThat(problems.map { it.code }).containsExactlyInAnyOrder(ProblemCode.INVALID, ProblemCode.CONTRADICTS)
    }

    private fun resolve(vararg properties: Pair<String, String>): RuntimeSelection {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", properties.toMap()))
        return RuntimeSelection.resolve(environment, declarations)
    }

    private fun declaration(
        name: String,
        roles: Set<RuntimeRole>,
    ): CommandDeclaration =
        object : CommandDeclaration {
            override val name = name
            override val description = name
            override val roles = roles
            override val properties = emptyMap<String, String>()
        }
}
