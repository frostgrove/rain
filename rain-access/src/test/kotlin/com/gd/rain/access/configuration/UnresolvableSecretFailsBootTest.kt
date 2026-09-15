package com.gd.rain.access.configuration

import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.accessProperties
import com.gd.rain.access.support.startAccessApplication
import com.gd.rain.core.config.ConfigurationProblemsException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType

/**
 * Gap 20: a signing key that cannot be resolved refuses the start, before any bean exists, naming the property and never
 * what was written in it.
 */
class UnresolvableSecretFailsBootTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "file:/nonexistent/rain-access/signing.key",
            "file:relative/signing.key",
            "base64:not base64 at all!",
            "base64:c2hvcnQ=",
        ],
    )
    fun `the start is refused naming rain-access-token-signing-key`(written: String) {
        val failure =
            runCatching {
                startAccessApplication(
                    AccessApplication::class.java,
                    WebApplicationType.NONE,
                    *accessProperties("rain.access.token.signing-key=$written"),
                ).close()
            }.exceptionOrNull()

        val refusal = generateSequence(failure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().firstOrNull()
        assertThat(refusal).describedAs("the start was refused by configuration validation: $failure").isNotNull()
        val problem = requireNotNull(refusal).problems.single { it.path == "rain.access.token.signing-key" }
        assertThat(problem.code.fatal).isTrue()
        assertThat(refusal.message).doesNotContain(written.substringAfter(':'))
    }
}
