package com.gd.rain.i18n.web.autoconfigure

import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.web.AcceptLanguageServletLocaleSource
import com.gd.rain.i18n.web.CookieServletLocaleSource
import com.gd.rain.i18n.web.DefaultI18nViewFactory
import com.gd.rain.i18n.web.I18nLocaleContextResolver
import com.gd.rain.i18n.web.I18nProblemLocalizer
import com.gd.rain.i18n.web.I18nRequestContext
import com.gd.rain.i18n.web.I18nRequestContextCleanupListener
import com.gd.rain.i18n.web.I18nRequestFilter
import com.gd.rain.i18n.web.I18nRequestLocaleContextTaskDecorator
import com.gd.rain.i18n.web.I18nRuntime
import com.gd.rain.i18n.web.I18nViewFactory
import com.gd.rain.i18n.web.I18nWebProperties
import com.gd.rain.i18n.web.I18nWebSettings
import com.gd.rain.i18n.web.LocalizedProblemMessages
import com.gd.rain.i18n.web.ObservingI18nViewFactory
import com.gd.rain.i18n.web.ParameterServletLocaleSource
import com.gd.rain.i18n.web.ServletLocaleSource
import com.gd.rain.web.filter.WebFilterOrder
import com.gd.rain.web.problem.ProblemLocalizer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.task.TaskDecorator
import org.springframework.web.context.annotation.RequestScope
import org.springframework.web.servlet.LocaleContextResolver

/**
 * Activates only when an application deliberately supplies a catalog snapshot provider. The servlet
 * bridge is therefore optional: a library can depend on `rain-i18n` without inheriting any web policy.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(CatalogSnapshotProvider::class)
@ConditionalOnBooleanProperty(value = [I18nWebProperties.ENABLED], matchIfMissing = true)
@EnableConfigurationProperties(I18nWebProperties::class)
public class RainI18nWebAutoConfiguration {
    @Bean
    public fun i18nWebSettings(properties: I18nWebProperties): I18nWebSettings = properties.settings()

    @Bean
    @ConditionalOnMissingBean
    public fun i18nViewFactory(observers: ObjectProvider<I18nObserver>): I18nViewFactory =
        observers.ifAvailable?.let(::ObservingI18nViewFactory) ?: DefaultI18nViewFactory

    @Bean
    @ConditionalOnMissingBean(AcceptLanguageServletLocaleSource::class)
    public fun acceptLanguageServletLocaleSource(): AcceptLanguageServletLocaleSource = AcceptLanguageServletLocaleSource()

    @Bean(name = ["rainI18nQueryServletLocaleSource"])
    @ConditionalOnProperty(prefix = I18nWebProperties.PREFIX, name = ["query-source"])
    @ConditionalOnMissingBean(name = ["rainI18nQueryServletLocaleSource"])
    public fun queryServletLocaleSource(settings: I18nWebSettings): ServletLocaleSource =
        ParameterServletLocaleSource(checkNotNull(settings.querySource))

    @Bean(name = ["rainI18nCookieServletLocaleSource"])
    @ConditionalOnProperty(prefix = I18nWebProperties.PREFIX, name = ["cookie-source"])
    @ConditionalOnMissingBean(name = ["rainI18nCookieServletLocaleSource"])
    public fun cookieServletLocaleSource(settings: I18nWebSettings): ServletLocaleSource =
        CookieServletLocaleSource(checkNotNull(settings.cookieSource))

    @Bean
    public fun i18nRequestFilterRegistration(
        catalogs: CatalogSnapshotProvider,
        viewFactory: I18nViewFactory,
        sources: ObjectProvider<ServletLocaleSource>,
        observers: ObjectProvider<I18nObserver>,
    ): FilterRegistrationBean<I18nRequestFilter> =
        FilterRegistrationBean(
            I18nRequestFilter(
                catalogs,
                viewFactory,
                sources.orderedStream().toList(),
                observers.ifAvailable ?: I18nObserver.NONE,
            ),
        ).also { registration ->
            registration.setName("rainI18nRequestFilter")
            registration.order = WebFilterOrder.CROSS_SITE + 5
        }

    @Bean
    @ConditionalOnMissingBean(I18nRequestContextCleanupListener::class)
    public fun i18nRequestContextCleanupListener(): ServletListenerRegistrationBean<I18nRequestContextCleanupListener> =
        ServletListenerRegistrationBean(I18nRequestContextCleanupListener())

    @Bean
    @ConditionalOnMissingBean(LocaleContextResolver::class)
    public fun i18nLocaleContextResolver(): LocaleContextResolver = I18nLocaleContextResolver()

    @Bean
    @ConditionalOnBean(LocalizedProblemMessages::class)
    public fun i18nProblemLocalizer(messages: LocalizedProblemMessages): ProblemLocalizer = I18nProblemLocalizer(messages)

    /** An application deliberately applies this named decorator to its chosen executor. */
    @Bean(name = ["rainI18nLocaleContextTaskDecorator"])
    @ConditionalOnProperty(
        prefix = I18nWebProperties.PREFIX,
        name = ["propagate-executors"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(name = ["rainI18nLocaleContextTaskDecorator"])
    public fun i18nRequestLocaleContextTaskDecorator(settings: I18nWebSettings): TaskDecorator {
        check(settings.propagateExecutors) { "the i18n request locale decorator requires ${I18nWebProperties.PROPAGATE_EXECUTORS}=true" }
        return I18nRequestLocaleContextTaskDecorator()
    }

    /**
     * The magic-first entry point for controllers and views. It exposes the exact low-level [view]
     * that the filter minted, so escaping the convenience layer never requires a second resolution.
     */
    @Bean
    @RequestScope
    public fun i18nRuntime(request: HttpServletRequest): I18nRuntime =
        I18nRuntime(requireNotNull(I18nRequestContext.of(request)) { "Rain i18n request filter did not establish a context" })
}
