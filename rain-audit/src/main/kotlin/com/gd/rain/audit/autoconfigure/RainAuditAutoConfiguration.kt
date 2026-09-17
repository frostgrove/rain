package com.gd.rain.audit.autoconfigure

import com.gd.rain.audit.AuditEventType
import com.gd.rain.audit.AuditEventTypesCheck
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.audit.JooqAuditRecorder
import com.gd.rain.audit.scope.AuditScopeContributor
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.autoconfigure.RainPersistenceAutoConfiguration
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

@AutoConfiguration(after = [JooqAutoConfiguration::class, RainPersistenceAutoConfiguration::class])
@ConditionalOnBean(DSLContext::class, PlatformTransactionManager::class)
public class RainAuditAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun auditRecorder(
        dsl: DSLContext,
        transactions: PlatformTransactionManager,
        ids: IdGenerator,
        clock: Clock,
        currentActor: ObjectProvider<CurrentActor>,
        types: ObjectProvider<AuditEventType>,
        scopeContributors: ObjectProvider<AuditScopeContributor>,
    ): AuditRecorder =
        JooqAuditRecorder(
            dsl,
            transactions,
            ids,
            clock,
            currentActor.ifAvailable,
            types.orderedStream().toList(),
            scopeContributors.orderedStream().toList(),
        )

    @Bean
    public fun auditEventTypesCheck(types: ObjectProvider<AuditEventType>): ConfigurationCheck =
        AuditEventTypesCheck(types.orderedStream().toList())
}
