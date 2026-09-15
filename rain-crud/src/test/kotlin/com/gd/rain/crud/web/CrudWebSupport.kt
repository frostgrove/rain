package com.gd.rain.crud.web

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.crud.Books
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.MemoryStore
import com.gd.rain.crud.SwitchableCallers
import com.gd.rain.crud.TestCaller
import com.gd.rain.crud.autoconfigure.RainCrudAutoConfiguration
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.web.autoconfigure.RainProbeAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebFilterAutoConfiguration
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.EndpointDeclaration
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The properties a rain web context in these tests starts from, as rain-web's own tests use them. */
val BASE_PROPERTIES: Array<String> =
    arrayOf(
        "rain.runtime.roles=api",
        "rain.deployment.stage=test",
        "rain.web.body-limit=1MB",
        "rain.web.request-budget=30s",
        "rain.web.client-address=direct",
        "server.forward-headers-strategy=none",
        "spring.servlet.multipart.enabled=false",
    )

/** A servlet context with MVC, rain's runtime, health, web and crud auto-configurations, and the books fixture — no server. */
fun booksRunner(): WebApplicationContextRunner =
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
                RainCrudAutoConfiguration::class.java,
            ),
        ).withPropertyValues(*BASE_PROPERTIES)
        .withUserConfiguration(BookFixture::class.java)

/** MockMvc over [context] with every registered filter applied in registration order, as the container would. */
fun mockMvcOf(context: WebApplicationContext): MockMvc {
    val builder = MockMvcBuilders.webAppContextSetup(context)
    context
        .getBeansOfType(FilterRegistrationBean::class.java)
        .values
        .sortedBy { it.order }
        .forEach { builder.addFilter<DefaultMockMvcBuilder>(checkNotNull(it.filter)) }
    return builder.build()
}

/**
 * What a web test works with: the requests, the rows behind them and who is calling. `/books` is unversioned and
 * unscoped; `/editions` is the same table versioned, confined to the caller's shelf.
 */
class BooksWorld(
    val mvc: MockMvc,
    val store: MemoryStore,
    val editions: MemoryStore,
    val callers: SwitchableCallers,
) {
    fun get(
        path: String,
        vararg parameters: Pair<String, String>,
    ): MockHttpServletResponse = perform(MockMvcRequestBuilders.get(path), *parameters)

    fun post(
        path: String,
        body: String,
    ): MockHttpServletResponse = perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    fun patch(
        path: String,
        body: String,
    ): MockHttpServletResponse = perform(MockMvcRequestBuilders.patch(path).contentType(MediaType.APPLICATION_JSON).content(body))

    fun put(
        path: String,
        body: String,
    ): MockHttpServletResponse = perform(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(body))

    fun perform(
        request: MockHttpServletRequestBuilder,
        vararg parameters: Pair<String, String>,
    ): MockHttpServletResponse {
        parameters.forEach { (name, value) -> request.param(name, value) }
        return mvc.perform(request).andReturn().response
    }
}

/** Runs [block] against a started books context whose caller holds every books permission on shelf `a`. */
fun withBooks(block: (BooksWorld) -> Unit) {
    booksRunner().run { context ->
        assertThat(context).hasNotFailed()
        val callers = context.getBean(SwitchableCallers::class.java)
        callers.caller = TestCaller.of("a", *Books.EVERY_PERMISSION.toTypedArray())
        block(
            BooksWorld(
                mockMvcOf(context),
                context.getBean("bookStore", MemoryStore::class.java),
                context.getBean("editionStore", MemoryStore::class.java),
                callers,
            ),
        )
    }
}

private val READER: JsonMapper = JsonMapper.builder().build()

fun MockHttpServletResponse.json(): JsonNode = READER.readTree(contentAsByteArray)

/** The problem body of a refusal, after checking it is one with [status]. */
fun MockHttpServletResponse.problem(status: Int): JsonNode {
    assertThat(this.status).describedAs("status of %s", contentAsString).isEqualTo(status)
    assertThat(contentType).describedAs("content type of a refusal").isEqualTo("application/problem+json")
    return json()
}

/** `pointer code` of each rendered violation. */
fun JsonNode.pointedCodes(): List<String> = get("errors").values().map { "${it["pointer"].asString()} ${it["code"].asString()}" }

@Configuration(proxyBeanMethods = false)
class BookFixture {
    @Bean
    fun bookCallers(): SwitchableCallers = SwitchableCallers()

    @Bean
    fun bookStore(): MemoryStore = MemoryStore(Books.SCHEMA)

    @Bean
    fun editionStore(): MemoryStore = MemoryStore(Books.VERSIONED)

    @Bean
    fun mountedBooks(
        @Qualifier("bookStore") store: MemoryStore,
        callers: SwitchableCallers,
    ): MountedResource<Map<String, Any?>> =
        MountedResource(
            "/books",
            CrudOperation.entries.toSet(),
            CrudResource(Books.rules(includable = FieldGrant.only("reviews")), Books.policy(), store, callers, listOf(Books.REVIEWS)),
        )

    @Bean
    fun mountedEditions(
        @Qualifier("editionStore") store: MemoryStore,
        callers: SwitchableCallers,
    ): MountedResource<Map<String, Any?>> =
        MountedResource(
            "/editions",
            CrudOperation.entries.toSet(),
            CrudResource(Books.rules(), Books.policy(Books.SHELF_SCOPE), store, callers, emptyList()),
        )

    @Bean
    fun bookController(
        @Qualifier("mountedBooks") books: MountedResource<Map<String, Any?>>,
    ): BookController = BookController(books)

    @Bean
    fun editionController(
        @Qualifier("mountedEditions") editions: MountedResource<Map<String, Any?>>,
    ): EditionController = EditionController(editions)
}

/** An application controller over a mounted resource: every handler is one line. */
abstract class MountedController(
    private val mounted: MountedResource<Map<String, Any?>>,
) : DeclaresItsOwnAccess {
    private val resource: CrudResource<Map<String, Any?>> get() = mounted.resource

    @GetMapping
    fun list(request: HttpServletRequest): PageBody<Map<String, Any?>> = CrudMvc.list(resource, request)

    @GetMapping("/count")
    fun count(request: HttpServletRequest): CountBody = CrudMvc.count(resource, request)

    @GetMapping("/{id}")
    fun get(
        @PathVariable("id") id: String,
        request: HttpServletRequest,
    ): Map<String, Any?> = CrudMvc.get(resource, id, request)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody(required = false) body: String?,
    ): Map<String, Any?> = CrudMvc.create(resource, body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: String?,
    ): Map<String, Any?> = CrudMvc.update(resource, id, body)

    @PutMapping("/{id}")
    fun replace(
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: String?,
    ): Map<String, Any?> = CrudMvc.replace(resource, id, body)

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable("id") id: String,
    ): DeletedBody = CrudMvc.delete(resource, id)

    @PostMapping("/bulk-delete")
    fun bulkDelete(
        @RequestBody(required = false) body: String?,
    ): DeletedBody = CrudMvc.bulkDelete(resource, body)

    override fun accessDeclarations(): List<EndpointDeclaration> = mounted.declarations()
}

@RestController
@RequestMapping("/books")
class BookController(
    books: MountedResource<Map<String, Any?>>,
) : MountedController(books)

@RestController
@RequestMapping("/editions")
class EditionController(
    editions: MountedResource<Map<String, Any?>>,
) : MountedController(editions)
