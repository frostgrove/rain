package com.gd.rain.audit.autoconfigure

import com.gd.rain.audit.AuditEventType
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.audit.scope.AuditScopeContributor
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.id.IdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import java.lang.reflect.Proxy
import java.time.Clock
import java.util.UUID

class RainAuditAutoConfigurationTest {
    private val event = AuditEventType("tickets", "closed", "ticket")
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RainAuditAutoConfiguration::class.java))

    @Test
    fun `the auto-configuration assembles audit services from independent application contributors`() {
        runner
            .withBean(DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(PlatformTransactionManager::class.java, { inert(PlatformTransactionManager::class.java) })
            .withBean(IdGenerator::class.java, { IdGenerator { UUID(0, 1) } })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(AuditEventType::class.java, { event })
            .withBean(CurrentActor::class.java, { CurrentActor { null } })
            .withBean(AuditScopeContributor::class.java, { AuditScopeContributor { null } })
            .run { context ->
                assertThat(context).hasSingleBean(AuditRecorder::class.java).hasSingleBean(ConfigurationCheck::class.java)
                assertThat(context.getBean(ConfigurationCheck::class.java).problems()).isEmpty()
            }
    }

    private companion object {
        fun <T> inert(type: Class<T>): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                    if (method.name == "toString") type.simpleName else error("$type is not used while wiring audit")
                },
            )
    }
}
