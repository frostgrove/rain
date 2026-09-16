package com.gd.rain.sample.stand

import com.gd.rain.access.SurfaceExemption
import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.test.ApplicationHttp
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import com.gd.rain.web.route.Access
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.EndpointDeclaration
import com.gd.rain.web.route.MountsItsOwnSurface
import org.assertj.core.api.Assertions.assertThat
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ClassUtils
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * The HTTP surface a started process serves, read through public types only: every request mapping with the `@Access`
 * it wears or the declaration its `DeclaresItsOwnAccess` controller makes, and every route a `MountsItsOwnSurface` bean
 * declares (the probes). A mapping a `SurfaceExemption` covers (the error dispatch) is not part of it.
 *
 * rain-access verifies this surface at start-up but publishes no view of what it verified, so a test reads it again.
 */
object Surface {
    fun of(context: ApplicationContext): List<EndpointDeclaration> {
        val mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
        val exemptions = context.getBeansOfType(SurfaceExemption::class.java).values
        val mapped =
            mapping.handlerMethods.flatMap { (info, handler) ->
                val resolved = handler.createWithResolvedBean()
                val type = ClassUtils.getUserClass(resolved.beanType)
                if (exemptions.any { it.handlerType.isAssignableFrom(type) }) return@flatMap emptyList()
                val access =
                    AnnotatedElementUtils.findMergedAnnotation(resolved.method, Access::class.java)
                        ?: AnnotatedElementUtils.findMergedAnnotation(type, Access::class.java)
                val derived = (resolved.bean as? DeclaresItsOwnAccess)?.accessDeclarations()?.associateBy { it.key }.orEmpty()
                info.patternValues.flatMap { pattern ->
                    info.methodsCondition.methods.map { method ->
                        access?.let { EndpointDeclaration.of(method.name, pattern, it) }
                            ?: checkNotNull(derived["${method.name} $pattern"]) { "${method.name} $pattern declares no access" }
                    }
                }
            }
        return (
            mapped +
                context
                    .getBeansOfType(
                        MountsItsOwnSurface::class.java,
                    ).values
                    .flatMap { it.mountedDeclarations() }
        ).sortedBy { it.key }
    }

    /** A path of [declaration] with every `{variable}` filled by a value no binder refuses, so a request reaches the access check. */
    fun filled(
        declaration: EndpointDeclaration,
        values: Map<String, String>,
    ): String = values.entries.fold(declaration.path) { path, (name, value) -> path.replace("{$name}", value) }
}

/** `?name=value&…` with names and values percent-encoded, as a browser sends `filter[status][eq]`. */
fun query(vararg parameters: Pair<String, String>): String =
    parameters.joinToString("&", prefix = "?") { (name, value) ->
        "${URLEncoder.encode(name, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
    }

/**
 * One api process on one migrated database for the surface suites, started once per test JVM: the suites read and
 * refuse, and each writes only rows it names itself. Every suite signs in again, so no access token outlives its test.
 */
object SharedApi {
    const val ADMINISTRATOR = "surface-admin@helpdesk.example"
    const val NOBODY = "surface-nobody@helpdesk.example"

    val database: RainDatabase by lazy { RainPostgres.freshDatabase("surface").also(Stand::migrate) }

    val process: SampleProcess by lazy {
        SampleProcess.api(database).also { started ->
            Staff.ensureRoles(started)
            Staff.enrol(started, ADMINISTRATOR, HelpdeskRoles.ADMINISTRATOR)
            Staff.enrol(started, NOBODY)
            Runtime.getRuntime().addShutdownHook(Thread { started.close() })
        }
    }

    /** Values for every path variable the surface names: a well-formed id, the served subject type, a declared slug and code. */
    val VARIABLES: Map<String, String> =
        mapOf(
            "id" to "6f6e05b5-8f22-4a1a-9b3b-000000000001",
            "roleId" to "6f6e05b5-8f22-4a1a-9b3b-000000000002",
            "subjectId" to "6f6e05b5-8f22-4a1a-9b3b-000000000003",
            "sessionId" to "6f6e05b5-8f22-4a1a-9b3b-000000000004",
            "subjectType" to "agent",
            "slug" to HelpdeskRoles.SUPERVISOR,
            "code" to "ticket.read",
        )
}

/** A server-sent event: its name and its data. */
data class ServerEvent(
    val name: String,
    val data: String,
)

/** Reads a server-sent event stream on a thread of its own; [next] waits a bounded time for the next event. */
class EventStreamReader private constructor(
    private val lines: Stream<String>,
) : AutoCloseable {
    private val events = LinkedBlockingQueue<ServerEvent>()
    private val reader =
        Thread.ofVirtual().start {
            var name: String? = null
            lines.forEach { line ->
                when {
                    line.startsWith("event: ") -> name = line.removePrefix("event: ")

                    line.startsWith(
                        "data: ",
                    ) -> events.add(ServerEvent(checkNotNull(name) { "data before an event name" }, line.removePrefix("data: ")))

                    line.isEmpty() -> name = null
                }
            }
        }

    fun next(): ServerEvent = checkNotNull(events.poll(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)) { "no server-sent event arrived" }

    override fun close() {
        lines.close()
        reader.interrupt()
    }

    companion object {
        fun open(
            http: ApplicationHttp,
            path: String,
            vararg headers: Pair<String, String>,
        ): EventStreamReader {
            val request = HttpRequest.newBuilder(http.uri(path)).timeout(Duration.ofSeconds(120)).GET()
            headers.forEach { (name, value) -> request.header(name, value) }
            val response = http.client.send(request.build(), HttpResponse.BodyHandlers.ofLines())
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream;charset=UTF-8")
            return EventStreamReader(response.body())
        }
    }
}
