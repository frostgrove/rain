package com.gd.rain.i18n.tool.gradle

import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSourceCodec
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

class RainI18nPluginTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `plugin exposes disabled magic defaults and the complete deterministic task surface`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("base")
        project.pluginManager.apply(RainI18nPlugin::class.java)

        val extension = project.extensions.getByType(RainI18nExtension::class.java)

        assertThat(extension.enabled.get()).isFalse()
        assertThat(
            extension.source
                .get()
                .asFile.invariantSeparatorsPath,
        ).endsWith("src/main/i18n/catalog.json")
        assertThat(extension.kotlinPackage.get()).isEqualTo("com.gd.rain.i18n.generated")
        assertThat(project.tasks.names)
            .contains(
                "rainI18nCheck",
                "rainI18nExtract",
                "rainI18nCompile",
                "rainI18nGenerateKotlin",
                "rainI18nExportTypeScript",
            )
        val check = project.tasks.getByName("check")
        assertThat(check.taskDependencies.getDependencies(check)).contains(project.tasks.getByName("rainI18nCheck"))
    }

    @Test
    fun `enabled plugin compiles and generates every public artifact through its task graph`() {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'fixture'\n")
        Files.writeString(
            directory.resolve("build.gradle"),
            """
            plugins {
              id 'base'
              id 'com.gd.rain.i18n'
            }
            rainI18n {
              enabled.set(true)
              source.set(layout.projectDirectory.file('catalog.json'))
              kotlinPackage.set('example.i18n')
            }
            """.trimIndent(),
        )
        Files.write(directory.resolve("catalog.json"), source())
        Files.createDirectories(directory.resolve("src/main/java/example"))
        Files.writeString(
            directory.resolve("src/main/java/example/Usage.java"),
            """
            package example;
            class Usage {
              Object key = new MessageKey("plugin", "title");
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments("rainI18nCompile", "rainI18nGenerateKotlin", "rainI18nExportTypeScript", "--stacktrace")
                .build()

        assertThat(result.output).contains("BUILD SUCCESSFUL")
        assertThat(directory.resolve("build/generated/rain-i18n/catalog.rain-i18n")).exists()
        assertThat(directory.resolve("build/generated/sources/rainI18n/main/kotlin/example/i18n/RainI18nContracts.kt")).exists()
        assertThat(directory.resolve("build/generated/rain-i18n/typescript/current.json")).exists()
        assertThat(directory.resolve("build/reports/rain-i18n/usage.json")).exists()
        assertThat(directory.resolve("build/reports/rain-i18n/usage.json").toFile().readText()).contains("plugin.title")
    }

    @Test
    fun `enabled plugin installs the K2 semantic extractor into Kotlin compilation`() {
        val i18nClasses =
            Path.of(
                MessageKey::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'fixture'\n")
        Files.writeString(
            directory.resolve("build.gradle"),
            """
            plugins {
              id 'org.jetbrains.kotlin.jvm' version '2.4.20'
              id 'com.gd.rain.i18n'
            }
            repositories { mavenCentral() }
            dependencies { implementation files('$i18nClasses') }
            rainI18n {
              enabled.set(true)
              source.set(layout.projectDirectory.file('catalog.json'))
            }
            """.trimIndent(),
        )
        Files.write(directory.resolve("catalog.json"), source())
        Files.createDirectories(directory.resolve("src/main/kotlin/example"))
        Files.writeString(
            directory.resolve("src/main/kotlin/example/Usage.kt"),
            """
            package example

            import com.gd.rain.i18n.CatalogSnapshot
            import com.gd.rain.i18n.MessageKey
            import com.gd.rain.i18n.generated.RainI18nContracts

            val key = MessageKey("orders", "accepted")

            fun generatedBinding(snapshot: CatalogSnapshot) = RainI18nContracts.message1(snapshot)
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments("compileKotlin", "--stacktrace")
                .build()

        assertThat(result.output).contains("BUILD SUCCESSFUL")
        assertThat(
            directory.resolve("build/generated/sources/rainI18n/main/kotlin/com/gd/rain/i18n/generated/RainI18nContracts.kt"),
        ).exists()
        assertThat(directory.resolve("build/reports/rain-i18n/k2/compileKotlin.json").toFile().readText())
            .contains("\"complete\":true")
            .contains("orders.accepted", "plugin.title")
    }

    private fun source(): ByteArray {
        val en = LocaleTag.parse("en")
        return CatalogSourceCodec().encode(
            CatalogSpec(
                CatalogIdentity("plugin", icuClDrTzdbIdentity = "icu4j-78.3"),
                en,
                LocalePolicy(setOf(en), en),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("plugin", "title"), 1, "Title", "A title", public = true)),
            ),
        )
    }
}
