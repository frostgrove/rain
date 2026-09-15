package com.gd.rain.resilience

import com.gd.rain.observability.health.HealthCheck
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A declared breaker in readiness: while it withholds calls the check fails, naming the state, when the
 * episode began and the last failure. Its public code is the declaration's; its importance is the application's,
 * stated as `rain.health.checks.breaker.<name>`.
 */
public class BreakerHealthCheck(
    private val declaration: BreakerDeclaration,
    private val breakers: BreakerRegistry,
) : HealthCheck {
    override val name: String = NAME_PREFIX + declaration.name.value

    override val code: String? = declaration.healthCode

    override val timeout: Duration? = null

    override fun probe() {
        val reading = breakers.state(declaration.name)
        if (!reading.withholding) return
        val since =
            reading.since?.atOffset(ZoneOffset.UTC)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ?: "a start not observed by this process"
        val reason = reading.reason ?: "no failure was recorded through rain"
        error("breaker ${declaration.name} is ${reading.state.name.lowercase()} since $since: $reason")
    }

    public companion object {
        /** Readiness names a breaker's check `breaker.<name>`. */
        public const val NAME_PREFIX: String = "breaker."
    }
}

/**
 * Registers one [BreakerHealthCheck] bean per [BreakerDeclaration] bean, so every declared breaker
 * reaches readiness without the application declaring its check a second time.
 */
public class BreakerHealthRegistrar : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val factory =
            registry as? ConfigurableListableBeanFactory
                ?: error("breaker health checks are registered into a listable bean factory, not ${registry.javaClass.name}")
        factory.getBeanNamesForType(BreakerDeclaration::class.java, true, false).sorted().forEach { declaration ->
            val definition = RootBeanDefinition(BreakerHealthCheck::class.java)
            definition.setInstanceSupplier {
                BreakerHealthCheck(
                    factory.getBean(declaration, BreakerDeclaration::class.java),
                    factory.getBean(BreakerRegistry::class.java),
                )
            }
            registry.registerBeanDefinition(declaration + BEAN_SUFFIX, definition)
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        // Every check is registered as a bean definition above.
    }

    public companion object {
        public const val BEAN_SUFFIX: String = ".health"
    }
}
