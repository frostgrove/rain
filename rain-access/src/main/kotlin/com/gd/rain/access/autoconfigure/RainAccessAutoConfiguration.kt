package com.gd.rain.access.autoconfigure

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.MountedSubject
import com.gd.rain.access.SubjectDirectory
import com.gd.rain.access.SubjectRegistrar
import com.gd.rain.access.SurfaceExemption
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.HashingBulkheadCheck
import com.gd.rain.access.internal.RedisClientCheck
import com.gd.rain.access.internal.RedisConnectionChoice
import com.gd.rain.access.internal.RedisQualifiers
import com.gd.rain.access.internal.UnavailableAttemptLimiter
import com.gd.rain.access.internal.UnavailableRevocationList
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.AttemptPolicy
import com.gd.rain.access.internal.attempt.MemoryAttemptLimiter
import com.gd.rain.access.internal.attempt.RedisAttemptLimiter
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.password.Argon2PasswordHasher
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.access.internal.password.PasswordHasher
import com.gd.rain.access.internal.revocation.EvictionPolicyCheck
import com.gd.rain.access.internal.revocation.NoRevocationList
import com.gd.rain.access.internal.revocation.RedisPingHealthCheck
import com.gd.rain.access.internal.revocation.RedisRevocationList
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.revocation.RevocationReplayTask
import com.gd.rain.access.internal.store.CatalogueStore
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.GrantStore
import com.gd.rain.access.internal.store.JooqCatalogueStore
import com.gd.rain.access.internal.store.JooqCredentialStore
import com.gd.rain.access.internal.store.JooqGrantStore
import com.gd.rain.access.internal.store.JooqSessionStore
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.token.AccessTokenVerifier
import com.gd.rain.access.internal.token.RefreshWindow
import com.gd.rain.access.internal.token.SessionFingerprints
import com.gd.rain.access.internal.token.SigningKey
import com.gd.rain.access.internal.token.SigningKeyMaterial
import com.gd.rain.access.internal.token.SigningKeyResolution
import com.gd.rain.access.internal.usecase.AccessFaultTranslator
import com.gd.rain.access.internal.usecase.AccessTransactions
import com.gd.rain.access.internal.usecase.CatalogueSynchronizer
import com.gd.rain.access.internal.usecase.ChangePasswordUseCase
import com.gd.rain.access.internal.usecase.CloseEverywhereUseCase
import com.gd.rain.access.internal.usecase.GrantDeclarationsCheck
import com.gd.rain.access.internal.usecase.GrantsAdministration
import com.gd.rain.access.internal.usecase.GrantsService
import com.gd.rain.access.internal.usecase.LoginUseCase
import com.gd.rain.access.internal.usecase.LogoutUseCase
import com.gd.rain.access.internal.usecase.PasswordRules
import com.gd.rain.access.internal.usecase.ProvisioningService
import com.gd.rain.access.internal.usecase.RefreshUseCase
import com.gd.rain.access.internal.usecase.RoleAdministration
import com.gd.rain.access.internal.usecase.SessionClosing
import com.gd.rain.access.internal.usecase.SessionIssuer
import com.gd.rain.access.internal.usecase.SessionRetentionTask
import com.gd.rain.access.internal.usecase.SessionsQuery
import com.gd.rain.access.internal.usecase.SetSubjectPasswordUseCase
import com.gd.rain.access.internal.usecase.SignUpUseCase
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.access.internal.usecase.SubjectRegistryCheck
import com.gd.rain.access.internal.web.AccessAuthenticationFilter
import com.gd.rain.access.internal.web.AccessDeclarations
import com.gd.rain.access.internal.web.AccessEnforcementInterceptor
import com.gd.rain.access.internal.web.AccessFilterOrder
import com.gd.rain.access.internal.web.AccessPermissionCodes
import com.gd.rain.access.internal.web.AccessRequestPrincipal
import com.gd.rain.access.internal.web.AccessSecurityChain
import com.gd.rain.access.internal.web.AccessSurfaceVerifier
import com.gd.rain.access.internal.web.AuthController
import com.gd.rain.access.internal.web.CredentialCookies
import com.gd.rain.access.internal.web.CredentialSurface
import com.gd.rain.access.internal.web.CredentialThrottleFilter
import com.gd.rain.access.internal.web.JsonOnlyFilter
import com.gd.rain.access.internal.web.PageRequest
import com.gd.rain.access.internal.web.PermissionController
import com.gd.rain.access.internal.web.PresentedToken
import com.gd.rain.access.internal.web.PrincipalActor
import com.gd.rain.access.internal.web.RoleController
import com.gd.rain.access.internal.web.SignUpController
import com.gd.rain.access.internal.web.SubjectGrantController
import com.gd.rain.audit.AuditEventType
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.audit.autoconfigure.RainAuditAutoConfiguration
import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.observability.health.HealthCheck
import com.gd.rain.persistence.autoconfigure.RainPersistenceAutoConfiguration
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import com.gd.rain.web.limit.TokenBucketThrottle
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.MountsItsOwnSurface
import com.gd.rain.web.route.RequestPrincipal
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer
import io.github.resilience4j.common.bulkhead.configuration.CommonBulkheadConfigurationProperties
import jakarta.servlet.Filter
import org.jooq.DSLContext
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.function.support.RouterFunctionMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.Clock

/**
 * rain-access. Stores, use cases, the catalogue and the public [AccessProvisioning] and [GrantsLookup] in every process
 * with a database; the catalogue synchronised at start-up in the `api`, `worker` and `seeder` roles; session retention
 * and revocation replay as recurring work in the `worker` role; the security chain in every servlet application; the
 * routes, their enforcement, the surface verification and the credential gate in the `api` role.
 */
@AutoConfiguration(
    after = [
        JooqAutoConfiguration::class,
        RainRuntimeAutoConfiguration::class,
        RainPersistenceAutoConfiguration::class,
        RainAuditAutoConfiguration::class,
        RainResilienceAutoConfiguration::class,
        RainWebErrorAutoConfiguration::class,
    ],
    afterName = [
        RainAccessAutoConfiguration.BULKHEAD_AUTO_CONFIGURATION,
        RainAccessAutoConfiguration.DATA_REDIS_AUTO_CONFIGURATION,
    ],
    beforeName = [
        RainAccessAutoConfiguration.SERVLET_WEB_SECURITY_AUTO_CONFIGURATION,
        RainAccessAutoConfiguration.SECURITY_FILTER_AUTO_CONFIGURATION,
    ],
)
@ConditionalOnBean(DSLContext::class, PlatformTransactionManager::class, AuditRecorder::class)
@EnableConfigurationProperties(AccessProperties::class)
public class RainAccessAutoConfiguration {
    @Bean
    public fun accessErrorCodes(): ErrorCodeCatalog = AccessErrorCodes

    /** First: its failures carry a database failure as their cause, which the persistence translator would claim. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public fun accessFaultTranslator(): FaultTranslator = AccessFaultTranslator

    @Bean
    public fun accessSignedInEvent(): AuditEventType = AccessAuditTypes.SIGNED_IN

    @Bean
    public fun accessSignInFailedEvent(): AuditEventType = AccessAuditTypes.SIGN_IN_FAILED

    @Bean
    public fun accessLockoutOpenedEvent(): AuditEventType = AccessAuditTypes.LOCKOUT_OPENED

    @Bean
    public fun accessSignedUpEvent(): AuditEventType = AccessAuditTypes.SIGNED_UP

    @Bean
    public fun accessSignedOutEvent(): AuditEventType = AccessAuditTypes.SIGNED_OUT

    @Bean
    public fun accessSessionRevokedEvent(): AuditEventType = AccessAuditTypes.SESSION_REVOKED

    @Bean
    public fun accessSessionsClosedEvent(): AuditEventType = AccessAuditTypes.SESSIONS_CLOSED

    @Bean
    public fun accessPasswordChangedEvent(): AuditEventType = AccessAuditTypes.PASSWORD_CHANGED

    @Bean
    public fun accessPasswordEnrolledEvent(): AuditEventType = AccessAuditTypes.PASSWORD_ENROLLED

    @Bean
    public fun accessGrantChangedEvent(): AuditEventType = AccessAuditTypes.GRANT_CHANGED

    @Bean
    public fun accessRoleChangedEvent(): AuditEventType = AccessAuditTypes.ROLE_CHANGED

    @Bean
    public fun accessDefaultRoleChangedEvent(): AuditEventType = AccessAuditTypes.DEFAULT_ROLE_CHANGED

    @Bean
    public fun accessModuleGrants(): ModuleGrants = ModuleGrants(MODULE, AccessPermissionCodes.DECLARED)

    @Bean
    public fun accessSubjectRegistry(
        mounted: ObjectProvider<MountedSubject>,
        directories: ObjectProvider<SubjectDirectory>,
        registrars: ObjectProvider<SubjectRegistrar>,
    ): SubjectRegistry =
        SubjectRegistry(mounted.orderedStream().toList(), directories.orderedStream().toList(), registrars.orderedStream().toList())

    @Bean
    public fun accessSubjectRegistryCheck(registry: SubjectRegistry): ConfigurationCheck = SubjectRegistryCheck(registry)

    @Bean
    public fun accessGrantDeclarationsCheck(
        modules: ObjectProvider<ModuleGrants>,
        systemRoles: ObjectProvider<SystemRoleDeclaration>,
    ): ConfigurationCheck = GrantDeclarationsCheck(modules.orderedStream().toList(), systemRoles.orderedStream().toList())

    @Bean
    public fun accessSigningKey(
        properties: AccessProperties,
        stage: DeploymentStage,
    ): SigningKeyMaterial =
        when (val key = SigningKey.resolve(properties.token.signingKey, stage)) {
            is SigningKeyResolution.Resolved -> {
                SigningKeyMaterial(key.material)
            }

            is SigningKeyResolution.Refused -> {
                throw ConfigurationProblemsException(
                    listOf(ConfigurationProblem("${AccessProperties.PREFIX}.token.signing-key", ProblemCode.INVALID, key.problem)),
                )
            }
        }

    @Bean
    public fun accessTokenIssuer(
        key: SigningKeyMaterial,
        properties: AccessProperties,
    ): AccessTokenIssuer = AccessTokenIssuer(key.bytes(), properties.token.issuer, properties.token.audience, properties.token.accessTtl)

    @Bean
    public fun accessTokenVerifier(
        key: SigningKeyMaterial,
        properties: AccessProperties,
        clock: Clock,
    ): AccessTokenVerifier = AccessTokenVerifier(key.bytes(), properties.token.issuer, properties.token.audience, clock)

    @Bean
    public fun accessSessionFingerprints(key: SigningKeyMaterial): SessionFingerprints = SessionFingerprints(key.bytes())

    @Bean
    @ConditionalOnMissingBean
    public fun accessPasswordHasher(properties: AccessProperties): PasswordHasher = Argon2PasswordHasher(properties.hashing.argon2)

    @Bean
    public fun accessHashingBulkhead(
        registry: ObjectProvider<BulkheadRegistry>,
        properties: AccessProperties,
    ): HashingBulkhead =
        HashingBulkhead(
            {
                registry.getObject().find(AccessProperties.HASHING_BULKHEAD).orElseThrow {
                    IllegalStateException("no bulkhead instance ${AccessProperties.HASHING_BULKHEAD} is configured; start-up refuses that")
                }
            },
            properties.hashing.queue,
        )

    @Bean
    public fun accessHashingBulkheadCheck(
        properties: ObjectProvider<CommonBulkheadConfigurationProperties>,
        customizers: ObjectProvider<BulkheadConfigCustomizer>,
    ): ConfigurationCheck = HashingBulkheadCheck(properties.ifAvailable, customizers.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    public fun accessCredentialStore(dsl: DSLContext): CredentialStore = JooqCredentialStore(dsl)

    @Bean
    @ConditionalOnMissingBean
    public fun accessSessionStore(dsl: DSLContext): SessionStore = JooqSessionStore(dsl)

    @Bean
    @ConditionalOnMissingBean
    public fun accessGrantStore(dsl: DSLContext): GrantStore = JooqGrantStore(dsl)

    @Bean
    @ConditionalOnMissingBean
    public fun accessCatalogueStore(dsl: DSLContext): CatalogueStore = JooqCatalogueStore(dsl)

    @Bean
    public fun accessTransactions(transactions: PlatformTransactionManager): AccessTransactions = AccessTransactions(transactions)

    @Bean
    public fun accessAuditTrail(recorder: AuditRecorder): AuditTrail = AuditTrail(recorder)

    @Bean
    public fun accessPasswordRules(properties: AccessProperties): PasswordRules = PasswordRules(properties.password)

    @Bean
    public fun accessSessionIssuer(
        sessions: SessionStore,
        tokens: AccessTokenIssuer,
        ids: IdGenerator,
        clock: Clock,
        properties: AccessProperties,
    ): SessionIssuer = SessionIssuer(sessions, tokens, ids, clock, properties.session.ttl)

    @Bean
    public fun accessSessionClosing(
        sessions: SessionStore,
        revocations: RevocationList,
        transactions: AccessTransactions,
        properties: AccessProperties,
        clock: Clock,
    ): SessionClosing = SessionClosing(sessions, revocations, transactions, properties.session.revokeBatch, clock)

    @Bean
    @ConditionalOnProperty(prefix = "rain.access.revocation", name = ["store"], havingValue = "none")
    public fun accessNoRevocationList(): RevocationList = NoRevocationList

    @Bean
    @ConditionalOnProperty(prefix = "rain.access.attempts", name = ["store"], havingValue = "memory")
    public fun accessMemoryAttemptLimiter(
        properties: AccessProperties,
        clock: Clock,
    ): AttemptLimiter =
        MemoryAttemptLimiter(
            AttemptPolicy.of(properties.attempts),
            checkNotNull(properties.attempts.memory) { "rain.access.attempts.memory is validated as stated" }.maximumKeys,
            clock,
        )

    @Bean
    public fun accessLoginUseCase(
        credentials: CredentialStore,
        issuer: SessionIssuer,
        hasher: PasswordHasher,
        bulkhead: HashingBulkhead,
        limiter: AttemptLimiter,
        rules: PasswordRules,
        audit: AuditTrail,
        transactions: AccessTransactions,
        fingerprints: SessionFingerprints,
        properties: AccessProperties,
        clock: Clock,
    ): LoginUseCase =
        LoginUseCase(
            credentials,
            issuer,
            hasher,
            bulkhead,
            limiter,
            AttemptPolicy.of(properties.attempts),
            rules,
            audit,
            transactions,
            fingerprints,
            clock,
        )

    @Bean
    public fun accessSignUpUseCase(
        credentials: CredentialStore,
        grants: GrantStore,
        issuer: SessionIssuer,
        hasher: PasswordHasher,
        bulkhead: HashingBulkhead,
        rules: PasswordRules,
        audit: AuditTrail,
        transactions: AccessTransactions,
        ids: IdGenerator,
        clock: Clock,
    ): SignUpUseCase = SignUpUseCase(credentials, grants, issuer, hasher, bulkhead, rules, audit, transactions, ids, clock)

    @Bean
    public fun accessRefreshUseCase(
        sessions: SessionStore,
        issuer: SessionIssuer,
        subjects: SubjectRegistry,
        revocations: RevocationList,
        fingerprints: SessionFingerprints,
        properties: AccessProperties,
        clock: Clock,
    ): RefreshUseCase =
        RefreshUseCase(
            sessions,
            issuer,
            subjects,
            revocations,
            RefreshWindow(properties.session.refreshGrace, properties.session.idleTtl),
            properties.session.rotationAttempts,
            fingerprints,
            clock,
        )

    @Bean
    public fun accessLogoutUseCase(
        sessions: SessionStore,
        revocations: RevocationList,
        audit: AuditTrail,
        transactions: AccessTransactions,
        clock: Clock,
    ): LogoutUseCase = LogoutUseCase(sessions, revocations, audit, transactions, clock)

    @Bean
    public fun accessCloseEverywhereUseCase(
        closing: SessionClosing,
        audit: AuditTrail,
        transactions: AccessTransactions,
        clock: Clock,
    ): CloseEverywhereUseCase = CloseEverywhereUseCase(closing, audit, transactions, clock)

    @Bean
    public fun accessSessionsQuery(
        sessions: SessionStore,
        properties: AccessProperties,
        clock: Clock,
    ): SessionsQuery = SessionsQuery(sessions, properties.session.idleTtl, clock)

    @Bean
    public fun accessChangePasswordUseCase(
        credentials: CredentialStore,
        hasher: PasswordHasher,
        bulkhead: HashingBulkhead,
        limiter: AttemptLimiter,
        rules: PasswordRules,
        closing: SessionClosing,
        audit: AuditTrail,
        transactions: AccessTransactions,
        properties: AccessProperties,
        clock: Clock,
    ): ChangePasswordUseCase =
        ChangePasswordUseCase(
            credentials,
            hasher,
            bulkhead,
            limiter,
            AttemptPolicy.of(properties.attempts),
            rules,
            closing,
            properties.password.revokeOtherSessionsOnChange,
            audit,
            transactions,
            clock,
        )

    @Bean
    public fun accessSetSubjectPasswordUseCase(
        subjects: SubjectRegistry,
        credentials: CredentialStore,
        hasher: PasswordHasher,
        bulkhead: HashingBulkhead,
        rules: PasswordRules,
        closing: SessionClosing,
        audit: AuditTrail,
        transactions: AccessTransactions,
        ids: IdGenerator,
        clock: Clock,
    ): SetSubjectPasswordUseCase =
        SetSubjectPasswordUseCase(subjects, credentials, hasher, bulkhead, rules, closing, audit, transactions, ids, clock)

    @Bean
    public fun accessGrantsAdministration(
        grants: GrantStore,
        locks: AdvisoryLocks,
        audit: AuditTrail,
        transactions: AccessTransactions,
        properties: AccessProperties,
        clock: Clock,
    ): GrantsAdministration = GrantsAdministration(grants, locks, audit, transactions, properties.grants.maxRolesPerSubject, clock)

    @Bean
    public fun accessRoleAdministration(
        grants: GrantStore,
        audit: AuditTrail,
        transactions: AccessTransactions,
        ids: IdGenerator,
        properties: AccessProperties,
        clock: Clock,
    ): RoleAdministration = RoleAdministration(grants, audit, transactions, ids, properties.grants.roleDeletionBatch, clock)

    @Bean
    @ConditionalOnMissingBean
    public fun accessProvisioning(
        subjects: SubjectRegistry,
        credentials: CredentialStore,
        grants: GrantStore,
        administration: GrantsAdministration,
        hasher: PasswordHasher,
        bulkhead: HashingBulkhead,
        rules: PasswordRules,
        audit: AuditTrail,
        transactions: AccessTransactions,
        ids: IdGenerator,
        properties: AccessProperties,
        clock: Clock,
    ): AccessProvisioning =
        ProvisioningService(
            subjects,
            credentials,
            grants,
            administration,
            hasher,
            bulkhead,
            rules,
            audit,
            transactions,
            ids,
            properties.provisioning.holderPageSize,
            properties.provisioning.holderPageBudget,
            clock,
        )

    @Bean
    @ConditionalOnMissingBean
    public fun grantsLookup(
        subjects: SubjectRegistry,
        grants: GrantStore,
        properties: AccessProperties,
    ): GrantsLookup = GrantsService(subjects, grants, properties.web.page.maxSize)

    @Bean
    @ConditionalOnMissingBean(CurrentActor::class)
    public fun accessPrincipalActor(): CurrentActor = PrincipalActor

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRainRole(RuntimeRole.API, RuntimeRole.WORKER, RuntimeRole.SEEDER)
    public class Catalogue {
        @Bean
        public fun accessCatalogueSynchronizer(
            store: CatalogueStore,
            transactions: AccessTransactions,
            modules: ObjectProvider<ModuleGrants>,
            systemRoles: ObjectProvider<SystemRoleDeclaration>,
            ids: IdGenerator,
            properties: AccessProperties,
            clock: Clock,
        ): CatalogueSynchronizer =
            CatalogueSynchronizer(
                store,
                transactions,
                modules.orderedStream().toList(),
                systemRoles.orderedStream().toList(),
                ids,
                properties.catalogue.chunkSize,
                clock,
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRainRole(RuntimeRole.WORKER)
    public class Worker {
        @Bean
        public fun accessSessionRetention(
            sessions: SessionStore,
            properties: AccessProperties,
            clock: Clock,
        ): RecurringWork =
            SessionRetentionTask(
                sessions,
                properties.session.retention.keepFor,
                properties.session.ttl,
                properties.session.retention.interval,
                properties.session.retention.batch,
                properties.session.retention.batchesPerRun,
                clock,
            )
    }

    /**
     * Redis-backed stores; the client is the application's, and each store's connection factory is the one named or qualified
     * for it, or the application's one factory ([RedisConnectionChoice]).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [REDIS_CONNECTION_FACTORY])
    public class RedisStores {
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "rain.access.revocation", name = ["store"], havingValue = "redis")
        public class Revocation {
            @Bean
            public fun accessRevocationRedis(
                properties: AccessProperties,
                @Qualifier(REVOCATION_QUALIFIER) revocation: ObjectProvider<RedisConnectionFactory>,
                @Qualifier(ATTEMPTS_QUALIFIER) attempts: ObjectProvider<RedisConnectionFactory>,
                application: ObjectProvider<RedisConnectionFactory>,
                beans: ListableBeanFactory,
            ): AccessRedisConnection =
                AccessRedisConnection(
                    RedisConnectionChoice
                        .forStores(properties, revocation, attempts, application, factoryNames(beans))
                        .factoryOf(REVOCATION_QUALIFIER),
                )

            @Bean
            public fun accessRevocationList(
                redis: AccessRedisConnection,
                properties: AccessProperties,
                clock: Clock,
            ): RevocationList = RedisRevocationList(redis.template(), revocation(properties).keyPrefix, properties.token.accessTtl, clock)

            @Bean
            @ConditionalOnRainRole(RuntimeRole.API, RuntimeRole.WORKER)
            public fun accessRevocationServerCheck(
                redis: AccessRedisConnection,
                properties: AccessProperties,
            ): ConfigurationCheck =
                EvictionPolicyCheck(
                    "the revocation list",
                    "${AccessProperties.PREFIX}.revocation.redis",
                    "${AccessProperties.PREFIX}.revocation.redis.eviction-policy-attested",
                    revocation(properties).evictionPolicyAttested,
                    redis::factory,
                )

            @Bean
            @ConditionalOnRainRole(RuntimeRole.API, RuntimeRole.WORKER)
            public fun accessRevocationHealthCheck(redis: AccessRedisConnection): HealthCheck =
                RedisPingHealthCheck(REVOCATION_HEALTH, REVOCATION_HEALTH, redis.factory())

            @Bean
            @ConditionalOnRainRole(RuntimeRole.WORKER)
            public fun accessRevocationReplay(
                sessions: SessionStore,
                revocations: RevocationList,
                properties: AccessProperties,
                clock: Clock,
            ): RecurringWork = RevocationReplayTask(sessions, revocations, revocation(properties).replay, properties.token.accessTtl, clock)

            private fun revocation(properties: AccessProperties): AccessProperties.RedisRevocation =
                checkNotNull(properties.revocation.redis) { "rain.access.revocation.redis is validated as stated" }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "rain.access.attempts", name = ["store"], havingValue = "redis")
        public class Attempts {
            @Bean
            public fun accessAttemptsRedis(
                properties: AccessProperties,
                @Qualifier(REVOCATION_QUALIFIER) revocation: ObjectProvider<RedisConnectionFactory>,
                @Qualifier(ATTEMPTS_QUALIFIER) attempts: ObjectProvider<RedisConnectionFactory>,
                application: ObjectProvider<RedisConnectionFactory>,
                beans: ListableBeanFactory,
            ): AccessAttemptsRedisConnection =
                AccessAttemptsRedisConnection(
                    RedisConnectionChoice
                        .forStores(properties, revocation, attempts, application, factoryNames(beans))
                        .factoryOf(ATTEMPTS_QUALIFIER),
                )

            @Bean
            public fun accessRedisAttemptLimiter(
                redis: AccessAttemptsRedisConnection,
                properties: AccessProperties,
            ): AttemptLimiter = RedisAttemptLimiter(redis.template(), attempts(properties).keyPrefix, AttemptPolicy.of(properties.attempts))

            @Bean
            @ConditionalOnRainRole(RuntimeRole.API)
            public fun accessAttemptsServerCheck(
                redis: AccessAttemptsRedisConnection,
                properties: AccessProperties,
            ): ConfigurationCheck =
                EvictionPolicyCheck(
                    "the attempt counters",
                    "${AccessProperties.PREFIX}.attempts.redis",
                    "${AccessProperties.PREFIX}.attempts.redis.eviction-policy-attested",
                    attempts(properties).evictionPolicyAttested,
                    redis::factory,
                )

            @Bean
            @ConditionalOnRainRole(RuntimeRole.API)
            public fun accessAttemptsHealthCheck(redis: AccessAttemptsRedisConnection): HealthCheck =
                RedisPingHealthCheck(ATTEMPTS_HEALTH, ATTEMPTS_HEALTH, redis.factory())

            private fun attempts(properties: AccessProperties): AccessProperties.RedisAttempts =
                checkNotNull(properties.attempts.redis) { "rain.access.attempts.redis is validated as stated" }
        }

        private companion object {
            fun factoryNames(beans: ListableBeanFactory): List<String> =
                beans.getBeanNamesForType(RedisConnectionFactory::class.java).sorted()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass(REDIS_CONNECTION_FACTORY)
    public class RedisClientAbsent {
        @Bean
        public fun accessRedisClientCheck(properties: AccessProperties): ConfigurationCheck = RedisClientCheck(properties)

        @Bean
        @ConditionalOnProperty(prefix = "rain.access.revocation", name = ["store"], havingValue = "redis")
        public fun accessUnavailableRevocationList(): RevocationList = UnavailableRevocationList

        @Bean
        @ConditionalOnProperty(prefix = "rain.access.attempts", name = ["store"], havingValue = "redis")
        public fun accessUnavailableAttemptLimiter(): AttemptLimiter = UnavailableAttemptLimiter
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public class Web {
        /** The one chain in every servlet application with rain-access, so Spring Boot's default chain never applies. */
        @Bean
        public fun accessSecurityFilterChain(
            http: HttpSecurity,
            verifier: AccessTokenVerifier,
            subjects: SubjectRegistry,
            revocations: RevocationList,
            writer: ProblemWriter,
            properties: AccessProperties,
        ): SecurityFilterChain =
            AccessSecurityChain.build(
                http,
                AccessAuthenticationFilter(PresentedToken(properties.web.delivery), verifier, subjects, revocations),
                writer,
            )

        @Bean
        @ConditionalOnMissingBean
        public fun accessRequestPrincipal(): RequestPrincipal = AccessRequestPrincipal

        @Bean
        public fun accessErrorControllerExemption(): SurfaceExemption =
            SurfaceExemption(
                ErrorController::class.java,
                "the error dispatch renders the refusal of a request whatever that request declared",
            )

        @Bean
        public fun accessCredentialCookies(
            properties: AccessProperties,
            clock: Clock,
        ): CredentialCookies = CredentialCookies(properties.web.refreshPath, clock)

        @Bean
        public fun accessPageRequest(properties: AccessProperties): PageRequest = PageRequest(properties.web.page)

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnRainRole(RuntimeRole.API)
        public class Api {
            @Bean
            public fun accessAuthController(
                subjects: SubjectRegistry,
                login: LoginUseCase,
                refresh: RefreshUseCase,
                logout: LogoutUseCase,
                everywhere: CloseEverywhereUseCase,
                passwords: ChangePasswordUseCase,
                sessions: SessionsQuery,
                grants: GrantsLookup,
                cookies: CredentialCookies,
                properties: AccessProperties,
                pages: PageRequest,
            ): AuthController =
                AuthController(
                    subjects,
                    login,
                    refresh,
                    logout,
                    everywhere,
                    passwords,
                    sessions,
                    grants,
                    cookies,
                    properties.web.delivery,
                    pages,
                )

            /** Sign-up routes exist only in an application with a registrar. */
            @Bean
            @ConditionalOnBean(SubjectRegistrar::class)
            public fun accessSignUpController(
                subjects: SubjectRegistry,
                signUp: SignUpUseCase,
                cookies: CredentialCookies,
                properties: AccessProperties,
                pages: PageRequest,
            ): SignUpController = SignUpController(subjects, signUp, cookies, properties.web.delivery, pages)

            @Bean
            public fun accessRoleController(
                roles: RoleAdministration,
                pages: PageRequest,
                properties: AccessProperties,
            ): RoleController = RoleController(roles, pages, properties.web.maxBulkIds)

            @Bean
            public fun accessPermissionController(
                roles: RoleAdministration,
                pages: PageRequest,
            ): PermissionController = PermissionController(roles, pages)

            @Bean
            public fun accessSubjectGrantController(
                subjects: SubjectRegistry,
                grants: GrantsAdministration,
                passwords: SetSubjectPasswordUseCase,
                pages: PageRequest,
            ): SubjectGrantController = SubjectGrantController(subjects, grants, passwords, pages)

            @Bean
            public fun accessDeclarations(exemptions: ObjectProvider<SurfaceExemption>): AccessDeclarations =
                AccessDeclarations(exemptions.orderedStream().toList())

            @Bean
            public fun accessEnforcementInterceptor(
                declarations: AccessDeclarations,
                grants: GrantsLookup,
                selfMounted: ObjectProvider<MountsItsOwnSurface>,
            ): AccessEnforcementInterceptor =
                AccessEnforcementInterceptor(declarations, grants) {
                    selfMounted
                        .orderedStream()
                        .toList()
                        .flatMap(MountsItsOwnSurface::mountedDeclarations)
                        .associateBy(com.gd.rain.web.route.EndpointDeclaration::key)
                }

            @Bean
            public fun accessWebMvcConfigurer(interceptor: AccessEnforcementInterceptor): WebMvcConfigurer =
                object : WebMvcConfigurer {
                    override fun addInterceptors(registry: InterceptorRegistry) {
                        registry.addInterceptor(interceptor)
                    }
                }

            @Bean
            public fun accessSurfaceVerifier(
                context: ApplicationContext,
                declarations: AccessDeclarations,
                resources: ObjectProvider<DeclaresItsOwnAccess>,
                selfMounted: ObjectProvider<MountsItsOwnSurface>,
                modules: ObjectProvider<ModuleGrants>,
            ): ConfigurationCheck =
                AccessSurfaceVerifier(
                    {
                        if (context.containsBean(MVC_MAPPING)) {
                            context.getBean(MVC_MAPPING, RequestMappingHandlerMapping::class.java)
                        } else {
                            null
                        }
                    },
                    {
                        if (context.containsBean(ROUTER_MAPPING)) {
                            context.getBean(ROUTER_MAPPING, RouterFunctionMapping::class.java).routerFunction
                        } else {
                            null
                        }
                    },
                    declarations,
                    resources.orderedStream().toList(),
                    selfMounted.orderedStream().toList(),
                    {
                        modules
                            .orderedStream()
                            .toList()
                            .flatMap { module -> module.permissions.map { it.code } }
                            .toSet()
                    },
                )

            @Bean
            public fun accessJsonOnlyFilterRegistration(
                properties: AccessProperties,
                writer: ProblemWriter,
            ): FilterRegistrationBean<JsonOnlyFilter> =
                registration(
                    "rainAccessJsonOnlyFilter",
                    JsonOnlyFilter(CredentialSurface(properties.web.authPath), writer),
                    AccessFilterOrder.JSON_ONLY,
                )

            @Bean
            public fun accessCredentialThrottleFilterRegistration(
                properties: AccessProperties,
                writer: ProblemWriter,
            ): FilterRegistrationBean<CredentialThrottleFilter> {
                val throttle = properties.gate.throttle
                return registration(
                    "rainAccessCredentialThrottleFilter",
                    CredentialThrottleFilter(
                        CredentialSurface(properties.web.authPath),
                        TokenBucketThrottle(throttle.perMinute, throttle.burst, throttle.callers),
                        writer,
                    ),
                    AccessFilterOrder.CREDENTIAL_THROTTLE,
                )
            }
        }
    }

    public companion object {
        public const val MODULE: String = "access"

        /** The bean name or qualifier of a connection factory dedicated to the revocation list. */
        public const val REVOCATION_QUALIFIER: String = RedisQualifiers.REVOCATION

        /** The bean name or qualifier of a connection factory dedicated to the attempt counters. */
        public const val ATTEMPTS_QUALIFIER: String = RedisQualifiers.ATTEMPTS

        public const val REVOCATION_HEALTH: String = "access.revocation"
        public const val ATTEMPTS_HEALTH: String = "access.attempts"

        public const val BULKHEAD_AUTO_CONFIGURATION: String =
            "io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration"
        public const val DATA_REDIS_AUTO_CONFIGURATION: String =
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
        public const val SERVLET_WEB_SECURITY_AUTO_CONFIGURATION: String =
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
        public const val SECURITY_FILTER_AUTO_CONFIGURATION: String =
            "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration"
        public const val REDIS_CONNECTION_FACTORY: String = "org.springframework.data.redis.connection.RedisConnectionFactory"

        private const val MVC_MAPPING = "requestMappingHandlerMapping"

        /** The bean Spring MVC registers for functional routes. */

        private const val ROUTER_MAPPING: String = "routerFunctionMapping"

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
    }
}

/** The connection factory the revocation list uses, and a template over it. */
public class AccessRedisConnection(
    private val connections: RedisConnectionFactory,
) {
    public fun factory(): RedisConnectionFactory = connections

    public fun template(): StringRedisTemplate = StringRedisTemplate(connections)
}

/** The connection factory the attempt counters use, and a template over it. */
public class AccessAttemptsRedisConnection(
    private val connections: RedisConnectionFactory,
) {
    public fun factory(): RedisConnectionFactory = connections

    public fun template(): StringRedisTemplate = StringRedisTemplate(connections)
}
