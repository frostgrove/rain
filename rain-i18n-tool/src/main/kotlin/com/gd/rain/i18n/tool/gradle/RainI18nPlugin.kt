package com.gd.rain.i18n.tool.gradle

import com.gd.rain.i18n.SourceCodecProblem
import com.gd.rain.i18n.tool.AtomicOutputWriter
import com.gd.rain.i18n.tool.CatalogTool
import com.gd.rain.i18n.tool.CatalogToolCheck
import com.gd.rain.i18n.tool.CatalogToolCompilation
import com.gd.rain.i18n.tool.CatalogToolKotlinGeneration
import com.gd.rain.i18n.tool.CatalogToolTypeScriptExport
import com.gd.rain.i18n.tool.KotlinUsageExtractor
import com.gd.rain.i18n.tool.TypeScriptPublication
import com.gd.rain.i18n.tool.TypeScriptPublicationWrite
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Configuration for the deterministic `com.gd.rain.i18n` Gradle plugin. */
public abstract class RainI18nExtension {
    /** Enables the conventional task graph; disabled projects get no source probing or generated output. */
    public abstract val enabled: Property<Boolean>

    /** One explicit canonical source document; the plugin never discovers catalog fragments. */
    public abstract val source: RegularFileProperty

    /** Canonical compiled runtime artifact. */
    public abstract val artifact: RegularFileProperty

    /** Generated Kotlin package for the magic-first typed binding API. */
    public abstract val kotlinPackage: Property<String>

    /** Generated Kotlin file name. */
    public abstract val kotlinFileName: Property<String>

    /** Exact generated Kotlin source file, overrideable for a non-standard source-set layout. */
    public abstract val generatedKotlin: RegularFileProperty

    /** Content-addressed public TypeScript contract publication root. */
    public abstract val typeScriptPublication: DirectoryProperty

    /** Kotlin/Java source roots used only by the bounded direct-key usage extractor. */
    public abstract val usageSources: ConfigurableFileCollection

    /** Written manifest of literal direct low-level key construction. */
    public abstract val usageManifest: RegularFileProperty

    /** One K2 semantic-usage manifest per Kotlin compilation; never a shared cross-source-set file. */
    public abstract val semanticUsageDirectory: DirectoryProperty

    /** Refuses unprovable `MessageKey(...)` construction until a caller supplies static generated bindings. */
    public abstract val failOnDynamicUsage: Property<Boolean>
}

/** Registers magic-first task defaults while keeping every source, output and strictness knob explicit. */
public class RainI18nPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("rainI18n", RainI18nExtension::class.java)
        extension.enabled.convention(false)
        extension.source.convention(project.layout.projectDirectory.file("src/main/i18n/catalog.json"))
        extension.artifact.convention(project.layout.buildDirectory.file("generated/rain-i18n/catalog.rain-i18n"))
        extension.kotlinPackage.convention("com.gd.rain.i18n.generated")
        extension.kotlinFileName.convention("RainI18nContracts.kt")
        extension.generatedKotlin.convention(
            project.layout.buildDirectory.file(
                extension.kotlinPackage.zip(extension.kotlinFileName) { packageName, fileName ->
                    "generated/sources/rainI18n/main/kotlin/${packageName.replace('.', '/')}/$fileName"
                },
            ),
        )
        extension.typeScriptPublication.convention(project.layout.buildDirectory.dir("generated/rain-i18n/typescript"))
        extension.usageSources.from(
            project.fileTree("src/main/kotlin") { pattern -> pattern.include("**/*.kt") },
            project.fileTree("src/main/java") { pattern -> pattern.include("**/*.java") },
        )
        extension.usageManifest.convention(project.layout.buildDirectory.file("reports/rain-i18n/usage.json"))
        extension.semanticUsageDirectory.convention(project.layout.buildDirectory.dir("reports/rain-i18n/k2"))
        extension.failOnDynamicUsage.convention(true)

        configureK2SemanticUsage(project, extension)

        val extract =
            project.tasks.register(
                "rainI18nExtract",
                RainI18nExtractTask::class.java,
                Action { task ->
                    task.group = "rain i18n"
                    task.description = "Extracts bounded direct Kotlin/Java MessageKey usage evidence."
                    task.active.set(extension.enabled)
                    task.kotlinSources.from(extension.usageSources)
                    task.manifest.set(extension.usageManifest)
                    task.failOnDynamicUsage.set(extension.failOnDynamicUsage)
                },
            )
        val check =
            project.tasks.register(
                "rainI18nCheck",
                RainI18nCheckTask::class.java,
                Action { task ->
                    task.group = "verification"
                    task.description = "Checks the canonical Rain i18n source without mutating it."
                    task.active.set(extension.enabled)
                    task.source.set(extension.source)
                    task.dependsOn(extract)
                },
            )
        project.tasks.register(
            "rainI18nCompile",
            RainI18nCompileTask::class.java,
            Action { task ->
                task.group = "rain i18n"
                task.description = "Compiles one checked canonical i18n source into a runtime artifact."
                task.active.set(extension.enabled)
                task.source.set(extension.source)
                task.artifact.set(extension.artifact)
                task.dependsOn(check)
            },
        )
        val generateKotlin =
            project.tasks.register(
                "rainI18nGenerateKotlin",
                RainI18nGenerateKotlinTask::class.java,
                Action { task ->
                    task.group = "rain i18n"
                    task.description = "Generates reflection-free typed Kotlin i18n contracts."
                    task.active.set(extension.enabled)
                    task.source.set(extension.source)
                    task.packageName.set(extension.kotlinPackage)
                    task.fileName.set(extension.kotlinFileName)
                    task.output.set(extension.generatedKotlin)
                    task.dependsOn(check)
                },
            )
        project.tasks.register(
            "rainI18nExportTypeScript",
            RainI18nExportTypeScriptTask::class.java,
            Action { task ->
                task.group = "rain i18n"
                task.description = "Publishes a verified public-only TypeScript contract generation."
                task.active.set(extension.enabled)
                task.source.set(extension.source)
                task.publication.set(extension.typeScriptPublication)
                task.dependsOn(check)
            },
        )
        configureGeneratedKotlinMagic(project, extension, generateKotlin)
        project.pluginManager.withPlugin("base") {
            project.tasks.named("check").configure { task -> task.dependsOn(check) }
        }
    }
}

/** Adds the exact generated binder directory to Kotlin/JVM main sources without application code. */
private fun configureGeneratedKotlinMagic(
    project: Project,
    extension: RainI18nExtension,
    generation: TaskProvider<RainI18nGenerateKotlinTask>,
) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val kotlinExtension = checkNotNull(project.extensions.findByName("kotlin")) { "the Kotlin/JVM extension is installed" }
        val sourceSets =
            checkNotNull(
                kotlinExtension.javaClass.methods
                    .firstOrNull { method -> method.name == "getSourceSets" && method.parameterCount == 0 }
                    ?.invoke(kotlinExtension),
            ) { "the Kotlin/JVM extension exposes source sets" }
        val main =
            checkNotNull(
                sourceSets.javaClass.methods
                    .firstOrNull { method -> method.name == "getByName" && method.parameterCount == 1 }
                    ?.invoke(sourceSets, "main"),
            ) { "the Kotlin/JVM extension exposes main sources" }
        val kotlinSources =
            checkNotNull(
                main.javaClass.methods
                    .firstOrNull { method -> method.name == "getKotlin" && method.parameterCount == 0 }
                    ?.invoke(main),
            ) { "the Kotlin/JVM main source set exposes Kotlin sources" }
        checkNotNull(
            kotlinSources.javaClass.methods.firstOrNull { method -> method.name == "srcDir" && method.parameterCount == 1 },
        ) { "the Kotlin/JVM source set accepts an additional source directory" }.invoke(
            kotlinSources,
            extension.generatedKotlin.map { file -> file.asFile.parentFile },
        )
        project.tasks.named("compileKotlin").configure { task -> task.dependsOn(generation) }
    }
}

/**
 * Installs the resolved K2 usage compiler plugin into each Kotlin compilation only while magic is
 * enabled. Each compilation owns an independent manifest, avoiding source-set races and keeping
 * the semantic bridge a tool↔Kotlin pair rather than a context-specific integration module.
 */
private fun configureK2SemanticUsage(
    project: Project,
    extension: RainI18nExtension,
) {
    val compilerPluginPaths = k2CompilerPluginPaths()
    project.tasks.all { task ->
        if (!task.javaClass.name.startsWith("org.jetbrains.kotlin.gradle.tasks.")) return@all
        val compilerOptions =
            task.javaClass.methods
                .firstOrNull { method ->
                    method.name == "getCompilerOptions" && method.parameterCount == 0
                }?.invoke(task)
                ?: return@all
        val freeCompilerArgs =
            compilerOptions.javaClass.methods
                .firstOrNull { method -> method.name == "getFreeCompilerArgs" && method.parameterCount == 0 }
                ?.invoke(compilerOptions)
                ?: return@all
        val manifest = extension.semanticUsageDirectory.file("${task.name}.json")
        val compilerArguments =
            project.providers.provider {
                if (!extension.enabled.getOrElse(false)) {
                    emptyList()
                } else {
                    k2CompilerArguments(
                        compilerPluginPaths = compilerPluginPaths,
                        manifest = manifest.get().asFile.toPath(),
                        failOnDynamicUsage = extension.failOnDynamicUsage.getOrElse(true),
                    )
                }
            }
        checkNotNull(
            freeCompilerArgs.javaClass.methods.firstOrNull { method ->
                method.name == "addAll" && method.parameterCount == 1 &&
                    org.gradle.api.provider.Provider::class.java.isAssignableFrom(method.parameterTypes.single())
            },
        ) { "a Kotlin compiler free-arguments property supports provider-backed arguments" }.invoke(
            freeCompilerArgs,
            compilerArguments,
        )
        task.doFirst("create the semantic Rain i18n usage manifest directory") {
            if (extension.enabled.getOrElse(false)) {
                Files.createDirectories(
                    manifest
                        .get()
                        .asFile
                        .toPath()
                        .parent,
                )
            }
        }
    }
}

private fun k2CompilerArguments(
    compilerPluginPaths: List<Path>,
    manifest: Path,
    failOnDynamicUsage: Boolean,
): List<String> =
    buildList {
        compilerPluginPaths.forEach { path -> add("-Xplugin=${path.toAbsolutePath().normalize()}") }
        add("-P")
        add("plugin:com.gd.rain.i18n.k2-usage:manifest=${manifest.toAbsolutePath().normalize()}")
        add("-P")
        add("plugin:com.gd.rain.i18n.k2-usage:fail-on-dynamic=$failOnDynamicUsage")
    }

private fun k2CompilerPluginPaths(): List<Path> {
    val codeSource =
        Path
            .of(
                RainI18nPlugin::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).toAbsolutePath()
            .normalize()
    if (Files.isRegularFile(codeSource)) return listOf(codeSource)

    val resource =
        checkNotNull(
            RainI18nPlugin::class.java.classLoader.getResource(
                "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
            ),
        ) { "the K2 compiler-plugin registrar service is packaged" }
    require(resource.protocol == "file") { "a directory-backed K2 compiler plugin exposes a file registrar resource" }
    var resourceRoot = Path.of(URI(resource.toString()))
    repeat(3) { resourceRoot = checkNotNull(resourceRoot.parent) { "the registrar service has a resource root" } }
    return listOf(codeSource, resourceRoot.toAbsolutePath().normalize()).distinct()
}

/** Shared enabled-state contract: a disabled optional plugin performs no I/O. */
@DisableCachingByDefault(because = "the optional task set favors explicit verified writes over remote build-cache reuse")
public abstract class RainI18nTask : DefaultTask() {
    @get:Input
    public abstract val active: Property<Boolean>

    init {
        onlyIf("rain.i18n is enabled") { active.getOrElse(false) }
    }
}

/** Strict read-only canonical source check. */
@DisableCachingByDefault(because = "a check task deliberately has no output")
public abstract class RainI18nCheckTask : RainI18nTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val source: RegularFileProperty

    @TaskAction
    public fun checkCatalog(): Unit =
        when (val checked = CatalogTool().check(source.get().asFile.readBytes())) {
            CatalogToolCheck.Valid -> logger.lifecycle("Rain i18n source is valid: ${source.get().asFile}")
            is CatalogToolCheck.Invalid -> throw GradleException(problemSummary(checked.problems))
        }
}

/** Compiles a fully validated source document through an atomic same-directory replacement. */
@DisableCachingByDefault(because = "the task uses an explicit atomic local output transition")
public abstract class RainI18nCompileTask : RainI18nTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val source: RegularFileProperty

    @get:OutputFile
    public abstract val artifact: RegularFileProperty

    @TaskAction
    public fun compileCatalog(): Unit =
        when (val compilation = CatalogTool().compile(source.get().asFile.readBytes())) {
            is CatalogToolCompilation.Compiled -> AtomicOutputWriter().replace(artifact.get().asFile.toPath(), compilation.artifactBytes())
            is CatalogToolCompilation.Invalid -> throw GradleException(problemSummary(compilation.problems))
        }
}

/** Generates one exact Kotlin binder file after the source's compiler contract succeeds. */
@DisableCachingByDefault(because = "the task uses an explicit atomic local output transition")
public abstract class RainI18nGenerateKotlinTask : RainI18nTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val source: RegularFileProperty

    @get:Input
    public abstract val packageName: Property<String>

    @get:Input
    public abstract val fileName: Property<String>

    @get:OutputFile
    public abstract val output: RegularFileProperty

    @TaskAction
    public fun generate(): Unit =
        when (val generated = CatalogTool().generateKotlin(source.get().asFile.readBytes(), packageName.get(), fileName.get())) {
            is CatalogToolKotlinGeneration.Generated -> {
                AtomicOutputWriter().replace(
                    output.get().asFile.toPath(),
                    generated.source.content.toByteArray(Charsets.UTF_8),
                )
            }

            is CatalogToolKotlinGeneration.Invalid -> {
                throw GradleException(problemSummary(generated.problems))
            }
        }
}

/** Publishes public TypeScript declarations only through the verified content-addressed publisher. */
@DisableCachingByDefault(because = "publication holds a local descriptor lock and manages immutable generations")
public abstract class RainI18nExportTypeScriptTask : RainI18nTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val source: RegularFileProperty

    @get:OutputDirectory
    public abstract val publication: DirectoryProperty

    @TaskAction
    public fun export(): Unit =
        when (val exported = CatalogTool().exportTypeScript(source.get().asFile.readBytes())) {
            is CatalogToolTypeScriptExport.Exported -> {
                when (val written = TypeScriptPublication().publish(publication.get().asFile.toPath(), exported.export)) {
                    is TypeScriptPublicationWrite.Published -> {
                        logger.lifecycle(
                            "Rain i18n TypeScript generation: ${written.result.generation.hex}",
                        )
                    }

                    is TypeScriptPublicationWrite.Refused -> {
                        throw GradleException(
                            "Rain i18n TypeScript publication refused: ${written.reason.name}",
                        )
                    }
                }
            }

            is CatalogToolTypeScriptExport.Invalid -> {
                throw GradleException(problemSummary(exported.problems))
            }
        }
}

/** Emits direct literal Kotlin/Java key evidence and refuses dynamic low-level construction by default. */
@DisableCachingByDefault(because = "the manifest is evidence for the exact local source set")
public abstract class RainI18nExtractTask : RainI18nTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val kotlinSources: ConfigurableFileCollection

    @get:Input
    public abstract val failOnDynamicUsage: Property<Boolean>

    @get:OutputFile
    public abstract val manifest: RegularFileProperty

    @TaskAction
    public fun extract() {
        val files =
            kotlinSources.files
                .filter { file ->
                    file.isFile && (file.name.endsWith(".kt") || file.name.endsWith(".java"))
                }.map { file -> file.toPath() }
        val extracted = KotlinUsageExtractor().extract(files)
        AtomicOutputWriter().replace(manifest.get().asFile.toPath(), usageManifest(extracted).toByteArray(Charsets.UTF_8))
        if (failOnDynamicUsage.get() && extracted.dynamicUsages.isNotEmpty()) {
            throw GradleException("Rain i18n usage is incomplete: ${extracted.dynamicUsages.size} dynamic MessageKey constructor call(s)")
        }
    }
}

private fun problemSummary(problems: List<SourceCodecProblem>): String =
    problems.take(20).joinToString(prefix = "Rain i18n source is invalid: ", separator = "; ") { problem ->
        "${problem.path}: ${problem.message}"
    }

private fun usageManifest(manifest: com.gd.rain.i18n.tool.KotlinUsageManifest): String =
    buildString {
        append("{\"schema\":\"rain.i18n.kotlin-usage/v1\",\"keys\":[")
        manifest.keys.forEachIndexed { index, key ->
            if (index > 0) append(',')
            append(json(key.value))
        }
        append("],\"dynamic\":[")
        manifest.dynamicUsages.forEachIndexed { index, usage ->
            if (index > 0) append(',')
            append("{\"source\":")
                .append(json(usage.source))
                .append(",\"line\":")
                .append(usage.line)
                .append('}')
        }
        append("]}\n")
    }

private fun json(value: String): String =
    buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u").append(character.code.toString(16).padStart(4, '0')) else append(character)
            }
        }
        append('"')
    }
