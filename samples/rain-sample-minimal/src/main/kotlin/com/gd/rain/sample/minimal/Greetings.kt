package com.gd.rain.sample.minimal

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.core.config.problems
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

/** `sample.greetings` — the application's own section, validated with rain's in the same start-up. */
@ConfigurationProperties(GreetingProperties.PREFIX)
data class GreetingProperties(
    /** Names nobody is greeted as. */
    val reservedNames: Set<String>,
    val maxNameLength: Int,
) {
    companion object {
        const val PREFIX = "sample.greetings"
        const val MAX_NAME_LENGTH_CEILING = 256
    }
}

/** Declares `sample.greetings` to rain's configuration validation (registered in `META-INF/spring.factories`). */
class GreetingConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(GreetingProperties.PREFIX, GreetingProperties::class, Presence.REQUIRED) { greetings, _ ->
                problems {
                    expect(
                        greetings.maxNameLength in 1..GreetingProperties.MAX_NAME_LENGTH_CEILING,
                        "${GreetingProperties.PREFIX}.max-name-length",
                    ) {
                        "is ${greetings.maxNameLength}; it is between 1 and ${GreetingProperties.MAX_NAME_LENGTH_CEILING}"
                    }
                    expect(greetings.reservedNames.none(String::isBlank), "${GreetingProperties.PREFIX}.reserved-names") {
                        "names a blank name"
                    }
                }
            },
        )
}

object SampleErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-sample-minimal"

    val NAME_RESERVED: ErrorCode = ErrorCode.of("name_reserved", "this name is reserved")

    override val codes: List<ErrorCode> = listOf(NAME_RESERVED)
}

data class GreetingRequest(
    val name: String?,
)

data class Greeting(
    val text: String,
    val at: Instant,
)

@RestController
@RequestMapping("/v1/greetings")
class GreetingController(
    private val greetings: GreetingProperties,
    private val clock: Clock,
) {
    @PostMapping
    fun greet(
        @RequestBody request: GreetingRequest,
    ): Greeting {
        val name =
            request.name?.takeIf(String::isNotBlank)
                ?: throw Fault.validation(listOf(Violation(path("name"), RainErrorCodes.REQUIRED)))
        if (name.length > greetings.maxNameLength) {
            throw Fault.validation(
                listOf(Violation(path("name"), RainErrorCodes.TOO_LONG, "is longer than ${greetings.maxNameLength} characters")),
            )
        }
        if (name in greetings.reservedNames) throw Fault.conflict(SampleErrorCodes.NAME_RESERVED)
        return Greeting("hello, $name", clock.instant())
    }
}
