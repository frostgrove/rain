package com.gd.rain.access.support

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.AttemptStore
import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.MountedSubject
import com.gd.rain.access.RevocationStore
import com.gd.rain.access.autoconfigure.RainAccessAutoConfiguration
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.web.autoconfigure.RainProbeAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebFilterAutoConfiguration
import com.gd.rain.web.config.ClientAddressMode
import com.gd.rain.web.config.RainWebProperties
import com.gd.rain.web.problem.ErrorCodeRegistrar
import com.gd.rain.web.problem.ProblemRenderer
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.problem.RainWebErrorCodes
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration
import io.mockk.mockk
import jakarta.servlet.Filter
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.util.unit.DataSize
import org.springframework.web.context.WebApplicationContext
import java.time.Duration

/** The beans a rain-access context needs that are not rain-access's own, with every store in memory. */
@Configuration(proxyBeanMethods = false)
class AccessUnitBeans {
    @Bean
    fun dsl(): DSLContext = mockk(relaxed = true)

    @Bean
    fun transactionManager(): PlatformTransactionManager = NoOpTransactionManager()

    @Bean
    fun auditRecorder(): AuditRecorder = RecordingAuditRecorder()

    @Bean
    fun ids(): IdGenerator = IdGenerator(UuidV7Ids::next)

    @Bean
    fun advisoryLocks(transactions: PlatformTransactionManager): AdvisoryLocks = noOpLocks(transactions)

    @Bean
    fun credentialStore(): MemoryCredentialStore = MemoryCredentialStore()

    @Bean
    fun sessionStore(): MemorySessionStore = MemorySessionStore()

    @Bean
    fun grants(): MemoryGrants = MemoryGrants()

    @Bean
    fun agents(): MemoryDirectory = MemoryDirectory(AGENT)

    @Bean
    fun agentsMounted(): MountedSubject = mounted(AGENT)
}

/** A servlet context with MVC, Spring Security, the Resilience4j bulkhead registry, rain's web layer and rain-access. */
fun accessWebRunner(): WebApplicationContextRunner =
    WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PropertyPlaceholderAutoConfiguration::class.java,
                JacksonAutoConfiguration::class.java,
                HttpMessageConvertersAutoConfiguration::class.java,
                DispatcherServletAutoConfiguration::class.java,
                WebMvcAutoConfiguration::class.java,
                ErrorMvcAutoConfiguration::class.java,
                ServletWebSecurityAutoConfiguration::class.java,
                SecurityFilterAutoConfiguration::class.java,
                BulkheadAutoConfiguration::class.java,
                RainRuntimeAutoConfiguration::class.java,
                RainHealthAutoConfiguration::class.java,
                RainWebErrorAutoConfiguration::class.java,
                RainWebFilterAutoConfiguration::class.java,
                RainProbeAutoConfiguration::class.java,
                RainAccessAutoConfiguration::class.java,
            ),
        ).withUserConfiguration(AccessUnitBeans::class.java)
        .withPropertyValues(*accessProperties())

/** MockMvc over a context with rain's servlet filters and Spring Security's chain applied in their registration order. */
fun mockMvcWithFilters(context: WebApplicationContext): MockMvc {
    val builder = MockMvcBuilders.webAppContextSetup(context)
    val filters =
        context
            .getBeansOfType(FilterRegistrationBean::class.java)
            .values
            .map { it.order to checkNotNull(it.filter) } +
            (SECURITY_FILTER_ORDER to context.getBean(SECURITY_FILTER_CHAIN, Filter::class.java))
    filters.sortedBy { it.first }.forEach { builder.addFilter<DefaultMockMvcBuilder>(it.second) }
    return builder.build()
}

private const val SECURITY_FILTER_CHAIN = "springSecurityFilterChain"
private const val SECURITY_FILTER_ORDER = -100

fun problemWriter(): ProblemWriter =
    ProblemWriter(ProblemRenderer(ErrorCodeRegistrar.register(listOf(RainErrorCodes, RainWebErrorCodes, AccessErrorCodes))))

/** A complete, valid `rain.access` for tests that construct properties rather than bind them. */
fun sampleAccessProperties(
    delivery: CredentialDelivery = CredentialDelivery.BOTH,
    signingKey: String = SIGNING_KEY,
    attempts: AccessProperties.Attempts = AccessProperties.Attempts(AttemptStore.MEMORY, memory = AccessProperties.MemoryAttempts(1000)),
): AccessProperties =
    AccessProperties(
        web = AccessProperties.Web("/api", delivery),
        token = AccessProperties.Token(ISSUER, AUDIENCE, signingKey, ACCESS_TTL),
        session = AccessProperties.Session(SESSION_TTL, IDLE_TTL, AccessProperties.Retention(Duration.ofDays(7), Duration.ofHours(1))),
        password = PASSWORD_RULES,
        hashing = AccessProperties.Hashing(16),
        attempts = attempts,
        revocation = AccessProperties.Revocation(RevocationStore.NONE),
        gate = AccessProperties.Gate(AccessProperties.Throttle(120, 60, 1000)),
        grants = AccessProperties.Grants(16),
    )

fun sampleWebProperties(vararg origins: String): RainWebProperties =
    RainWebProperties(
        bodyLimit = DataSize.ofMegabytes(1),
        requestBudget = Duration.ofSeconds(30),
        clientAddress = ClientAddressMode.DIRECT,
        cors =
            RainWebProperties.Cors(
                allowedOrigins = origins.toList(),
                allowedMethods = if (origins.isEmpty()) emptyList() else listOf("POST"),
            ),
    )
