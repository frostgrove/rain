package com.gd.rain.observability.health

import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent

/**
 * Turns readiness to `draining` the moment this application context starts closing.
 *
 * `ContextClosedEvent` is published before lifecycle beans stop — before a graceful web server
 * shutdown waits for in-flight requests — so a load balancer reading `/ready` during that wait is told
 * to stop sending traffic. Only this context's own close counts: a child context closing publishes the
 * event to its parent too, and that is not this process shutting down.
 */
public class HealthDrainingListener(
    private val registry: HealthRegistry,
) : ApplicationListener<ContextClosedEvent>,
    ApplicationContextAware {
    @Volatile
    private var context: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        context = applicationContext
    }

    override fun onApplicationEvent(event: ContextClosedEvent) {
        if (event.applicationContext === context) registry.startDraining()
    }
}
