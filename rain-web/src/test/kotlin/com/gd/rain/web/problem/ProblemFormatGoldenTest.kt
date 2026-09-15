package com.gd.rain.web.problem

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Problem format v1 as one reviewed file: every fault kind and each rule of the format, rendered and compared byte for byte
 * with `src/test/resources/problem-format-v1.golden.json`. A change to the wire format changes that file in the same commit,
 * where review sees it. `./gradlew :rain-web:writeProblemGolden` rewrites the file; `check` only compares.
 */
class ProblemFormatGoldenTest {
    private val renderer = ProblemRenderer(ErrorCodeRegistrar.register(listOf(RainErrorCodes, RainWebErrorCodes)))
    private val json = JsonMapper.builder().build()

    private fun cases(): Map<String, Fault> =
        linkedMapOf<String, Fault>().apply {
            FaultKind.entries.forEach { kind ->
                put(
                    "kind.${kind.name.lowercase()}",
                    if (kind ==
                        FaultKind.VALIDATION
                    ) {
                        Fault.validation(listOf(Violation.at(path("name"), RainErrorCodes.REQUIRED)))
                    } else {
                        Fault(kind)
                    },
                )
            }
            put("detail.stated", Fault.conflict(message = "the record is closed"))
            put("retry_after.whole_seconds_rounded_up", Fault.retryable(retryAfter = Duration.ofMillis(1200)))
            put("retry_after.too_many_requests", Fault(FaultKind.TOO_MANY_REQUESTS, retryAfter = Duration.ofSeconds(3)))
            put(
                "validation.sorted_with_default_messages",
                Fault.validation(
                    listOf(
                        Violation.at(path("items", 1, "email"), RainErrorCodes.REQUIRED),
                        Violation.general(RainErrorCodes.CHECK, "the dates overlap"),
                        Violation.at(path("items", 0, "name"), RainErrorCodes.TOO_LONG),
                    ),
                ),
            )
            put("validation.partial_stated", Fault.validation(listOf(Violation.general(RainErrorCodes.CHECK)), partial = true))
            put(
                "validation.cut_at_limit",
                Fault.validation(
                    (0..ProblemFormat.MAX_ERRORS).map {
                        Violation.at(path("items", it), RainErrorCodes.REQUIRED)
                    },
                ),
            )
            put(
                "internal.says_only_that_it_failed",
                Fault(FaultKind.INTERNAL, message = "the disk at /var/data is full", cause = IllegalStateException("disk")),
            )
            put("code.unregistered_renders_internal", Fault(FaultKind.CONFLICT, ErrorCode.of("never_declared", "not declared")))
            put("code.cross_site", Fault.forbidden(RainWebErrorCodes.CROSS_SITE))
        }

    private fun document(): String =
        buildString {
            append("{\n  \"format\": ").append(ProblemFormat.VERSION).append(",\n  \"refusals\": {\n")
            cases().entries.forEachIndexed { index, (name, fault) ->
                val rendered = renderer.render(fault)
                append("    ").append(json.writeValueAsString(name)).append(": {\n")
                append("      \"status\": ").append(rendered.status).append(",\n")
                append("      \"headers\": ").append(json.writeValueAsString(rendered.headers.toSortedMap())).append(",\n")
                append("      \"body\": ").append(String(rendered.body, Charsets.UTF_8)).append("\n")
                append("    }").append(if (index < cases().size - 1) ",\n" else "\n")
            }
            append("  }\n}\n")
        }

    @Test
    fun `every rendered refusal matches the golden file`() {
        val actual = document()
        if (System.getProperty(WRITE) == "true") {
            Files.writeString(GOLDEN, actual)
        }

        assertThat(actual).isEqualTo(Files.readString(GOLDEN))
    }

    private companion object {
        const val WRITE = "rain.writeGolden"
        val GOLDEN: Path = Path.of("src/test/resources/problem-format-v1.golden.json")
    }
}
