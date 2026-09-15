# Port inventory

Every file rain was built from, where it went, and its status. Source: `kotlin/tmp/project` at commit `befbcc9` (`backend/` paths are relative to `src/main/kotlin/com/gd/framework` for main, `src/test/kotlin/com/gd/framework` for test, `src/test/kotlin/com/gd/lease/integration` for lease-it).

Status: `pending` → `ported` / `dropped` / `split`. `kotlin/tmp` may be deleted only when no row is `pending`.

| Kind | Source | Destination | Status | Note |
|---|---|---|---|---|
| main | `FrameworkPackages.kt` | dropped | dropped | no package scanning in rain |
| main | `access/AccessFaults.kt` | rain-access | pending |  |
| main | `access/AccessFieldNames.kt` | rain-access | pending |  |
| main | `access/ModuleMetadata.kt` | rain-access | pending |  |
| main | `access/SubjectRegistry.kt` | rain-access | pending |  |
| main | `access/config/AccessConfiguration.kt` | rain-access | pending |  |
| main | `access/config/AccessDeclarationConfiguration.kt` | rain-access | pending |  |
| main | `access/config/AccessSurfaceConfiguration.kt` | rain-access | pending |  |
| main | `access/config/DirectoryConfiguration.kt` | rain-access | pending |  |
| main | `access/domain/Credential.kt` | rain-access | pending |  |
| main | `access/domain/Permission.kt` | rain-access | pending |  |
| main | `access/domain/Role.kt` | rain-access | pending |  |
| main | `access/domain/RolePermission.kt` | rain-access | pending |  |
| main | `access/domain/Session.kt` | rain-access | pending |  |
| main | `access/domain/SubjectDefaultRole.kt` | rain-access | pending |  |
| main | `access/domain/SubjectPermission.kt` | rain-access | pending |  |
| main | `access/domain/SubjectRole.kt` | rain-access | pending |  |
| main | `access/protection/AttemptLimiter.kt` | rain-access | pending |  |
| main | `access/protection/BulkheadPasswordEncoder.kt` | rain-access | pending |  |
| main | `access/protection/MemoryAttemptLimiter.kt` | rain-access | pending |  |
| main | `access/protection/SignInAttemptsObserver.kt` | rain-access | pending |  |
| main | `access/revoke/EvictionPolicy.kt` | rain-access | pending |  |
| main | `access/revoke/RedisRevocationList.kt` | rain-access | pending |  |
| main | `access/revoke/RevocationEvictionVerifier.kt` | rain-access | pending |  |
| main | `access/revoke/RevocationList.kt` | rain-access | pending |  |
| main | `access/security/AccessAuthenticationFilter.kt` | rain-access | pending |  |
| main | `access/security/AccessAuthorizationManager.kt` | rain-access | pending |  |
| main | `access/security/AccessDeniedRenderer.kt` | rain-access | pending |  |
| main | `access/security/AccessSurface.kt` | rain-access | pending |  |
| main | `access/security/AccessSurfaceVerifier.kt` | rain-access | pending |  |
| main | `access/security/AuthEntryPoint.kt` | rain-access | pending |  |
| main | `access/security/CredentialConverter.kt` | rain-access | pending |  |
| main | `access/security/PrincipalActor.kt` | rain-access | pending |  |
| main | `access/security/SecurityChainConfiguration.kt` | rain-access | pending |  |
| main | `access/spi/AccessPrincipal.kt` | rain-access | pending |  |
| main | `access/spi/Grants.kt` | rain-access | pending |  |
| main | `access/spi/Registration.kt` | rain-access | pending |  |
| main | `access/spi/SpiMetadata.kt` | rain-access | pending |  |
| main | `access/spi/SubjectCredentials.kt` | rain-access | pending |  |
| main | `access/spi/Subjects.kt` | rain-access | pending |  |
| main | `access/store/AccessCatalog.kt` | rain-access | pending |  |
| main | `access/store/AccessRepositories.kt` | rain-access | pending |  |
| main | `access/store/AccessRepositoryFragments.kt` | rain-access | pending |  |
| main | `access/token/AccessTokenIssuer.kt` | rain-access | pending |  |
| main | `access/token/AccessTokenVerifier.kt` | rain-access | pending |  |
| main | `access/token/PasswordEncoders.kt` | rain-access | pending |  |
| main | `access/token/RefreshCredential.kt` | rain-access | pending |  |
| main | `access/token/RotationClassifier.kt` | rain-access | pending |  |
| main | `access/usecase/AccessCatalogSynchronizer.kt` | rain-access | pending |  |
| main | `access/usecase/AccessSeeder.kt` | rain-access | pending |  |
| main | `access/usecase/AuthResponse.kt` | rain-access | pending |  |
| main | `access/usecase/ChangePasswordUseCase.kt` | rain-access | pending |  |
| main | `access/usecase/GrantsResolver.kt` | rain-access | pending |  |
| main | `access/usecase/LoginUseCase.kt` | rain-access | pending |  |
| main | `access/usecase/OwnedTransactions.kt` | rain-access | pending |  |
| main | `access/usecase/PasswordCredentials.kt` | rain-access | pending |  |
| main | `access/usecase/RevocationAnnouncer.kt` | rain-access | pending |  |
| main | `access/usecase/RotateRefreshUseCase.kt` | rain-access | pending |  |
| main | `access/usecase/SessionIssuer.kt` | rain-access | pending |  |
| main | `access/usecase/SessionUseCases.kt` | rain-access | pending |  |
| main | `access/usecase/SetSubjectPasswordUseCase.kt` | rain-access | pending |  |
| main | `access/usecase/SignUpUseCase.kt` | rain-access | pending |  |
| main | `access/usecase/SubjectGrantsUseCase.kt` | rain-access | pending |  |
| main | `access/web/AuthController.kt` | rain-access | pending |  |
| main | `access/web/AuthCrossSiteFilter.kt` | rain-access | pending |  |
| main | `access/web/AuthCrossSiteGuard.kt` | rain-access | pending |  |
| main | `access/web/AuthTransportConfiguration.kt` | rain-access | pending |  |
| main | `access/web/AuthViews.kt` | rain-access | pending |  |
| main | `access/web/CredentialDelivery.kt` | rain-access | pending |  |
| main | `access/web/DirectoryResources.kt` | rain-access | pending |  |
| main | `access/web/RoleController.kt` | rain-access | pending |  |
| main | `access/web/RoleService.kt` | rain-access | pending |  |
| main | `access/web/SubjectGrantController.kt` | rain-access | pending |  |
| main | `audit/AccessAudit.kt` | rain-access | pending | generic access events; product `userChanged` dropped |
| main | `audit/AuditConfiguration.kt` | rain-audit | ported | RainAuditAutoConfiguration |
| main | `audit/AuditDetail.kt` | rain-audit | ported | bounded scalar detail checked against the declared type, deterministic JSON (gap 21 part) |
| main | `audit/AuditEvent.kt` | rain-audit | ported | AuditEventType (declared detail keys) + AuditEvent + AuditOutcome |
| main | `audit/AuditLogRepository.kt` | rain-audit | ported | schema rain_audit via rain.jooq-schema; actor (type, id) with no foreign key (gap 17 part) |
| main | `audit/AuditRecorder.kt` | rain-audit | ported | JooqAuditRecorder: record inside the caller's transaction (refused outside), recordIndependently in its own; keyset reads with a bounded page |
| main | `audit/SignInAuditRecorder.kt` | rain-access | pending | breaks the access↔audit cycle |
| main | `cli/OneShotReports.kt` | split | pending | LlmSmokeCheck → rain-llm; RedlineReport dropped (product) |
| main | `cli/SeedStep.kt` | rain-boot | ported | rain-boot Seeder + seed command (ordered by order then name, names checked) |
| main | `config/AccessProperties.kt` | rain-access | pending |  |
| main | `config/AppProperties.kt` | rain-boot | dropped | spring.application.name is required instead; shutdown budget moves to rain-jobs with spring.lifecycle |
| main | `config/CasebankProperties.kt` | dropped | pending | product configuration |
| main | `config/ClientProperties.kt` | split | pending | LlmProperties → rain-llm; BreakerProperties → rain-resilience (r4j config); WpsProperties dropped |
| main | `config/ConfigurationBinding.kt` | rain-boot | ported | rebuilt as rain-boot SectionBinder (missing leaves, unknown keys, file-borne secrets) |
| main | `config/ConfigurationProblems.kt` | rain-core | ported | typed ConfigurationProblem + ConfigurationProblemsException in rain-core |
| main | `config/ConfigurationPropertiesWiring.kt` | dropped | dropped | @EnableConfigurationProperties per auto-configuration |
| main | `config/CrossCuttingConfigurationValidator.kt` | rain-boot | ported | rebuilt as rain-boot RainConfigurationValidator over the open ConfigurationContributor SPI (gaps 37, 41, 45) |
| main | `config/DataSizeFormat.kt` | rain-core | ported | rain-boot config/Formats.kt |
| main | `config/DeploymentConfiguration.kt` | dropped | dropped | closed product tree replaced by ConfigurationContributor sections |
| main | `config/DeploymentStage.kt` | rain-boot | ported | required rain.deployment.stage, no profile inference (gap 36) |
| main | `config/DurationFormat.kt` | rain-core | ported | rain-boot config/Formats.kt |
| main | `config/HttpProperties.kt` | rain-web | ported | rain-web RainWebProperties + RainWebConfigurationContributor (rain.web, required) |
| main | `config/JobsProperties.kt` | rain-jobs | ported | rain.jobs JobsProperties; DEFAULT_WORKERS dropped |
| main | `config/RealtimeProperties.kt` | rain-realtime | ported | rain.realtime; pool-name, subscriber-buffer, max-subscriptions required; contributor OPTIONAL |
| main | `config/RedisProperties.kt` | dropped | pending | Boot spring.data.redis.*; revocation settings → rain-access |
| main | `config/ResourceDeclarations.kt` | dropped | pending | replaced by revocation server eviction-policy check (gap 14) |
| main | `config/SeedProperties.kt` | dropped | pending | product configuration; prod secret rule → @RequiredFromEnvironment (gap 37) |
| main | `config/SigningKey.kt` | rain-access | pending | entropy heuristic removed (gap 21) |
| main | `config/StorageProperties.kt` | dropped | pending | product configuration |
| main | `config/WorkspaceProperties.kt` | dropped | pending | product configuration |
| main | `crud/BulkDeleteRequest.kt` | rain-crud | ported | CrudResource.bulkDelete + CrudMvc.bulkDelete (strict {"ids":[…]}) |
| main | `crud/CrudAccessTable.kt` | rain-crud | ported | web/MountedResource.declarations derived from the policy |
| main | `crud/CrudConfiguration.kt` | rain-crud | ported | autoconfigure/RainCrudAutoConfiguration; SpEL accessPolicies bean not ported |
| main | `crud/CrudMounted.kt` | rain-crud | dropped | Go body-reader parity marker; rain renders malformed_body uniformly |
| main | `crud/CrudResource.kt` | rain-crud | ported | CrudResource (authorize, scope, keyset pages, capped count) |
| main | `crud/CrudRouteRegistrar.kt` | rain-crud | ported | web/MountedResource + CrudRoute |
| main | `crud/CrudStore.kt` | rain-crud | ported | CrudStore SPI with required RowScope; GuardedResource folded into CrudResource |
| main | `crud/Operations.kt` | rain-crud | ported | Action + web/CrudOperation; RESTORE, QUERY, COUNT_QUERY, CrudRules dropped |
| main | `crud/PaginatedResponse.kt` | rain-crud | ported | Page/PageWindow/CappedCount + web PageBody v1; RestoredResponse dropped |
| main | `crud/ResourcePolicy.kt` | rain-crud | ported | ResourcePolicy/ActionAccess/ScopeRule + CallerLookup SPI; SecurityContextHolder removed |
| main | `crud/persistence/JooqResourceStore.kt` | rain-crud | ported | persistence/JooqResourceStore |
| main | `crud/persistence/QueryStatements.kt` | rain-crud | ported | merged into JooqResourceStore; Cursors -> query/CursorCodec v1 |
| main | `crud/persistence/Row.kt` | rain-crud | ported | persistence/Row exact labels, required* + RowShapeException |
| main | `crud/query/Coerce.kt` | rain-crud | ported | query/WireValues (one spelling per kind) |
| main | `crud/query/FieldFold.kt` | rain-crud | dropped | fold matching removed; FieldGrant replaces allowed() |
| main | `crud/query/FilterNode.kt` | rain-crud | ported | query/Predicate + Operator (v1 set) + Order |
| main | `crud/query/QueryCompiler.kt` | rain-crud | ported | query/QueryCompiler |
| main | `crud/query/QueryConfig.kt` | rain-crud | ported | query/QueryRules + Pagination + QueryLimits |
| main | `crud/query/QueryDialectParser.kt` | rain-crud | ported | query/DialectV1 |
| main | `crud/query/QueryDocument.kt` | rain-crud | dropped | JSON query document not part of dialect v1 |
| main | `crud/query/QueryRequest.kt` | rain-crud | ported | query/QueryParameters + ListPlan/Window |
| main | `crud/query/ResourceSchema.kt` | rain-crud | ported | query/ResourceSchema (schema-qualified TableName) |
| main | `http/Access.kt` | rain-web | ported | rain-web com.gd.rain.web.route (Access, EndpointDeclaration, DeclaresItsOwnAccess, MountsItsOwnSurface) |
| main | `http/ApiPaths.kt` | dropped | dropped | product constant |
| main | `http/CrossOriginFilter.kt` | rain-web | dropped | Spring CorsFilter + ProblemCorsProcessor over rain.web.cors |
| main | `http/RequestLogConfiguration.kt` | rain-web | ported | RainWebFilterAutoConfiguration |
| main | `http/RequestLogFilter.kt` | rain-web | ported | rain-web RequestLogFilter + CorrelationId rule; RequestPrincipal SPI in web.route |
| main | `http/RootProbeController.kt` | rain-web | ported | rain-web ProbeController (rain.web.probes.*); / and favicon dropped |
| main | `http/SocketDeadlines.kt` | rain-web | dropped | replaced by Boot server.tomcat.* settings |
| main | `http/TomcatDeadlineCustomizer.kt` | rain-web | dropped | replaced by Boot server.tomcat.* settings |
| main | `http/TransportBodyLimits.kt` | rain-web | dropped | replaced by server.tomcat.* and spring.servlet.multipart.*; int bound validated on rain.web.body-limit (gap 32) |
| main | `http/TransportConfiguration.kt` | rain-web | ported | RainWebFilterAutoConfiguration with WebFilterOrder |
| main | `http/error/EnvelopeErrorController.kt` | rain-web | ported | rain-web RainErrorController |
| main | `http/error/EnvelopeWriter.kt` | rain-web | ported | rain-web ProblemRenderer + ProblemWriter |
| main | `http/error/ErrorCodes.kt` | rain-core | ported | RainErrorCodes catalog + ErrorCodeRegistry (duplicates refused); DOMAIN codes dropped (gap 35) |
| main | `http/error/ErrorEnvelope.kt` | rain-web | ported | RFC 9457 problem format v1 (ProblemFormat) |
| main | `http/error/ErrorEnvelopeAdvice.kt` | rain-web | ported | rain-web RainExceptionHandler + StatusTable (gaps 30, 31) |
| main | `http/error/Fault.kt` | rain-core | ported | rain-core Fault with typed ErrorCode and construction invariants |
| main | `http/error/FaultKind.kt` | rain-core | ported | status as Int, total kind table (gap 31 core half) |
| main | `http/error/Groups.kt` | rain-core | dropped | validation/general grouping replaced by the problem+json errors list |
| main | `http/error/StaleDocumentBody.kt` | dropped | dropped | product body |
| main | `http/error/Violation.kt` | rain-core | ported | RFC 6901 pointer path, deterministic order |
| main | `http/filter/BodyLimitFilter.kt` | rain-web | ported | rain-web BodyLimitFilter |
| main | `http/filter/CrossSiteFilter.kt` | rain-web | ported | rain-web CrossSiteFilter (cross_site) |
| main | `http/filter/RequestBudgetFilter.kt` | rain-web | ported | rain-web RequestBudgetFilter + RequestBudgetTimer (gap 30) |
| main | `http/filter/RequestDeadline.kt` | rain-web | ported | rain-web RequestDeadline |
| main | `http/filter/SafeMethods.kt` | rain-web | ported | rain-web filter/Requests.kt |
| main | `http/filter/SecurityHeadersFilter.kt` | rain-web | ported | rain-web SecurityHeadersFilter, configurable (gap 34) |
| main | `http/limit/CallerTable.kt` | rain-web | ported | rain-web CallerTable, refusedBecauseFull counter (gap 33) |
| main | `http/limit/CredentialGate.kt` | rain-access | pending |  |
| main | `http/limit/CredentialGateFilter.kt` | rain-access | pending |  |
| main | `http/limit/JsonOnlyFilter.kt` | rain-web | pending |  |
| main | `http/limit/TokenBucketThrottle.kt` | rain-web | ported | rain-web TokenBucketThrottle |
| main | `llm/LlmConfiguration.kt` | rain-llm | pending |  |
| main | `llm/LlmErrors.kt` | rain-llm | pending |  |
| main | `llm/LlmGateway.kt` | rain-llm | pending |  |
| main | `llm/LlmSettings.kt` | rain-llm | pending |  |
| main | `llm/LlmSlotStore.kt` | rain-llm | pending |  |
| main | `llm/LlmSlots.kt` | rain-llm | pending |  |
| main | `llm/LlmSmokeProbe.kt` | rain-llm | pending |  |
| main | `llm/Utf8.kt` | rain-llm | pending |  |
| main | `llm/persistence/LlmSlotRepository.kt` | rain-llm | pending |  |
| main | `lock/AdvisoryLockStore.kt` | rain-persistence | ported | rain-persistence lock |
| main | `lock/AdvisoryLocks.kt` | rain-persistence | ported | rain-persistence lock |
| main | `lock/LockConfiguration.kt` | rain-persistence | ported | RainPersistenceAutoConfiguration |
| main | `lock/LockKey.kt` | rain-persistence | ported | rain-core lock key derivation (FNV-1a 64) and guards |
| main | `lock/LockProperties.kt` | rain-persistence | ported | rain.locks section |
| main | `lock/persistence/AdvisoryLockRepository.kt` | rain-persistence | ported | JooqAdvisoryLockStore |
| main | `observability/LoggingConfiguration.kt` | rain-observability | ported | rain-observability RainOpenTelemetryLoggingAutoConfiguration |
| main | `observability/health/ActuatorHealthContributors.kt` | rain-observability | ported | rain-observability ActuatorHealthBridge (gap 44) |
| main | `observability/health/DatabaseHealthIndicator.kt` | rain-persistence | ported | rain-observability DatabaseHealthCheck, importance from rain.health.checks.database |
| main | `observability/health/HealthCache.kt` | rain-observability | ported | rain-observability HealthCache |
| main | `observability/health/HealthConfiguration.kt` | rain-observability | ported | RainHealthAutoConfiguration + RainActuatorHealthAutoConfiguration |
| main | `observability/health/HealthModel.kt` | rain-observability | ported | rain-observability HealthModel (readiness contract) |
| main | `observability/health/HealthRegistry.kt` | rain-observability | ported | rain-observability HealthRegistry (gap 44) |
| main | `persistence/AssignIdCallback.kt` | rain-data-jdbc | ported | rain-data-jdbc |
| main | `persistence/ConnectionBudget.kt` | dropped | dropped | replaced by ConnectionDemandCheck in rain-jobs |
| main | `persistence/CurrentActor.kt` | rain-persistence | ported | rain-core Actor(type, id) + CurrentActor |
| main | `persistence/DataAccessFaults.kt` | rain-persistence | ported | DataAccessFaultTranslator over the rain-core FaultTranslator SPI; 57014 statement_timeout, 42P01/42703 internal, cause and nextException walked (gap 42) |
| main | `persistence/Ids.kt` | rain-persistence | ported | IdGenerator in rain-core, UuidV7Ids in rain-persistence |
| main | `persistence/JsonbPayload.kt` | rain-persistence | ported | marker in rain-persistence, converters in rain-data-jdbc |
| main | `persistence/LanguageCodes.kt` | dropped | pending | product converter; JdbcConversionContribution instead |
| main | `persistence/OffsetDateTimeToInstantConverter.kt` | rain-data-jdbc | ported | rain-data-jdbc |
| main | `persistence/PersistenceConfiguration.kt` | rain-persistence | ported | RainPersistenceAutoConfiguration, RainSchemaAutoConfiguration, RainDataJdbcAutoConfiguration; product converters and the auditing handler are not ported (applications use @EnableJdbcAuditing) |
| main | `persistence/RealtimeDataSource.kt` | rain-realtime | ported | internal HikariListenerConnections; pool name from config; evictConnection on failure |
| main | `persistence/RecordingFlywayMigration.kt` | rain-persistence | ported | RainSchemaMigrationStrategy (schema per module, own history) + migrate command |
| main | `persistence/StatementBudget.kt` | rain-persistence | ported | StatementTimeout for jOOQ and every JdbcTemplate, one key, agreement check with Boot's (gap 43) |
| main | `persistence/TransactionRetry.kt` | rain-persistence | ported | Spring Framework 7 RetryTemplate with a SQLState predicate (gap 46) |
| main | `persistence/WireEnum.kt` | rain-persistence | ported | marker in rain-persistence, converters in rain-data-jdbc |
| main | `realtime/Channel.kt` | rain-realtime | ported | Channel.parse replaces ofOrNull; NotifyRules v1 checked against server |
| main | `realtime/RealtimeConfiguration.kt` | rain-realtime | ported | RainRealtimeAutoConfiguration; section condition; listener under @ConditionalOnRainRole(API) |
| main | `realtime/RealtimeFault.kt` | rain-realtime | ported | split into SubscriptionEnd, PublishRefusal, RealtimeErrorCodes faults, ListenerStartRefused |
| main | `realtime/RealtimeListener.kt` | rain-realtime | ported | SmartLifecycle; UNLISTEN * per session; unsolicited counter; max-subscriptions |
| main | `realtime/RealtimePublisher.kt` | rain-realtime | ported | bounded byte count; surrogate and NUL refused; transaction required |
| main | `realtime/Subscription.kt` | rain-realtime | ported | END sentinel, buffer+1 queue, poll returns Next |
| main | `redis/RedisClients.kt` | dropped | pending | Boot spring.data.redis.* (gap 14) |
| main | `resilience/AdmissionGate.kt` | rain-resilience | pending |  |
| main | `resilience/AuthBulkheadConfiguration.kt` | rain-access | pending |  |
| main | `resilience/BreakerHealthContribution.kt` | rain-resilience | pending |  |
| main | `resilience/BreakerRegistry.kt` | rain-resilience | pending |  |
| main | `resilience/Breakers.kt` | rain-resilience | pending |  |
| main | `workq/AttemptStatementTimeout.kt` | rain-jobs | ported | AttemptStatementTimeout; failure charged, not swallowed |
| main | `workq/DefinitionGate.kt` | rain-jobs | ported | DefinitionGate; slot left by attempt thread |
| main | `workq/FencedEffects.kt` | rain-jobs | ported | FencedEffects; guards list, bound min(step, deadline-now) |
| main | `workq/Housekeeping.kt` | rain-jobs | ported | JobReaper, JobRetention, JobAdministration; cancelByContract/DOCUMENT_SCOPED dropped |
| main | `workq/JobDefinition.kt` | rain-jobs | ported | JobDefinition bean; JobCatalog constants dropped |
| main | `workq/JobProfile.kt` | rain-jobs | ported | JobProfile bean, BackoffLadder; enum constants dropped |
| main | `workq/Jobs.kt` | rain-jobs | ported | WorkQueue, EnqueueOptions, Attempt, JobHandler, JobState |
| main | `workq/SchedulerWorkQueue.kt` | rain-jobs | ported | SchedulerWorkQueue; intent before insert |
| main | `workq/WorkqConfiguration.kt` | rain-jobs | ported | RainJobsAutoConfiguration, role-gated worker |
| main | `workq/WorkqExecutionHandler.kt` | rain-jobs | ported | JobExecution, AttemptThreads, LeasedAttempt, LeaseRenewer |
| main | `workq/WorkqSchedulers.kt` | rain-jobs | ported | JobsWorker lifecycle, DbSchedulerFactory, JobTopology checks |
| main | `workq/persistence/JobIntentRepository.kt` | rain-jobs | ported | IntentLedger |
| main | `workq/persistence/JobInvocationRepository.kt` | rain-jobs | ported | AttemptLedger, HousekeepingLedger, AdministrationLedger |
| test | `FrameworkBoundaryTest.kt` | dropped | pending | Lease boundary; replaced by RainArchRules |
| test | `access/AccessFieldNamesTest.kt` | rain-access | pending |  |
| test | `access/PermissionCatalogueTest.kt` | rain-access | pending |  |
| test | `access/protection/MemoryAttemptLimiterTest.kt` | rain-access | pending |  |
| test | `access/protection/SignInAttemptsObserverTest.kt` | rain-access | pending |  |
| test | `access/revoke/EvictionPolicyTest.kt` | rain-access | pending |  |
| test | `access/revoke/RevocationListTest.kt` | rain-access | pending |  |
| test | `access/security/AccessSurfaceVerifierTest.kt` | rain-access | pending |  |
| test | `access/security/CredentialConverterTest.kt` | rain-access | pending |  |
| test | `access/security/SecurityDefaultsTest.kt` | rain-access | pending |  |
| test | `access/security/SecurityRefusalTest.kt` | rain-access | pending |  |
| test | `access/token/AccessTokenTest.kt` | rain-access | pending |  |
| test | `access/token/PasswordEncoderCompatibilityTest.kt` | rain-access | pending |  |
| test | `access/token/RefreshCredentialTest.kt` | rain-access | pending |  |
| test | `access/token/RotationClassifierTest.kt` | rain-access | pending |  |
| test | `access/usecase/AccessCatalogSynchronizerTest.kt` | rain-access | pending |  |
| test | `access/usecase/AccessSeederTest.kt` | rain-access | pending |  |
| test | `access/web/AuthCrossSiteGuardTest.kt` | rain-access | pending |  |
| test | `access/web/AuthViewsSerializationTest.kt` | rain-access | pending |  |
| test | `access/web/CredentialDeliveryTest.kt` | rain-access | pending |  |
| test | `access/web/RoleServiceTest.kt` | rain-access | pending |  |
| test | `audit/AuditDetailTest.kt` | rain-audit | ported | rain-audit AuditDetailTest |
| test | `audit/AuditRecorderTest.kt` | rain-audit | ported | rain-audit AuditIT (transaction semantics against PostgreSQL) |
| test | `audit/SignInAuditRecorderTest.kt` | rain-audit | pending |  |
| test | `config/AccessConfigurationTest.kt` | rain-access | pending |  |
| test | `config/ConfigFileParityTest.kt` | dropped | pending | Lease deployment files |
| test | `config/ConfigurationBindingTest.kt` | split | pending | split: stage/body/budget/llm parts → rain modules; workspace/worker parts dropped |
| test | `config/ConfigurationProblemsTest.kt` | rain-core | ported | rain-core ConfigurationProblemTest + rain-boot RainConfigurationValidatorTest |
| test | `config/Deployments.kt` | dropped | pending | Lease deployment files; replaced by rain-test ConfigDocuments |
| test | `config/EvictionDomainTest.kt` | rain-access | pending | revocation server eviction-policy check |
| test | `config/RedisConfigurationTest.kt` | dropped | pending | Boot spring.data.redis |
| test | `config/ResourceDeclarationsTest.kt` | dropped | pending | mechanism dropped |
| test | `config/ShutdownConfigurationTest.kt` | rain-boot | dropped | shutdown budget is re-specified in rain-jobs |
| test | `config/SigningKeyTest.kt` | rain-access | pending | entropy heuristic removed (gap 21) |
| test | `config/TransportConfigurationTest.kt` | rain-web | pending |  |
| test | `config/WorkspaceConfigurationTest.kt` | dropped | pending | product configuration |
| test | `crud/CrudAccessTableTest.kt` | rain-crud | ported | web/MountedResourceTest |
| test | `crud/CrudResourceTest.kt` | rain-crud | ported | web/CrudHttpTest + dialect HTTP refusal tests |
| test | `crud/GuardedResourceTest.kt` | rain-crud | ported | CrudResourcePolicyTest |
| test | `crud/MemoryStore.kt` | rain-crud | ported | MemoryStore + CrudStoreContract (memory and jOOQ) |
| test | `crud/PaginationArithmeticTest.kt` | rain-crud | ported | rewritten as CursorPageAssemblyTest for v1 page shapes |
| test | `crud/query/ContractQueryFixture.kt` | dropped | dropped | Lease schema; replaced by neutral Books fixture |
| test | `crud/query/QueryDialectTest.kt` | rain-crud | ported | query/DialectV1Test; heuristic cases rewritten as gap 29 tests |
| test | `http/CredentialGateOnTomcatTest.kt` | rain-web | pending |  |
| test | `http/CrossOriginFilterTest.kt` | rain-web | dropped | filter dropped; replaced by rain-web CorsTest |
| test | `http/Refusals.kt` | rain-web | ported | rain-web WebTestSupport problem()/problemCode() |
| test | `http/RequestLogFilterTest.kt` | rain-web | ported | rain-web RequestLogFilterTest |
| test | `http/RootProbeControllerTest.kt` | rain-web | ported | rain-web ProbeStatusMappingTest |
| test | `http/RootProbeRefusalTest.kt` | rain-web | ported | rain-web ProbeStatusMappingTest |
| test | `http/TomcatDeadlineCustomizerTest.kt` | rain-web | dropped | customizer dropped |
| test | `http/TransportChainTest.kt` | rain-web | ported | SecurityHeadersOnPreSecurityRefusalTest, StatusTableCoversSpringErrorResponsesTest, CorsTest; gate cases to rain-access |
| test | `http/TransportDeadlinesTest.kt` | rain-web | dropped | deadlines dropped |
| test | `http/TransportWiringTest.kt` | rain-web | ported | rain-web RainWebAutoConfigurationTest; gate cases to rain-access |
| test | `http/error/ApiError.kt` | rain-web | dropped | client reader of the old envelope |
| test | `http/error/ApiErrorContractTest.kt` | rain-web | dropped | old envelope client contract; ProblemFormatTest covers v1 |
| test | `http/error/EnvelopeErrorControllerTest.kt` | rain-web | ported | rain-web RainExceptionHandlerTest (error dispatch) |
| test | `http/error/ErrorCodesParityTest.kt` | dropped | dropped | Go/Lease code parity |
| test | `http/error/ErrorEnvelopeGoldenTest.kt` | rain-web | ported | rain-web ProblemFormatTest (exact bytes) |
| test | `http/error/ErrorEnvelopeTest.kt` | rain-web | ported | rain-web ProblemFormatTest |
| test | `http/error/FaultKindTest.kt` | rain-web | ported | rain-core FaultTest (kinds, invariants) |
| test | `http/error/UnreadableBodyTest.kt` | rain-web | ported | StatusTableCoversSpringErrorResponsesTest (not readable to 400) |
| test | `http/filter/BodyLimitFilterTest.kt` | rain-web | ported | rain-web BodyLimitFilterTest |
| test | `http/filter/CrossSiteFilterTest.kt` | rain-web | ported | rain-web CrossSiteFilterTest |
| test | `http/filter/MountedPathTest.kt` | rain-web | ported | rain-web MountedPathTest |
| test | `http/filter/RequestBudgetFilterTest.kt` | rain-web | ported | rain-web RequestBudgetFilterTest |
| test | `http/filter/SecurityHeadersFilterTest.kt` | rain-web | ported | rain-web SecurityHeadersOnPreSecurityRefusalTest |
| test | `http/limit/CredentialGateFilterTest.kt` | rain-web | pending |  |
| test | `http/limit/CredentialGateTest.kt` | rain-web | pending |  |
| test | `http/limit/JsonOnlyFilterTest.kt` | rain-web | pending |  |
| test | `http/limit/TokenBucketThrottleTest.kt` | rain-web | ported | rain-web TokenBucketThrottleTest; timing case replaced by CallerTableFullTest |
| test | `llm/LlmGatewaySpringAiTest.kt` | rain-llm | pending |  |
| test | `llm/LlmPortTest.kt` | rain-llm | pending |  |
| test | `llm/SpringAiConfigurationTest.kt` | rain-llm | pending |  |
| test | `llm/Utf8Test.kt` | rain-llm | pending |  |
| test | `lock/AdvisoryLocksTest.kt` | rain-persistence | ported | rain-persistence PersistenceUnitTest |
| test | `lock/LockKeyTest.kt` | rain-persistence | ported | rain-core LockKeyTest with the same reference vectors |
| test | `lock/LockPropertiesTest.kt` | rain-persistence | ported | rain-persistence PersistenceContributorTest |
| test | `observability/ApplicationLogLevelTest.kt` | rain-observability | dropped | Lease app logging-level boot test |
| test | `observability/LokiOffTest.kt` | rain-observability | dropped | Lease deployment configuration |
| test | `observability/MutableClock.kt` | rain-observability | ported | rain-observability test helper |
| test | `observability/OtelAppenderInstalledTest.kt` | rain-observability | ported | rain-observability OtelAppenderInstalledTest |
| test | `observability/Transcript.kt` | rain-observability | ported | rain-web test helper |
| test | `observability/health/ActuatorHealthTest.kt` | rain-observability | ported | rain-observability ActuatorHealthTest |
| test | `observability/health/DatabaseHealthIndicatorTest.kt` | rain-observability | ported | rain-observability DatabaseHealthContributionTest |
| test | `observability/health/FakeCheck.kt` | rain-observability | ported | rain-observability test helper |
| test | `observability/health/HealthCacheTest.kt` | rain-observability | ported | rain-observability HealthCacheTest |
| test | `observability/health/HealthRegistryTest.kt` | rain-observability | ported | rain-observability HealthRegistryTest |
| test | `observability/health/ReadinessMappingTest.kt` | rain-observability | ported | rain-observability ReadinessMappingTest |
| test | `persistence/AssignIdCallbackTest.kt` | rain-persistence | ported | rain-data-jdbc DataJdbcUnitTest |
| test | `persistence/ConversionRegistrationTest.kt` | rain-persistence | ported | rain-data-jdbc DataJdbcUnitTest |
| test | `persistence/FlywayMigrationSettingsTest.kt` | rain-persistence | dropped | replaced by SchemaDescriptorTest and the schema migration IT |
| test | `persistence/IdsTest.kt` | rain-persistence | ported | rain-persistence PersistenceUnitTest |
| test | `persistence/TransactionRetryTest.kt` | rain-persistence | ported | rain-persistence TransactionRetryTest (incl. back-off ladder parity) |
| test | `realtime/ChannelNameTest.kt` | rain-realtime | ported | parse rules replace ofOrNull case |
| test | `realtime/RealtimeBackoffTest.kt` | rain-realtime | ported | injected ReconnectWait, no sleeping |
| test | `realtime/RealtimePublisherTest.kt` | rain-realtime | ported | plus surrogate and bounded-count cases |
| test | `redis/RedisWiringTest.kt` | dropped | pending | Boot spring.data.redis |
| test | `resilience/AdmissionGateTest.kt` | rain-resilience | pending |  |
| test | `resilience/BreakerHealthContributionTest.kt` | rain-resilience | pending |  |
| test | `resilience/BreakerTest.kt` | rain-resilience | pending |  |
| test | `workq/AttemptRunnerTest.kt` | rain-jobs | ported | AttemptThreadsTest |
| test | `workq/DefinitionGateTest.kt` | rain-jobs | ported | DefinitionGateTest |
| test | `workq/JobConcurrencyParityTest.kt` | dropped | dropped | Lease job ceilings |
| test | `workq/JobProfileTest.kt` | rain-jobs | ported | rewritten for BackoffLadder; Lease constants dropped |
| test | `workq/NoParkingInTheHandlerTest.kt` | rain-jobs | ported | NoParkingInTheHandlerTest |
| test | `workq/ScheduledTasksIsolationTest.kt` | rain-jobs | ported | ScheduledTasksIsolationTest |
| test | `workq/WorkQueueAdminTest.kt` | rain-jobs | dropped | DOCUMENT_SCOPED Lease vocabulary; CancelBySubjectIT covers cancelBySubject |
| test | `workq/WorkqWiringTest.kt` | rain-jobs | ported | JobsAutoConfigurationTest, JobTopologyTest |
| lease-it | `access/CredentialRepositoryIT.kt` | rain-access | pending |  |
| lease-it | `access/DirectoryStoreIT.kt` | rain-access | pending | store SQL part ported as rain-crud JooqResourceStoreIT; service parts remain for rain-access |
| lease-it | `access/RefreshRotationIT.kt` | rain-access | pending |  |
| lease-it | `access/RevocationListIT.kt` | rain-access | pending |  |
| lease-it | `access/SessionRepositoryIT.kt` | rain-access | pending |  |
| lease-it | `persistence/AccessAggregateRoundTripIT.kt` | rain-access | pending |  |
| lease-it | `access/AuthSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `access/DirectorySurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `access/SubjectGrantSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `audit/AuditTrailIT.kt` | rain-audit | ported | rain-audit AuditIT |
| lease-it | `persistence/AuditLogSchemaIT.kt` | rain-audit | ported | rain-audit AuditIT (schema, actor both-or-none, index-backed pages) |
| lease-it | `health/RootProbeIT.kt` | rain-observability | pending |  |
| lease-it | `llm/LlmSlotsIT.kt` | rain-llm | pending |  |
| lease-it | `lock/AdvisoryLocksIT.kt` | rain-persistence | ported | rain-persistence PersistenceIT |
| lease-it | `persistence/ApplicationOwnedIdsIT.kt` | rain-data-jdbc | ported | rain-data-jdbc DataJdbcIT |
| lease-it | `persistence/ConvertersIT.kt` | rain-data-jdbc | ported | rain-data-jdbc DataJdbcIT |
| lease-it | `persistence/OptimisticLockingIT.kt` | rain-data-jdbc | ported | rain-data-jdbc OptimisticLockingIT |
| lease-it | `persistence/JooqReadModelIT.kt` | rain-persistence | dropped | product read models |
| lease-it | `persistence/MigrationIT.kt` | rain-persistence | ported | rain-persistence PersistenceIT (schema per module, second run applies nothing) |
| lease-it | `persistence/MigrateCommandIT.kt` | rain-persistence | ported | rain-persistence PersistenceIT (migrate command) |
| lease-it | `persistence/ConnectionBudgetIT.kt` | rain-jobs | ported | ConnectionDemandIT |
| lease-it | `realtime/RealtimeListenerIT.kt` | rain-realtime | ported | terminated-backend case moved to ListenerReconnectUnlistenIT |
| lease-it | `surface/AnonymousSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `surface/AuthorizationIT.kt` | rain-sample | pending |  |
| lease-it | `surface/HttpSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `surface/RouterIT.kt` | rain-sample | pending |  |
| lease-it | `workq/WorkqIT.kt` | rain-jobs | ported | EnqueueIT, AttemptLifecycleIT, FenceIT, ReaperIT, RetentionIT |
| lease-it | `workq/SchedulerTopologyIT.kt` | rain-jobs | ported | SchedulerTopologyIT per declared profile, RoleGatingIT |
| lease-it | `workq/JobIntentRepositoryIT.kt` | rain-jobs | ported | JobIntentIT |
| lease-it | `workq/AttemptStatementTimeoutIT.kt` | rain-jobs | ported | AttemptStatementTimeoutIT |
| lease-it | `ops/DeadJobsIT.kt` | rain-jobs | ported | DeadLetterKeysetIT, RedriveIT |
| lease-it | `OneShotRunnerIT.kt` | rain-sample | pending |  |
| lease-it | `seed/SeedCommandIT.kt` | rain-sample | pending |  |
| lease-it | `persistence/Databases.kt` | rain-test | ported | rain-test RainPostgres.freshDatabase |
| lease-it | `persistence/Applications.kt` | rain-test | dropped | tests start applications with SpringApplicationBuilder |
| lease-it | `persistence/Schemas.kt` | rain-test | dropped | schema fingerprint replaced by per-module migration assertions |
| lease-it | `persistence/Slices.kt` | rain-test | dropped | no Spring Data slice in rain |
| lease-it | `AbstractIntegrationTest.kt` | rain-test | ported | rain-test RainPostgres (one container per JVM) |
| resource/doc | `src/test/resources/contract/envelope-golden.json` | rain-web | pending | regenerated as problem+json golden |
| resource/doc | `src/test/resources/schema/schema-fingerprint.sql` | rain-test | pending | pattern for per-schema fingerprints |
| resource/doc | `src/test/resources/testconfig/application.yml` | rain-test | pending | rain.* keys |
| resource/doc | `src/test/resources/contract/frontend-calls.json` | dropped | pending | Lease web contract |
| resource/doc | `docs/backend/config/README.md` | docs/concepts/configuration.md | pending |  |
| resource/doc | `docs/backend/core/runtime/README.md` | docs/concepts/runtime-roles-and-commands.md | pending |  |
| resource/doc | `docs/backend/modules/README.md` | docs/concepts/runtime-roles-and-commands.md | pending |  |
| resource/doc | `docs/backend/http/README.md` | docs/modules/web.md + docs/concepts/error-contract.md | pending |  |
| resource/doc | `docs/backend/jobs/README.md` | docs/modules/jobs.md | pending |  |
| resource/doc | `docs/backend/core/jobs/README.md` | docs/modules/jobs.md | pending |  |
| resource/doc | `docs/backend/access/README.md` | docs/modules/access.md | pending |  |
| resource/doc | `docs/backend/access/accounts.md` | docs/modules/access.md | pending |  |
| resource/doc | `docs/backend/degradation/README.md` | docs/modules/resilience.md | pending |  |
| resource/doc | `docs/backend/observability/README.md` | docs/modules/observability.md | pending |  |
| resource/doc | `docs/backend/slo/README.md` | docs/modules/observability.md | pending |  |
| resource/doc | `docs/backend/core/realtime/README.md` | docs/modules/realtime.md | pending |  |
| resource/doc | `docs/backend/core/redis/README.md` | docs/modules/access.md (revocation server) | pending |  |
| resource/doc | `PLAN.md §0-§2` | docs/conventions.md | pending |  |
