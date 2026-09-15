package com.gd.rain.sample.seed

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.RequiredFromEnvironment
import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.boot.seed.Seeder
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.sample.agent.AgentRegistry
import com.gd.rain.sample.agent.Agents
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `sample.seed` — the agents the seed command enrols, one per role, and the password they start with. The password is
 * read only by the seed command, and in `prod` only from the environment (`SAMPLE_SEED_INITIALPASSWORD`).
 */
@ConfigurationProperties(SeedProperties.PREFIX)
data class SeedProperties(
    /** One agent per role, keyed by the role's slug. */
    val agents: Map<String, SeedAgent>,
    @param:RequiredFromEnvironment(DeploymentStage.PROD)
    val initialPassword: String? = null,
) {
    data class SeedAgent(
        val identifier: String,
        val displayName: String,
    ) : ConfigurationSection

    fun problems(): List<ConfigurationProblem> =
        problems {
            HelpdeskRoles.SEEDED.sorted().forEach { role ->
                expect(
                    role in agents,
                    "$PREFIX.agents.$role",
                    ProblemCode.REQUIRED,
                ) { "no agent is stated; the seed enrols one agent for every role" }
            }
            agents.keys.filterNot(HelpdeskRoles.SEEDED::contains).sorted().forEach { role ->
                add(
                    ConfigurationProblem(
                        "$PREFIX.agents.$role",
                        ProblemCode.INVALID,
                        "names no role of this application; the roles are ${HelpdeskRoles.SEEDED.sorted().joinToString(", ")}",
                    ),
                )
            }
            agents.toSortedMap().forEach { (role, agent) ->
                expect(agent.identifier.isNotBlank(), "$PREFIX.agents.$role.identifier") { "is blank" }
                expect(agent.displayName.isNotBlank(), "$PREFIX.agents.$role.display-name") { "is blank" }
            }
            agents.entries
                .groupBy({ Agents.NORMALIZATION.normalize(it.value.identifier) }, { it.key })
                .filterValues { it.size > 1 }
                .toSortedMap()
                .forEach { (identifier, roles) ->
                    add(
                        ConfigurationProblem(
                            "$PREFIX.agents",
                            ProblemCode.CONTRADICTS,
                            "roles ${roles.sorted().joinToString(", ")} name one identifier, $identifier",
                        ),
                    )
                }
            initialPassword?.let { expect(it.isNotBlank(), "$PREFIX.initial-password") { "is blank" } }
        }

    override fun toString(): String =
        "SeedProperties(agents=$agents, initialPassword=${if (initialPassword == null) "unstated" else "<redacted>"})"

    companion object {
        const val PREFIX = "sample.seed"
    }
}

/** The seed command enrols every agent with the initial password, so a process in the `seeder` role has one. */
class InitialPasswordCheck(
    private val seed: SeedProperties,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        if (seed.initialPassword != null) {
            emptyList()
        } else {
            listOf(
                ConfigurationProblem(
                    "${SeedProperties.PREFIX}.initial-password",
                    ProblemCode.REQUIRED,
                    "no value is provided; the seed command enrols every agent with it",
                ),
            )
        }
}

/** Writes the roles an agent can hold. */
class RolesSeeder(
    private val provisioning: AccessProvisioning,
) : Seeder {
    override val name: String = "helpdesk.roles"
    override val order: Int = 10

    override fun seed() {
        provisioning.ensureRole(HelpdeskRoles.SUPERVISOR, "Supervisor", HelpdeskRoles.SUPERVISOR_PERMISSIONS)
        provisioning.ensureRole(HelpdeskRoles.RESPONDER, "Responder", HelpdeskRoles.RESPONDER_PERMISSIONS)
    }
}

/**
 * Enrols one agent per role: the agent and its profile, its role, and its password. Each write asks its own question —
 * a run that stopped between two of them completes on the next run, and nothing that exists is overwritten.
 */
class AgentsSeeder(
    private val provisioning: AccessProvisioning,
    private val agents: AgentRegistry,
    private val seed: SeedProperties,
) : Seeder {
    override val name: String = "helpdesk.agents"
    override val order: Int = 20

    override fun seed() {
        val password = checkNotNull(seed.initialPassword) { "sample.seed.initial-password is checked as stated in the seeder role" }
        seed.agents.toSortedMap().forEach { (role, agent) ->
            val subject = Agents.ref(agents.ensure(agent.identifier, agent.displayName))
            provisioning.grantRole(subject, role)
            provisioning.enrolPassword(subject, agent.identifier, password)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnRainRole(RuntimeRole.SEEDER)
class SeedConfiguration {
    @Bean
    fun initialPasswordCheck(seed: SeedProperties): ConfigurationCheck = InitialPasswordCheck(seed)

    @Bean
    fun helpdeskRolesSeeder(provisioning: AccessProvisioning): Seeder = RolesSeeder(provisioning)

    @Bean
    fun helpdeskAgentsSeeder(
        provisioning: AccessProvisioning,
        agents: AgentRegistry,
        seed: SeedProperties,
    ): Seeder = AgentsSeeder(provisioning, agents, seed)
}
