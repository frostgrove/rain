package com.gd.rain.sample.config

import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RouterFunctions

/**
 * WORKAROUND for a rain defect. In a process with the `api` role, rain-access enforces a functional route by the pattern
 * it reads from `RouterFunctions.MATCHING_PATTERN_ATTRIBUTE`; Spring's `RouterFunctionMapping` removes that attribute once
 * the route matched and publishes the pattern as `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`. So every request to a
 * functional route — rain-web's own `/live` and `/ready` — is answered `500 internal` in an api process
 * (`a request routed to a functional route carries no matched pattern`), and no orchestrator can probe it.
 *
 * This interceptor runs before rain-access's and, for a functional route only, puts back under the name rain-access reads
 * the pattern Spring matched; it infers nothing. It goes when rain-access reads the attribute Spring sets.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnRainRole(RuntimeRole.API)
class FunctionalRoutePatternConfiguration {
    @Bean
    fun functionalRoutePattern(): WebMvcConfigurer = FunctionalRoutePattern()

    private class FunctionalRoutePattern :
        WebMvcConfigurer,
        Ordered {
        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(MatchedPatternRestored).order(Ordered.HIGHEST_PRECEDENCE)
        }
    }

    private object MatchedPatternRestored : HandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            handler: Any,
        ): Boolean {
            if (handler is HandlerFunction<*> && request.getAttribute(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE) == null) {
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.let {
                    request.setAttribute(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE, it)
                }
            }
            return true
        }
    }
}
