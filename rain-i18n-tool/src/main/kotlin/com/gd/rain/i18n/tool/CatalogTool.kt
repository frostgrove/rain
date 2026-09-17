package com.gd.rain.i18n.tool

import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogSourceCodec
import com.gd.rain.i18n.CatalogSourceDecoding
import com.gd.rain.i18n.SourceCodecProblem

/** Deterministic offline source validation and artifact compilation facade for Gradle tasks and CLIs. */
public class CatalogTool(
    private val sourceCodec: CatalogSourceCodec = CatalogSourceCodec(),
    private val artifactCodec: CatalogArtifactCodec = CatalogArtifactCodec(),
) {
    public fun check(source: ByteArray): CatalogToolCheck =
        when (val decoded = sourceCodec.decode(source)) {
            is CatalogSourceDecoding.Decoded -> {
                when (val compilation = CatalogCompiler.compile(decoded.source)) {
                    is CatalogCompilation.Compiled -> {
                        CatalogToolCheck.Valid
                    }

                    is CatalogCompilation.Refused -> {
                        CatalogToolCheck.Invalid(
                            compilation.problems.map { SourceCodecProblem(it.path, it.message) },
                        )
                    }
                }
            }

            is CatalogSourceDecoding.Refused -> {
                CatalogToolCheck.Invalid(decoded.problems)
            }
        }

    public fun compile(source: ByteArray): CatalogToolCompilation =
        when (val decoded = sourceCodec.decode(source)) {
            is CatalogSourceDecoding.Decoded -> {
                when (val compilation = CatalogCompiler.compile(decoded.source)) {
                    is CatalogCompilation.Compiled -> {
                        CatalogToolCompilation.Compiled(artifactCodec.encode(decoded.source))
                    }

                    is CatalogCompilation.Refused -> {
                        CatalogToolCompilation.Invalid(
                            compilation.problems.map { SourceCodecProblem(it.path, it.message) },
                        )
                    }
                }
            }

            is CatalogSourceDecoding.Refused -> {
                CatalogToolCompilation.Invalid(decoded.problems)
            }
        }

    /** Generates reflection-free Kotlin binders only after the source compiles as one exact snapshot. */
    public fun generateKotlin(
        source: ByteArray,
        packageName: String,
        fileName: String = "RainI18nContracts.kt",
    ): CatalogToolKotlinGeneration =
        when (val compiled = compileSnapshot(source)) {
            is ToolSnapshotCompilation.Compiled -> {
                CatalogToolKotlinGeneration.Generated(KotlinContractGenerator.generate(compiled.snapshot, packageName, fileName))
            }

            is ToolSnapshotCompilation.Invalid -> {
                CatalogToolKotlinGeneration.Invalid(compiled.problems)
            }
        }

    /** Exports public structural contracts without a browser formatter or private message schema. */
    public fun exportTypeScript(source: ByteArray): CatalogToolTypeScriptExport =
        when (val compiled = compileSnapshot(source)) {
            is ToolSnapshotCompilation.Compiled -> {
                CatalogToolTypeScriptExport.Exported(TypeScriptContractExporter.export(compiled.snapshot))
            }

            is ToolSnapshotCompilation.Invalid -> {
                CatalogToolTypeScriptExport.Invalid(compiled.problems)
            }
        }

    /** Produces a grammar-safe approved pseudo locale from canonical source, then recompiles it. */
    public fun pseudo(
        source: ByteArray,
        profile: PseudoLocaleProfile,
    ): CatalogToolPseudo = PseudoLocalizer(sourceCodec).pseudo(source, profile)

    /** Applies a typed review decision to canonical source without activating a runtime release. */
    public fun review(
        source: ByteArray,
        key: com.gd.rain.i18n.MessageKey,
        locale: com.gd.rain.i18n.LocaleTag,
        action: TranslationReviewAction,
    ): CatalogToolSourceMutation = TranslationAuthoringTool(sourceCodec).review(source, key, locale, action)

    /** Non-pruning structural merge for extracted source and baseline translation work. */
    public fun merge(
        baseline: ByteArray,
        incoming: ByteArray,
    ): CatalogToolMerge = TranslationAuthoringTool(sourceCodec).merge(baseline, incoming)

    private fun compileSnapshot(source: ByteArray): ToolSnapshotCompilation =
        when (val decoded = sourceCodec.decode(source)) {
            is CatalogSourceDecoding.Decoded -> {
                when (val compilation = CatalogCompiler.compile(decoded.source)) {
                    is CatalogCompilation.Compiled -> {
                        ToolSnapshotCompilation.Compiled(compilation.snapshot)
                    }

                    is CatalogCompilation.Refused -> {
                        ToolSnapshotCompilation.Invalid(
                            compilation.problems.map { SourceCodecProblem(it.path, it.message) },
                        )
                    }
                }
            }

            is CatalogSourceDecoding.Refused -> {
                ToolSnapshotCompilation.Invalid(decoded.problems)
            }
        }

    private sealed interface ToolSnapshotCompilation {
        public data class Compiled(
            public val snapshot: com.gd.rain.i18n.CatalogSnapshot,
        ) : ToolSnapshotCompilation

        public data class Invalid(
            public val problems: List<SourceCodecProblem>,
        ) : ToolSnapshotCompilation
    }
}

public sealed interface CatalogToolCheck {
    public data object Valid : CatalogToolCheck

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolCheck
}

public sealed interface CatalogToolCompilation {
    public data class Compiled(
        private val artifact: ByteArray,
    ) : CatalogToolCompilation {
        public fun artifactBytes(): ByteArray = artifact.copyOf()
    }

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolCompilation
}

/** Result of deterministic Kotlin binder generation. */
public sealed interface CatalogToolKotlinGeneration {
    public data class Generated(
        public val source: GeneratedKotlinSource,
    ) : CatalogToolKotlinGeneration

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolKotlinGeneration
}

/** Result of public-only TypeScript contract export. */
public sealed interface CatalogToolTypeScriptExport {
    public data class Exported(
        public val export: TypeScriptExport,
    ) : CatalogToolTypeScriptExport

    public data class Invalid(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogToolTypeScriptExport
}
