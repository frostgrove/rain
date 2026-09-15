import org.jooq.meta.jaxb.Configuration as JooqCodegen
import org.jooq.meta.jaxb.Database as JooqDatabase
import org.jooq.meta.jaxb.Generate as JooqGenerate
import org.jooq.meta.jaxb.Generator as JooqGenerator
import org.jooq.meta.jaxb.Jdbc as JooqJdbc
import org.jooq.meta.jaxb.Target as JooqTarget

/*
 * A rain module that owns database tables.
 *
 * The module's migrations live in `src/main/resources/db/rain/<module>` and are applied to schema
 * `rain_<module>` only. `generateJooq` applies exactly those migrations to an empty PostgreSQL and
 * generates `com.gd.rain.<module>.jooq` from the result, so generated code and migrations cannot drift
 * and a module can never compile against another module's tables. `writeSchemaDescriptor` produces the
 * `META-INF/rain/schemas/<module>.properties` file the runtime migration strategy reads.
 *
 * The generator reads from a throwaway Testcontainers PostgreSQL by default; `JOOQ_CODEGEN_SERVER_URL`
 * (plus `_USER`, `_PASSWORD`) points it at a server instead, where it creates, migrates and drops a
 * scratch database named after the module.
 */
plugins {
    id("rain.spring-module")
}

val libs = the<VersionCatalogsExtension>().named("libs")

interface RainSchemaExtension {
    /** Lower-case kebab module name; decides the schema, the migration location and the generated package. */
    val module: Property<String>

    /** Tables in the module schema that no typed query may name (e.g. a library's own bookkeeping). */
    val excludedTables: ListProperty<String>
}

val rainSchema = extensions.create<RainSchemaExtension>("rainSchema")
rainSchema.excludedTables.convention(emptyList())

dependencies {
    "api"(libs.findLibrary("jooq").get())
}

val jooqOutput: Provider<Directory> = layout.buildDirectory.dir("generated/sources/jooq")
val descriptorOutput: Provider<Directory> = layout.buildDirectory.dir("generated/resources/rain-schema")

fun schemaOf(module: String): String = "rain_" + module.replace('-', '_')

fun packageOf(module: String): String = "com.gd.rain." + module.replace("-", "") + ".jooq"

val postgresImage = libs.findVersion("postgresImage").get().requiredVersion

val generateJooq =
    tasks.register("generateJooq") {
        description = "Generates the module's jOOQ schema from its own migrations, applied to an empty PostgreSQL."
        group = LifecycleBasePlugin.BUILD_GROUP

        val module = rainSchema.module
        val excluded = rainSchema.excludedTables
        val migrations = module.map { layout.projectDirectory.dir("src/main/resources/db/rain/$it") }
        val out = jooqOutput
        val server = providers.environmentVariable("JOOQ_CODEGEN_SERVER_URL")
        val serverUser = providers.environmentVariable("JOOQ_CODEGEN_SERVER_USER")
        val serverPassword = providers.environmentVariable("JOOQ_CODEGEN_SERVER_PASSWORD")

        inputs.dir(migrations).withPropertyName("migrations")
        inputs.property("module", module)
        inputs.property("excluded", excluded)
        inputs.property("image", postgresImage)
        inputs.property("server", server.orElse(""))
        outputs.dir(out).withPropertyName("generated")

        doLast {
            val name = module.get()
            val schema = schemaOf(name)
            val directory = out.get().asFile
            directory.deleteRecursively()

            val scratch = "rain_jooq_" + name.replace('-', '_')
            val (url, user, password, close) =
                if (!server.isPresent) {
                    val container = org.testcontainers.postgresql.PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse(postgresImage))
                    container.start()
                    Quad(container.jdbcUrl, container.username, container.password) { container.stop() }
                } else {
                    val base = server.get().trimEnd('/')
                    val adminUser = serverUser.orNull ?: error("JOOQ_CODEGEN_SERVER_URL is set but JOOQ_CODEGEN_SERVER_USER is not")
                    val adminPassword = serverPassword.orNull ?: error("JOOQ_CODEGEN_SERVER_URL is set but JOOQ_CODEGEN_SERVER_PASSWORD is not")
                    fun administer(statement: String) = org.jooq.impl.DSL.using(base, adminUser, adminPassword).execute(statement)
                    administer("DROP DATABASE IF EXISTS \"$scratch\" WITH (FORCE)")
                    administer("CREATE DATABASE \"$scratch\"")
                    Quad(base.substringBeforeLast('/') + "/" + scratch, adminUser, adminPassword) {
                        administer("DROP DATABASE IF EXISTS \"$scratch\" WITH (FORCE)")
                    }
                }
            try {
                org.flywaydb.core.Flyway
                    .configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .table("flyway_schema_history")
                    .locations("filesystem:" + migrations.get().asFile.path)
                    .baselineOnMigrate(false)
                    .validateOnMigrate(true)
                    .failOnMissingLocations(true)
                    .load()
                    .migrate()

                org.jooq.codegen.GenerationTool.generate(
                    JooqCodegen()
                        .withJdbc(JooqJdbc().withDriver("org.postgresql.Driver").withUrl(url).withUser(user).withPassword(password))
                        .withGenerator(
                            JooqGenerator()
                                .withName("org.jooq.codegen.JavaGenerator")
                                .withDatabase(
                                    JooqDatabase()
                                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                                        .withInputSchema(schema)
                                        .withOutputSchemaToDefault(false)
                                        .withExcludes((listOf("flyway_schema_history") + excluded.get()).joinToString("|")),
                                ).withGenerate(
                                    JooqGenerate()
                                        .withJavaTimeTypes(true)
                                        .withDeprecated(false)
                                        .withRecords(true)
                                        .withPojos(false)
                                        .withDaos(false)
                                        .withSpringAnnotations(false)
                                        .withJpaAnnotations(false)
                                        .withValidationAnnotations(false)
                                        .withFluentSetters(false)
                                        .withImplicitJoinPathsToMany(false),
                                ).withTarget(JooqTarget().withPackageName(packageOf(name)).withDirectory(directory.path)),
                        ),
                )
            } finally {
                close()
            }
        }
    }

val writeSchemaDescriptor =
    tasks.register("writeSchemaDescriptor") {
        description = "Writes META-INF/rain/schemas/<module>.properties for the runtime migration strategy."
        group = LifecycleBasePlugin.BUILD_GROUP
        val module = rainSchema.module
        val out = descriptorOutput
        inputs.property("module", module)
        outputs.dir(out)
        doLast {
            val name = module.get()
            val file = out.get().file("META-INF/rain/schemas/$name.properties").asFile
            file.parentFile.mkdirs()
            file.writeText("module=$name\nschema=${schemaOf(name)}\nlocation=classpath:db/rain/$name\n")
        }
    }

the<SourceSetContainer>()["main"].java.srcDir(generateJooq)
the<SourceSetContainer>()["main"].resources.srcDir(writeSchemaDescriptor)

/** Four values and a closer; destructured above so both sources hand back the same shape. */
data class Quad(
    val url: String,
    val user: String,
    val password: String,
    val close: () -> Unit,
)
