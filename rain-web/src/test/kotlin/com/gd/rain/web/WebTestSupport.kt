package com.gd.rain.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.web.autoconfigure.RainProbeAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebFilterAutoConfiguration
import com.gd.rain.web.problem.ErrorCodeRegistrar
import com.gd.rain.web.problem.ProblemRenderer
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.problem.RainWebErrorCodes
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.reflect.KClass

/** The properties every rain web context in these tests starts from. */
val BASE_PROPERTIES: Array<String> =
    arrayOf(
        "rain.runtime.roles=api",
        "rain.deployment.stage=test",
        "rain.web.body-limit=1KB",
        "rain.web.request-budget=30s",
        "rain.web.client-address=direct",
        "server.forward-headers-strategy=none",
    )

/** A servlet context with Spring MVC, rain's runtime, health and web auto-configurations — no embedded server. */
fun webRunner(): WebApplicationContextRunner =
    WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PropertyPlaceholderAutoConfiguration::class.java,
                JacksonAutoConfiguration::class.java,
                HttpMessageConvertersAutoConfiguration::class.java,
                DispatcherServletAutoConfiguration::class.java,
                WebMvcAutoConfiguration::class.java,
                ErrorMvcAutoConfiguration::class.java,
                RainRuntimeAutoConfiguration::class.java,
                RainHealthAutoConfiguration::class.java,
                RainWebErrorAutoConfiguration::class.java,
                RainWebFilterAutoConfiguration::class.java,
                RainProbeAutoConfiguration::class.java,
            ),
        ).withPropertyValues(*BASE_PROPERTIES)

/** MockMvc over [context] with every registered filter applied in its registration order, as the container would. */
fun mockMvcOf(context: WebApplicationContext): MockMvc {
    val builder = MockMvcBuilders.webAppContextSetup(context)
    context
        .getBeansOfType(FilterRegistrationBean::class.java)
        .values
        .sortedBy { it.order }
        .forEach { builder.addFilter<DefaultMockMvcBuilder>(checkNotNull(it.filter)) }
    return builder.build()
}

/** A writer over the codes rain ships, for filters exercised without a context. */
fun problemWriter(): ProblemWriter = ProblemWriter(ProblemRenderer(ErrorCodeRegistrar.register(listOf(RainErrorCodes, RainWebErrorCodes))))

private val READER: JsonMapper = JsonMapper.builder().build()

/** The problem body of a response, after checking it is one. */
fun MockHttpServletResponse.problem(): JsonNode {
    assertThat(contentType).describedAs("content type of a refusal").isEqualTo("application/problem+json")
    return READER.readTree(contentAsByteArray)
}

fun MockHttpServletResponse.problemCode(): String = problem()["code"].asString()

fun readJson(body: String): JsonNode = READER.readTree(body)

/** A real `SpringApplication` start on a random port, with every auto-configuration on the test classpath. */
fun startServer(
    source: KClass<*>,
    vararg properties: String,
): ConfigurableApplicationContext =
    SpringApplicationBuilder(source.java)
        .web(WebApplicationType.SERVLET)
        .logStartupInfo(false)
        .properties("spring.application.name=sample", "server.port=0", *BASE_PROPERTIES, *properties)
        .run()

fun ConfigurableApplicationContext.port(): Int = checkNotNull((this as WebServerApplicationContext).webServer).port

fun get(
    port: Int,
    path: String,
    vararg headers: Pair<String, String>,
): HttpResponse<String> =
    HttpClient.newHttpClient().use { client ->
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(20)).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

/** The log of one class as a list, so a test asserts what a line says. */
class Transcript private constructor(
    private val logger: Logger,
    private val appender: ListAppender<ILoggingEvent>,
    private val level: Level?,
    private val additive: Boolean,
) : AutoCloseable {
    val lines: List<ILoggingEvent> get() = appender.list.toList()

    fun only(message: String): ILoggingEvent {
        val found = lines.filter { it.message == message }
        check(found.size == 1) { "the log holds ${found.size} \"$message\" lines, want exactly one:\n${written()}" }
        return found.single()
    }

    fun attributes(event: ILoggingEvent): Map<String, String?> = event.keyValuePairs.orEmpty().associate { it.key to it.value?.toString() }

    fun written(): String = lines.joinToString("\n") { "${it.level} ${it.message} ${attributes(it)}" }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = level
        logger.isAdditive = additive
    }

    companion object {
        fun of(type: KClass<*>): Transcript {
            val logger = LoggerFactory.getLogger(type.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val transcript = Transcript(logger, appender, logger.level, logger.isAdditive)
            logger.isAdditive = false
            logger.level = Level.TRACE
            logger.addAppender(appender)
            return transcript
        }
    }
}
