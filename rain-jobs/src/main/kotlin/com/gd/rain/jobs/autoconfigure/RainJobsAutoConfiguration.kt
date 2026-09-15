package com.gd.rain.jobs.autoconfigure

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.ConnectionDemandCheck
import com.gd.rain.jobs.JacksonJobPayloadCodec
import com.gd.rain.jobs.Jitter
import com.gd.rain.jobs.JobCatalogCheck
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobPayloadCodec
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobWorkerCheck
import com.gd.rain.jobs.JobsErrorCodes
import com.gd.rain.jobs.JobsFaultTranslator
import com.gd.rain.jobs.JobsProperties
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.jobs.WorkQueue
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.jobs.internal.Jackson3TaskSerializer
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.admin.LedgerJobAdministration
import com.gd.rain.jobs.internal.execution.AttemptStatementTimeout
import com.gd.rain.jobs.internal.execution.VirtualAttemptThreads
import com.gd.rain.jobs.internal.housekeeping.JobReaper
import com.gd.rain.jobs.internal.housekeeping.JobRetention
import com.gd.rain.jobs.internal.ledger.JooqAdministrationLedger
import com.gd.rain.jobs.internal.ledger.JooqAttemptLedger
import com.gd.rain.jobs.internal.ledger.JooqHousekeepingLedger
import com.gd.rain.jobs.internal.ledger.JooqIntentLedger
import com.gd.rain.jobs.internal.queue.SchedulerWorkQueue
import com.gd.rain.jobs.internal.worker.DbSchedulerFactory
import com.gd.rain.jobs.internal.worker.JobsWorker
import com.gd.rain.jobs.internal.worker.REAL_GRACE_WAIT
import com.gd.rain.jobs.internal.worker.SchedulerFactory
import com.gd.rain.jobs.internal.worker.WorkerAssembly
import com.gd.rain.jobs.internal.worker.WorkerParts
import com.gd.rain.persistence.autoconfigure.RainPersistenceAutoConfiguration
import com.gd.rain.persistence.lock.AdvisoryLockStore
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom
import javax.sql.DataSource

/**
 * rain-jobs. The client side — [WorkQueue], [JobAdministration], the catalogue and its checks — exists in every role;
 * the worker side — the schedulers, the lease renewer, the reaper, retention and recurring work, one [JobsWorker]
 * lifecycle — only in the worker role.
 */
@AutoConfiguration(
    after = [JooqAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, RainPersistenceAutoConfiguration::class],
)
@ConditionalOnBean(DataSource::class, DSLContext::class, PlatformTransactionManager::class)
@EnableConfigurationProperties(JobsProperties::class)
public class RainJobsAutoConfiguration {
    @Bean
    public fun jobCatalog(
        profiles: ObjectProvider<JobProfile>,
        definitions: ObjectProvider<JobDefinition<*>>,
    ): JobCatalog = JobCatalog(profiles.orderedStream().toList(), definitions.orderedStream().toList())

    @Bean
    public fun jobCatalogCheck(
        catalog: JobCatalog,
        properties: JobsProperties,
    ): ConfigurationCheck = JobCatalogCheck(catalog, properties)

    @Bean
    @ConditionalOnMissingBean
    public fun jobPayloadCodec(): JobPayloadCodec = JacksonJobPayloadCodec()

    @Bean
    public fun jobsErrorCodes(): ErrorCodeCatalog = JobsErrorCodes

    @Bean
    public fun jobsFaultTranslator(): FaultTranslator = JobsFaultTranslator

    @Bean
    @ConditionalOnMissingBean
    public fun workQueue(
        catalog: JobCatalog,
        dsl: DSLContext,
        dataSource: DataSource,
        transactions: PlatformTransactionManager,
        codec: JobPayloadCodec,
        ids: IdGenerator,
        clock: Clock,
    ): WorkQueue =
        SchedulerWorkQueue(catalog, JooqIntentLedger(dsl, ids), client(dataSource), codec, TransactionTemplate(transactions), ids, clock)

    @Bean
    @ConditionalOnMissingBean
    public fun jobAdministration(
        catalog: JobCatalog,
        properties: JobsProperties,
        dsl: DSLContext,
        dataSource: DataSource,
        transactions: PlatformTransactionManager,
        ids: IdGenerator,
        clock: Clock,
    ): JobAdministration =
        LedgerJobAdministration(
            catalog = catalog,
            properties = properties,
            ledger = JooqAdministrationLedger(dsl),
            intents = JooqIntentLedger(dsl, ids),
            client = client(dataSource),
            transactions = TransactionTemplate(transactions),
            clock = clock,
        )

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRainRole(RuntimeRole.WORKER)
    public class Worker {
        /**
         * Installed on the transaction manager by Boot's execution-listener customizer. The lock store is reached
         * through the provider: the transaction manager is built before the `DSLContext` the store needs.
         */
        @Bean
        public fun attemptStatementTimeout(
            statements: ObjectProvider<AdvisoryLockStore>,
            clock: Clock,
        ): AttemptStatementTimeout = AttemptStatementTimeout({ statements.getObject() }, clock)

        @Bean
        public fun jobWorkerCheck(
            catalog: JobCatalog,
            handlers: ObjectProvider<JobHandler<*>>,
            recurring: ObjectProvider<RecurringWork>,
            properties: JobsProperties,
        ): ConfigurationCheck = JobWorkerCheck(catalog, handlers.orderedStream().toList(), recurring.orderedStream().toList(), properties)

        @Bean
        public fun connectionDemandCheck(
            catalog: JobCatalog,
            recurring: ObjectProvider<RecurringWork>,
            properties: JobsProperties,
            environment: Environment,
        ): ConfigurationCheck = ConnectionDemandCheck(catalog, recurring.orderedStream().toList(), properties, environment)

        @Bean
        public fun jobsWorker(
            catalog: JobCatalog,
            handlers: ObjectProvider<JobHandler<*>>,
            recurring: ObjectProvider<RecurringWork>,
            properties: JobsProperties,
            dataSource: DataSource,
            dsl: DSLContext,
            transactions: PlatformTransactionManager,
            locks: AdvisoryLocks,
            statements: AdvisoryLockStore,
            codec: JobPayloadCodec,
            ids: IdGenerator,
            clock: Clock,
            meters: ObjectProvider<MeterRegistry>,
        ): JobsWorker {
            val jitter = Jitter { bound -> ThreadLocalRandom.current().nextLong(bound) }
            val host = lazy { InetAddress.getLocalHost().hostName }
            val factory =
                SchedulerFactory { spec ->
                    DbSchedulerFactory(
                        dataSource,
                        properties.scheduler,
                        properties.drainGrace,
                        host.value,
                        clock,
                        meters.ifAvailable,
                    ).create(spec)
                }
            return JobsWorker(
                assemble = {
                    val attempts = JooqAttemptLedger(dsl)
                    val housekeeping = JooqHousekeepingLedger(dsl, attempts)
                    val builtIn =
                        listOf(
                            JobReaper(catalog, housekeeping, TransactionTemplate(transactions), properties.reaper, jitter, clock),
                            JobRetention(catalog, housekeeping, properties.retention, clock),
                        )
                    WorkerAssembly.assemble(
                        WorkerParts(
                            catalog = catalog,
                            handlers = handlers.orderedStream().toList(),
                            recurring = builtIn + recurring.orderedStream().toList(),
                            properties = properties,
                            ledger = attempts,
                            locks = locks,
                            statements = statements,
                            threads = VirtualAttemptThreads,
                            codec = codec,
                            jitter = jitter,
                            ids = ids,
                            clock = clock,
                            host = host.value,
                            meters = meters.ifAvailable,
                        ),
                    )
                },
                factory = factory,
                grace = properties.drainGrace,
                graceWait = REAL_GRACE_WAIT,
            )
        }
    }

    private companion object {
        /** On the caller's transaction, with the PostgreSQL customization stated so building it opens no connection. */
        fun client(dataSource: DataSource): SchedulerClient =
            SchedulerClient.Builder
                .create(TransactionAwareDataSourceProxy(dataSource), emptyList())
                .serializer(Jackson3TaskSerializer())
                .tableName(DbSchedulerFactory.TABLE)
                .jdbcCustomization(PostgreSqlJdbcCustomization(false, false))
                .enablePriority()
                .build()
    }
}
