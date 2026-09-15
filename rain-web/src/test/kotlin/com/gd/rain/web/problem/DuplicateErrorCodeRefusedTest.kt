package com.gd.rain.web.problem

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Gap 35: the registry is built from every catalog, and a duplicate or malformed code refuses the start. */
class DuplicateErrorCodeRefusedTest {
    class Widgets : ErrorCodeCatalog {
        override val owner: String = "widgets"
        override val codes: List<ErrorCode> = listOf(ErrorCode.of("widget_locked", "the widget is locked"))
    }

    class Gadgets : ErrorCodeCatalog {
        override val owner: String = "gadgets"
        override val codes: List<ErrorCode> =
            listOf(ErrorCode.of("widget_locked", "locked"), ErrorCode.of("not_found", "missing"))
    }

    class SecondWidgets : ErrorCodeCatalog {
        override val owner: String = "widgets"
        override val codes: List<ErrorCode> = listOf(ErrorCode.of("widget_bent", "the widget is bent"))
    }

    class Malformed : ErrorCodeCatalog {
        override val owner: String = "malformed"
        override val codes: List<ErrorCode> get() = listOf(ErrorCode.of("Not-Snake", "a code nobody can branch on"))
    }

    @Test
    fun `a code two catalogs declare is refused naming every owner`() {
        assertThatThrownBy { ErrorCodeRegistrar.register(listOf(RainErrorCodes, RainWebErrorCodes, Widgets(), Gadgets())) }
            .isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
                assertThat(refusal.problems).containsExactly(
                    ConfigurationProblem("error-code:not_found", ProblemCode.CONTRADICTS, "is declared by rain-core, gadgets"),
                    ConfigurationProblem("error-code:widget_locked", ProblemCode.CONTRADICTS, "is declared by widgets, gadgets"),
                )
            }
    }

    @Test
    fun `a malformed code and a shared owner are reported in the same refusal`() {
        assertThatThrownBy { ErrorCodeRegistrar.register(listOf(Widgets(), SecondWidgets(), Malformed())) }
            .isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
                assertThat(refusal.problems.map { it.path to it.code }).containsExactly(
                    "error-catalog:widgets" to ProblemCode.CONTRADICTS,
                    "error-catalog:malformed" to ProblemCode.INVALID,
                )
                assertThat(refusal.problems.last().message).contains("Not-Snake")
            }
    }

    @Test
    fun `a duplicate contributed as a bean fails the start with the problems`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainWebErrorAutoConfiguration::class.java))
            .withBean(Widgets::class.java)
            .withBean(Gadgets::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                val refusal =
                    generateSequence(
                        context.startupFailure,
                        Throwable::cause,
                    ).filterIsInstance<ConfigurationProblemsException>().first()
                assertThat(refusal.problems.map { it.path }).containsExactly("error-code:not_found", "error-code:widget_locked")
            }
    }

    @Test
    fun `every catalog bean is registered, rain's own included`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainWebErrorAutoConfiguration::class.java))
            .withBean(Widgets::class.java)
            .run { context ->
                val registry = context.getBean(ErrorCodeRegistry::class.java)

                assertThat(registry.ownerOf(ErrorCode.of("widget_locked", "x"))).isEqualTo("widgets")
                assertThat(registry.ownerOf(RainWebErrorCodes.CROSS_SITE)).isEqualTo("rain-web")
                assertThat(registry.ownerOf(RainErrorCodes.DEADLINE_EXCEEDED)).isEqualTo("rain-core")
                assertThat(registry.size).isEqualTo(RainErrorCodes.codes.size + RainWebErrorCodes.codes.size + 1)
            }
    }
}
