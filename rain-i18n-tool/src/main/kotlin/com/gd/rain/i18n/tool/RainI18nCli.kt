package com.gd.rain.i18n.tool

import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.SourceCodecProblem
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Small deterministic command-line surface over [CatalogTool].
 *
 * This is deliberately a thin transport: source validation, compilation, review and publication
 * remain in the low-level SDK and all output mutations use [AtomicOutputWriter].
 */
public object RainI18nCli {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        exitProcess(execute(arguments.toList()))
    }

    /** Executes one bounded command and returns a conventional process status without terminating the JVM. */
    public fun execute(
        arguments: List<String>,
        output: Appendable = System.out,
        errors: Appendable = System.err,
    ): Int =
        try {
            require(arguments.size in 1..7) { usage() }
            require(
                arguments.all { argument ->
                    argument.length <= MAX_ARGUMENT_CHARS
                },
            ) { "a command argument exceeds $MAX_ARGUMENT_CHARS characters" }
            when (arguments.first()) {
                "check" -> {
                    check(arguments, output)
                }

                "compile" -> {
                    compile(arguments, output)
                }

                "generate-kotlin" -> {
                    generateKotlin(arguments, output)
                }

                "export-typescript" -> {
                    exportTypeScript(arguments, output)
                }

                "review" -> {
                    review(arguments, output)
                }

                "merge" -> {
                    merge(arguments, output)
                }

                "pseudo" -> {
                    pseudo(arguments, output)
                }

                "help", "--help", "-h" -> {
                    require(arguments.size == 1) { usage() }
                    output.appendLine(usage())
                    0
                }

                else -> {
                    throw IllegalArgumentException(usage())
                }
            }
        } catch (failure: IllegalArgumentException) {
            errors.appendLine(failure.message ?: "rain-i18n-tool refused the command")
            2
        } catch (failure: IllegalStateException) {
            errors.appendLine(failure.message ?: "rain-i18n-tool could not complete the command")
            3
        }

    private fun check(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 2) { usage() }
        return when (val result = CatalogTool().check(readSource(arguments[1]))) {
            CatalogToolCheck.Valid -> {
                output.appendLine("valid")
                0
            }

            is CatalogToolCheck.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun compile(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 3) { usage() }
        return when (val result = CatalogTool().compile(readSource(arguments[1]))) {
            is CatalogToolCompilation.Compiled -> {
                AtomicOutputWriter().replace(outputPath(arguments[2]), result.artifactBytes())
                output.appendLine("compiled")
                0
            }

            is CatalogToolCompilation.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun generateKotlin(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 4) { usage() }
        return when (val result = CatalogTool().generateKotlin(readSource(arguments[1]), arguments[2])) {
            is CatalogToolKotlinGeneration.Generated -> {
                AtomicOutputWriter().replace(outputPath(arguments[3]), result.source.content.toByteArray(Charsets.UTF_8))
                output.appendLine(result.source.relativePath)
                0
            }

            is CatalogToolKotlinGeneration.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun exportTypeScript(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 3) { usage() }
        return when (val result = CatalogTool().exportTypeScript(readSource(arguments[1]))) {
            is CatalogToolTypeScriptExport.Exported -> {
                when (val written = TypeScriptPublication().publish(outputPath(arguments[2]), result.export)) {
                    is TypeScriptPublicationWrite.Published -> {
                        output.appendLine(written.result.generation.hex)
                        0
                    }

                    is TypeScriptPublicationWrite.Refused -> {
                        throw IllegalStateException("TypeScript publication refused: ${written.reason.name}")
                    }
                }
            }

            is CatalogToolTypeScriptExport.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun review(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 6) { usage() }
        val action =
            try {
                TranslationReviewAction.valueOf(arguments[4].uppercase())
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("review action is approve or reject")
            }
        return when (
            val result =
                CatalogTool().review(
                    readSource(arguments[1]),
                    MessageKey.parse(arguments[2]),
                    LocaleTag.parse(arguments[3]),
                    action,
                )
        ) {
            is CatalogToolSourceMutation.Mutated -> {
                AtomicOutputWriter().replace(outputPath(arguments[5]), result.sourceBytes())
                output.appendLine("reviewed")
                0
            }

            is CatalogToolSourceMutation.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun merge(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 4) { usage() }
        return when (val result = CatalogTool().merge(readSource(arguments[1]), readSource(arguments[2]))) {
            is CatalogToolMerge.Merged -> {
                AtomicOutputWriter().replace(outputPath(arguments[3]), result.sourceBytes())
                output.appendLine("merged carried=${result.carriedTranslations} retained-obsolete=${result.retainedObsoleteKeys.size}")
                0
            }

            is CatalogToolMerge.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun pseudo(
        arguments: List<String>,
        output: Appendable,
    ): Int {
        require(arguments.size == 4) { usage() }
        val profile =
            try {
                PseudoLocaleProfile.valueOf(arguments[2].uppercase())
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("pseudo profile is accent or rtl")
            }
        return when (val result = CatalogTool().pseudo(readSource(arguments[1]), profile)) {
            is CatalogToolPseudo.Pseudoed -> {
                AtomicOutputWriter().replace(outputPath(arguments[3]), result.sourceBytes())
                output.appendLine(profile.locale.value)
                0
            }

            is CatalogToolPseudo.Invalid -> {
                problems(result.problems)
            }
        }
    }

    private fun problems(problems: List<SourceCodecProblem>): Int {
        val body = problems.take(MAX_PROBLEMS).joinToString("; ") { problem -> "${problem.path}: ${problem.message}" }
        throw IllegalArgumentException("catalog is invalid: $body")
    }

    private fun readSource(raw: String): ByteArray {
        val source = outputPath(raw)
        require(Files.isRegularFile(source, NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) { "source is not a regular non-symlink file" }
        require(Files.size(source) <= MAX_SOURCE_BYTES) { "source exceeds $MAX_SOURCE_BYTES bytes" }
        return Files.readAllBytes(source)
    }

    private fun outputPath(raw: String): Path {
        require(raw.isNotBlank()) { "a path is not blank" }
        return Path.of(raw).toAbsolutePath().normalize()
    }

    private fun usage(): String =
        "usage: rain-i18n-tool check <source> | compile <source> <artifact> | generate-kotlin <source> <package> <output> | " +
            "export-typescript <source> <directory> | review <source> <key> <locale> <approve|reject> <output> | " +
            "merge <baseline> <incoming> <output> | pseudo <source> <accent|rtl> <output>"

    private const val MAX_ARGUMENT_CHARS: Int = 4 * 1024
    private const val MAX_SOURCE_BYTES: Long = 16L * 1024L * 1024L
    private const val MAX_PROBLEMS: Int = 20
}
