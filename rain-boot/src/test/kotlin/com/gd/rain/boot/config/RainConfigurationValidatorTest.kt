package com.gd.rain.boot.config

import com.gd.rain.boot.config.elsewhere.Credentials
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration

class RainConfigurationValidatorTest {
    data class WebSection(
        val bodyLimitBytes: Long,
        val timeout: Duration = Duration.ofSeconds(30),
        val headers: Map<String, String> = emptyMap(),
        val origins: List<String> = emptyList(),
        val limits: Map<String, Int> = emptyMap(),
        val pools: Map<String, PoolSection> = emptyMap(),
    )

    data class PoolSection(
        val size: Int = 1,
    ) : ConfigurationSection

    data class StoreSection(
        val poolName: String,
        val credentials: Credentials,
    )

    private val web =
        SectionSpec("rain.web", WebSection::class, Presence.REQUIRED) { section, _ ->
            problems {
                expect(section.bodyLimitBytes > 0, "rain.web.body-limit-bytes") { "is ${section.bodyLimitBytes}; it has to be positive" }
                expect(!section.timeout.isNegative, "rain.web.timeout") { "is negative" }
            }
        }
    private val store = SectionSpec("rain.store", StoreSection::class, Presence.OPTIONAL)

    private val bodyFitsTimeout =
        object : CrossSectionRule {
            override val id = "web-body-vs-store"
            override val reads = setOf("rain.web", "rain.store")

            override fun evaluate(
                sections: BoundSections,
                stage: DeploymentStage,
            ): RuleOutcome =
                RuleOutcome.Violated(listOf(ConfigurationProblem("rain.store.pool-name", ProblemCode.CONTRADICTS, "cross rule ran")))
        }

    private val contributor =
        object : ConfigurationContributor {
            override val sections = listOf(web, store)
            override val rules = listOf(bodyFitsTimeout)
        }

    private val valid =
        arrayOf(
            "spring.application.name" to "sample",
            "rain.runtime.roles" to "api",
            "rain.deployment.stage" to "test",
            "rain.web.body-limit-bytes" to "1024",
        )

    @Test
    fun `a valid configuration reports nothing fatal`() {
        val report = validate(*valid)

        assertThat(report.fatal).isEmpty()
    }

    @Test
    fun `a missing stage is refused instead of defaulting to development`() {
        val report = validate(*valid.filterNot { it.first == "rain.deployment.stage" }.toTypedArray())

        assertThat(report.fatal).contains(
            ConfigurationProblem("rain.deployment.stage", ProblemCode.REQUIRED, "no value is provided; state one of dev, test, prod"),
        )
    }

    @Test
    fun `every problem of every kind is reported by one pass`() {
        val report =
            validate(
                "rain.deployment.stage" to "production",
                "rain.web.body-limit-bytes" to "0",
                "rain.web.timeout" to "-1s",
                "rain.store.pool-name" to "main",
                "rain.store.credentials.user" to "app",
                "rain.bogus" to "x",
            )

        assertThat(report.fatal.map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.runtime" to ProblemCode.REQUIRED,
            "rain.deployment.stage" to ProblemCode.INVALID,
            "spring.application.name" to ProblemCode.REQUIRED,
            "rain.store.credentials.password" to ProblemCode.REQUIRED,
            "rain.bogus" to ProblemCode.UNKNOWN_KEY,
        )
        assertThat(report.notEvaluated.map { it.path }).containsExactly("web-body-vs-store")
    }

    @Test
    fun `section problems and cross-section rules run together once the stage is known`() {
        val report =
            validate(
                *valid,
                "rain.web.body-limit-bytes" to "0",
                "rain.web.timeout" to "-1s",
                "rain.store.pool-name" to "main",
                "rain.store.credentials.user" to "app",
                "rain.store.credentials.password" to "secret",
            )

        assertThat(report.fatal.map { it.path }).containsExactly("rain.web.body-limit-bytes", "rain.web.timeout", "rain.store.pool-name")
    }

    @Test
    fun `a rule reading an absent optional section is not evaluated, and that is not fatal`() {
        val report = validate(*valid)

        assertThat(report.problems).containsExactly(
            ConfigurationProblem("web-body-vs-store", ProblemCode.NOT_EVALUATED, "reads rain.store, which this application does not bind"),
        )
        assertThat(report.fatal).isEmpty()
    }

    @Test
    fun `an absent required section is refused`() {
        val report = validate(*valid.filterNot { it.first.startsWith("rain.web") }.toTypedArray())

        assertThat(
            report.fatal,
        ).contains(ConfigurationProblem("rain.web", ProblemCode.REQUIRED, "the section is required and no key under it is stated"))
    }

    @Test
    fun `missing leaves are named inside a nested section declared in another package`() {
        val report = validate(*valid, "rain.store.credentials.user" to "app")

        assertThat(report.fatal.map { it.path }).containsExactlyInAnyOrder("rain.store.pool-name", "rain.store.credentials.password")
    }

    @Test
    fun `a value that cannot be converted is refused with its path`() {
        val report =
            validate(
                *valid.filterNot { it.first == "rain.web.body-limit-bytes" }.toTypedArray(),
                "rain.web.body-limit-bytes" to "large",
            )

        assertThat(report.fatal.single().path).isEqualTo("rain.web.body-limit-bytes")
        assertThat(report.fatal.single().code).isEqualTo(ProblemCode.INVALID)
    }

    @Test
    fun `keys under maps and lists of a declared section are claimed, members that do not exist are not`() {
        val report =
            validate(
                *valid,
                "rain.web.headers.x-frame-options" to "DENY",
                "rain.web.origins[0]" to "https://example.org",
                "rain.web.timout" to "5s",
            )

        assertThat(report.fatal.map { it.path to it.code }).containsExactly("rain.web.timout" to ProblemCode.UNKNOWN_KEY)
    }

    @Test
    fun `a dotted key under a map of scalars is one key, and a key under a map of sections still has to name a member`() {
        val report =
            validate(
                *valid,
                "rain.web.limits.tickets.summarize" to "3",
                "rain.web.pools.main.size" to "2",
                "rain.web.pools.main.sise" to "2",
            )

        assertThat(report.fatal.map { it.path to it.code }).containsExactly("rain.web.pools.main.sise" to ProblemCode.UNKNOWN_KEY)
    }

    @Test
    fun `keys from environment variables are not judged as unknown`() {
        val environment = environmentOf(*valid)
        environment.propertySources.addLast(
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf("RAIN_UNRELATED" to "x"),
            ),
        )

        assertThat(RainConfigurationValidator.validate(environment, listOf(contributor), emptyList()).fatal).isEmpty()
    }

    @Test
    fun `a secret stated in a file is refused in production and accepted from the environment`() {
        val stored =
            arrayOf(
                "spring.application.name" to "sample",
                "rain.runtime.roles" to "api",
                "rain.web.body-limit-bytes" to "1",
                "rain.store.pool-name" to "main",
                "rain.store.credentials.user" to "app",
            )

        val fromFile = environmentOf(*stored, "rain.deployment.stage" to "prod", "rain.store.credentials.password" to "secret")
        val refusal = RainConfigurationValidator.validate(fromFile, listOf(contributor), emptyList()).fatal

        assertThat(refusal.map { it.path to it.code }).contains("rain.store.credentials.password" to ProblemCode.INVALID)
        assertThat(refusal.first { it.path == "rain.store.credentials.password" }.message).doesNotContain("secret")

        val fromEnvironment = environmentOf(*stored, "rain.deployment.stage" to "prod")
        fromEnvironment.propertySources.addLast(
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf("RAIN_STORE_CREDENTIALS_PASSWORD" to "secret"),
            ),
        )
        assertThat(RainConfigurationValidator.validate(fromEnvironment, listOf(contributor), emptyList()).fatal.map { it.path })
            .doesNotContain("rain.store.credentials.password")

        val inDevelopment = environmentOf(*stored, "rain.deployment.stage" to "dev", "rain.store.credentials.password" to "secret")
        assertThat(RainConfigurationValidator.validate(inDevelopment, listOf(contributor), emptyList()).fatal.map { it.path })
            .doesNotContain("rain.store.credentials.password")
    }

    @Test
    fun `a section prefix declared twice is a contradiction`() {
        val twice =
            object : ConfigurationContributor {
                override val sections = listOf(SectionSpec("rain.web", WebSection::class, Presence.OPTIONAL))
            }

        val report = RainConfigurationValidator.validate(environmentOf(*valid), listOf(contributor, twice), emptyList())

        assertThat(
            report.fatal,
        ).contains(ConfigurationProblem("rain.web", ProblemCode.CONTRADICTS, "the section is declared more than once"))
    }

    private fun validate(vararg properties: Pair<String, String>): ValidationReport =
        RainConfigurationValidator.validate(environmentOf(*properties), listOf(contributor), emptyList())

    private fun environmentOf(vararg properties: Pair<String, String>): StandardEnvironment {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        environment.propertySources.addFirst(MapPropertySource("test", HashMap<String, Any>(properties.toMap())))
        return environment
    }
}
