package com.gd.rain.web.autoconfigure

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.CommandDeclarations
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.boot.runtime.RuntimeSelection
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.web.config.ForwardHeadersCheck
import com.gd.rain.web.config.MultipartLimitsCheck
import com.gd.rain.web.config.RainWebProperties
import com.gd.rain.web.error.RainErrorController
import com.gd.rain.web.error.RainExceptionHandler
import com.gd.rain.web.filter.BodyLimitFilter
import com.gd.rain.web.filter.CrossSiteFilter
import com.gd.rain.web.filter.ProbeOnlyFilter
import com.gd.rain.web.filter.ProblemCorsProcessor
import com.gd.rain.web.filter.RequestBudgetFilter
import com.gd.rain.web.filter.RequestBudgetTimer
import com.gd.rain.web.filter.RequestLogFilter
import com.gd.rain.web.filter.SecurityHeadersFilter
import com.gd.rain.web.filter.WebFilterOrder
import com.gd.rain.web.probe.ProbeController
import com.gd.rain.web.probe.ProbeSurface
import com.gd.rain.web.problem.ErrorCodeRegistrar
import com.gd.rain.web.problem.ProblemLocalizer
import com.gd.rain.web.problem.ProblemRenderer
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.problem.RainWebErrorCodes
import com.gd.rain.web.problem.StatusTable
import com.gd.rain.web.route.MountsItsOwnSurface
import com.gd.rain.web.route.RequestPrincipal
import jakarta.servlet.Filter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.servlet.autoconfigure.MultipartProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * The error code registry built from every catalog, the problem renderer and status table, and — in a
 * servlet application — the exception handler, the error controller and the problem writer.
 */
@AutoConfiguration(before = [ErrorMvcAutoConfiguration::class, WebMvcAutoConfiguration::class])
public class RainWebErrorAutoConfiguration {
    @Bean
    public fun rainErrorCodes(): ErrorCodeCatalog = RainErrorCodes

    @Bean
    public fun rainWebErrorCodes(): ErrorCodeCatalog = RainWebErrorCodes

    @Bean
    @ConditionalOnMissingBean
    public fun errorCodeRegistry(catalogs: ObjectProvider<ErrorCodeCatalog>): ErrorCodeRegistry =
        ErrorCodeRegistrar.register(catalogs.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    public fun statusTable(): StatusTable = StatusTable.V1

    @Bean
    @ConditionalOnMissingBean
    public fun problemRenderer(
        registry: ErrorCodeRegistry,
        localizers: ObjectProvider<ProblemLocalizer>,
    ): ProblemRenderer = ProblemRenderer(registry) { localizers.orderedStream().toList() }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public class ServletErrors {
        @Bean
        @ConditionalOnMissingBean
        public fun problemWriter(renderer: ProblemRenderer): ProblemWriter = ProblemWriter(renderer)

        @Bean
        @ConditionalOnMissingBean(ResponseEntityExceptionHandler::class)
        public fun rainExceptionHandler(
            renderer: ProblemRenderer,
            statuses: StatusTable,
            translators: ObjectProvider<FaultTranslator>,
        ): RainExceptionHandler = RainExceptionHandler(renderer, statuses, translators)

        @Bean
        @ConditionalOnMissingBean(ErrorController::class)
        public fun rainErrorController(
            renderer: ProblemRenderer,
            statuses: StatusTable,
        ): RainErrorController = RainErrorController(renderer, statuses)
    }
}

/** rain's servlet filters, each registered with its [WebFilterOrder], and the client-address check. */
@AutoConfiguration(after = [RainWebErrorAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RainWebProperties::class)
public class RainWebFilterAutoConfiguration {
    @Bean
    public fun securityHeadersFilterRegistration(properties: RainWebProperties): FilterRegistrationBean<SecurityHeadersFilter> =
        registration("rainSecurityHeadersFilter", SecurityHeadersFilter(properties.securityHeaders), WebFilterOrder.SECURITY_HEADERS)

    @Bean
    public fun requestLogFilterRegistration(
        properties: RainWebProperties,
        principals: ObjectProvider<RequestPrincipal>,
    ): FilterRegistrationBean<RequestLogFilter> =
        registration(
            "rainRequestLogFilter",
            RequestLogFilter(properties.probes.paths) { principals.getIfAvailable() },
            WebFilterOrder.REQUEST_LOG,
        )

    @Bean
    @ConditionalOnMissingBean
    public fun requestBudgetTimer(): RequestBudgetTimer = RequestBudgetTimer()

    @Bean
    public fun requestBudgetFilterRegistration(
        properties: RainWebProperties,
        timer: RequestBudgetTimer,
        writer: ProblemWriter,
    ): FilterRegistrationBean<RequestBudgetFilter> =
        registration("rainRequestBudgetFilter", RequestBudgetFilter(properties.requestBudget, timer, writer), WebFilterOrder.REQUEST_BUDGET)

    @Bean
    public fun corsFilterRegistration(
        properties: RainWebProperties,
        writer: ProblemWriter,
    ): FilterRegistrationBean<CorsFilter> {
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", properties.cors.toCorsConfiguration())
        val filter = CorsFilter(source)
        filter.setCorsProcessor(ProblemCorsProcessor(writer))
        return registration("rainCorsFilter", filter, WebFilterOrder.CORS)
    }

    @Bean
    public fun bodyLimitFilterRegistration(
        properties: RainWebProperties,
        writer: ProblemWriter,
        multipart: ObjectProvider<MultipartProperties>,
    ): FilterRegistrationBean<BodyLimitFilter> =
        registration(
            "rainBodyLimitFilter",
            BodyLimitFilter(properties.bodyLimit, writer, multipartParsedByContainer = multipart.ifAvailable?.isEnabled == true),
            WebFilterOrder.BODY_LIMIT,
        )

    @Bean
    public fun crossSiteFilterRegistration(
        properties: RainWebProperties,
        writer: ProblemWriter,
    ): FilterRegistrationBean<CrossSiteFilter> =
        registration("rainCrossSiteFilter", CrossSiteFilter(properties.cors.allowedOrigins, writer), WebFilterOrder.CROSS_SITE)

    @Bean
    public fun multipartLimitsCheck(
        properties: RainWebProperties,
        multipart: ObjectProvider<MultipartProperties>,
    ): ConfigurationCheck = MultipartLimitsCheck(multipart.ifAvailable, properties.bodyLimit)

    @Bean
    public fun forwardHeadersCheck(
        properties: RainWebProperties,
        environment: Environment,
    ): ConfigurationCheck = ForwardHeadersCheck(properties.clientAddress, environment)
}

/**
 * The probes, served whenever the application is a servlet application, and — when the API role is not
 * active — the filter that serves nothing but the probes and the Actuator base path.
 */
@AutoConfiguration(after = [RainHealthAutoConfiguration::class, RainWebFilterAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RainWebProperties::class)
public class RainProbeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun probeController(registry: HealthRegistry): ProbeController = ProbeController(registry)

    @Bean
    public fun probeRoutes(
        controller: ProbeController,
        properties: RainWebProperties,
    ): RouterFunction<ServerResponse> = controller.routes(properties.probes.livePath, properties.probes.readyPath)

    /** The probe routes on the verified surface: they are functional routes, so no annotation declares them. */
    @Bean
    public fun probeSurface(properties: RainWebProperties): MountsItsOwnSurface =
        ProbeSurface(properties.probes.livePath, properties.probes.readyPath)

    @Configuration(proxyBeanMethods = false)
    @Conditional(OnApiRoleAbsentCondition::class)
    public class ProbeOnly {
        @Bean
        public fun probeOnlyFilterRegistration(
            properties: RainWebProperties,
            writer: ProblemWriter,
            environment: Environment,
        ): FilterRegistrationBean<ProbeOnlyFilter> =
            registration(
                "rainProbeOnlyFilter",
                ProbeOnlyFilter(properties.probes.paths, environment.getProperty(ACTUATOR_BASE_PATH, ACTUATOR_BASE_PATH_DEFAULT), writer),
                WebFilterOrder.PROBE_ONLY,
            )
    }

    public companion object {
        public const val ACTUATOR_BASE_PATH: String = "management.endpoints.web.base-path"

        /** Spring Boot's declared default for [ACTUATOR_BASE_PATH]. */
        public const val ACTUATOR_BASE_PATH_DEFAULT: String = "/actuator"
    }
}

/** Matches when the API role is not active. An invalid runtime selection fails the context with its problems. */
internal class OnApiRoleAbsentCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val active =
            RuntimeSelection.activeRoles(
                RuntimeSelection.resolve(context.environment, CommandDeclarations.load(context.classLoader)),
            )
        val names = active.joinToString(", ") { it.wire }
        return if (RuntimeRole.API in active) {
            ConditionOutcome.noMatch("the api role is active; active: [$names]")
        } else {
            ConditionOutcome.match("the api role is not active; active: [$names]")
        }
    }
}

private fun <T : Filter> registration(
    name: String,
    filter: T,
    order: Int,
): FilterRegistrationBean<T> {
    val registration = FilterRegistrationBean(filter)
    registration.setName(name)
    registration.order = order
    return registration
}
