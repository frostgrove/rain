package com.gd.rain.test

import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.reflect.KClass

/**
 * A real Spring Boot application a test starts: every listener, environment post-processor and auto-configuration takes
 * part, as it does in a deployment.
 *
 * What the process states — its stage, its roles or its command, where its dependencies are — is passed as command-line
 * arguments (`--name=value`), which outrank the application's own configuration files the way a deployment's
 * environment does, and which a command reads as its arguments. Nothing is stated for the test: a context started here
 * has exactly the stage, roles and properties the test names.
 */
public class RainApplication private constructor(
    public val context: ConfigurableApplicationContext,
) : AutoCloseable {
    /** The port the application's web server listens on, as the server published it (`local.server.port`). */
    public val port: Int
        get() =
            checkNotNull(context.environment.getProperty(LOCAL_SERVER_PORT, Int::class.javaObjectType)) {
                "the application runs no web server, so it listens on no port"
            }

    /** A client of [port], created on first use. */
    public val http: ApplicationHttp by lazy { ApplicationHttp(port) }

    public fun <T : Any> bean(type: KClass<T>): T = context.getBean(type.java)

    /** Closes the context and answers the exit code its `ExitCodeGenerator`s — a command's, for one — agree on. */
    public fun exit(): Int = SpringApplication.exit(context)

    override fun close() {
        context.close()
    }

    public companion object {
        /** The property a started web server publishes its port under. */
        public const val LOCAL_SERVER_PORT: String = "local.server.port"

        private val PROPERTY = Regex("^[A-Za-z0-9_.\\[\\]-]+=.*$", RegexOption.DOT_MATCHES_ALL)

        /**
         * Starts [sources] as one application of [web] type with [properties], each `name=value`, and registers [singletons]
         * by name before any bean is created — a test's `Clock`, for instance.
         */
        public fun start(
            sources: List<Class<*>>,
            web: WebApplicationType,
            properties: List<String>,
            singletons: Map<String, Any> = emptyMap(),
        ): RainApplication {
            require(sources.isNotEmpty()) { "an application is started from at least one source" }
            properties.filterNot(PROPERTY::matches).takeIf { it.isNotEmpty() }?.let { malformed ->
                throw IllegalArgumentException("a property is stated as name=value, got ${malformed.joinToString(", ") { "\"$it\"" }}")
            }
            val builder =
                SpringApplicationBuilder(*sources.toTypedArray())
                    .web(web)
                    .logStartupInfo(false)
            if (singletons.isNotEmpty()) {
                builder.initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                        singletons.forEach { (name, bean) -> context.beanFactory.registerSingleton(name, bean) }
                    },
                )
            }
            return RainApplication(builder.run(*properties.map { "--$it" }.toTypedArray()))
        }
    }
}

/**
 * A plain HTTP client of one started application, with no cookie jar and no redirects followed: every header a request
 * carries is one the test wrote. A request with a body and no `Content-Type` of its own is sent as `application/json`.
 */
public class ApplicationHttp(
    public val port: Int,
) {
    public val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    public fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    public fun send(
        method: String,
        path: String,
        body: String? = null,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(uri(path))
                .timeout(REQUEST_TIMEOUT)
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null && headers.none { it.first.equals(CONTENT_TYPE, ignoreCase = true) }) request.header(CONTENT_TYPE, JSON)
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    public fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> = send("GET", path, null, *headers)

    public companion object {
        public val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        public val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)

        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON = "application/json"
    }
}
