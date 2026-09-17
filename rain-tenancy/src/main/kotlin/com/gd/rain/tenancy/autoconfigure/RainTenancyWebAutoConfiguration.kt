package com.gd.rain.tenancy.autoconfigure

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.tenancy.web.AuthorityTenantRequestScopeResolver
import com.gd.rain.tenancy.web.DeclaresItsOwnTenancy
import com.gd.rain.tenancy.web.TenantRequestScopeResolver
import com.gd.rain.tenancy.web.TenantRouteDeclarations
import com.gd.rain.tenancy.web.TenantRouteInterceptor
import com.gd.rain.tenancy.web.TenantServletSurfaceVerifier
import com.gd.rain.tenancy.web.TenantSurfaceExemption
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.context.ConfigurableWebApplicationContext
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.function.support.RouterFunctionMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** Servlet adapter: route declarations bind scopes but leave tenant transaction lifetime to services. */
@AutoConfiguration(after = [RainTenancyAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(TenantAuthority::class, TenantResolver::class)
public class RainTenancyWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun tenantRequestScopeResolver(
        authority: TenantAuthority,
        resolver: TenantResolver,
    ): TenantRequestScopeResolver = AuthorityTenantRequestScopeResolver(authority, resolver)

    @Bean
    public fun tenantRouteDeclarations(exemptions: ObjectProvider<TenantSurfaceExemption>): TenantRouteDeclarations =
        TenantRouteDeclarations(exemptions.orderedStream().toList())

    @Bean
    public fun tenantRouteInterceptor(
        declarations: TenantRouteDeclarations,
        resolver: TenantRequestScopeResolver,
        resources: ObjectProvider<DeclaresItsOwnTenancy>,
    ): TenantRouteInterceptor = TenantRouteInterceptor(declarations, resolver, resources.orderedStream().toList())

    @Bean
    public fun tenantWebMvcConfigurer(interceptor: TenantRouteInterceptor): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(interceptor)
            }
        }

    @Bean
    public fun tenantServletSurfaceVerifier(
        context: ConfigurableWebApplicationContext,
        declarations: TenantRouteDeclarations,
        resources: ObjectProvider<DeclaresItsOwnTenancy>,
    ): ConfigurationCheck =
        TenantServletSurfaceVerifier(
            mapping = { context.getBeanProvider(RequestMappingHandlerMapping::class.java).ifAvailable },
            declarations = declarations,
            resources = resources.orderedStream().toList(),
            routes = { context.getBeanProvider(RouterFunctionMapping::class.java).ifAvailable?.routerFunction },
        )
}
