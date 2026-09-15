# Port inventory

Every file rain was built from, where it went, and its status. Source: `kotlin/tmp/project` at commit `befbcc9` (`backend/` paths are relative to `src/main/kotlin/com/gd/framework` for main, `src/test/kotlin/com/gd/framework` for test, `src/test/kotlin/com/gd/lease/integration` for lease-it).

Status: `pending` → `ported` / `dropped` / `split`. `kotlin/tmp` may be deleted only when no row is `pending`.

| Kind | Source | Destination | Status | Note |
|---|---|---|---|---|
| main | `FrameworkPackages.kt` | dropped | dropped | no package scanning in rain |
| main | `access/AccessFaults.kt` | rain-access | ported | internal/usecase/AccessSupport.kt AccessFaults + AccessErrorCodes |
| main | `access/AccessFieldNames.kt` | rain-access | dropped | Go field-name inversion; wire names are direct |
| main | `access/ModuleMetadata.kt` | rain-access | dropped | package metadata only |
| main | `access/SubjectRegistry.kt` | rain-access | ported | internal/usecase/SubjectRegistry.kt + SubjectRegistryCheck |
| main | `access/config/AccessConfiguration.kt` | rain-access | ported | autoconfigure/RainAccessAutoConfiguration |
| main | `access/config/AccessDeclarationConfiguration.kt` | rain-access | ported | RainAccessAutoConfiguration Catalogue + GrantDeclarationsCheck |
| main | `access/config/AccessSurfaceConfiguration.kt` | rain-access | ported | RainAccessAutoConfiguration.Web.Api interceptor + AccessSurfaceVerifier |
| main | `access/config/DirectoryConfiguration.kt` | rain-access | ported | RainAccessAutoConfiguration.Web.Api directory controllers |
| main | `access/domain/Credential.kt` | rain-access | ported | store/Rows.kt + V1__access.sql credentials |
| main | `access/domain/Permission.kt` | rain-access | ported | store/Rows.kt + permissions table |
| main | `access/domain/Role.kt` | rain-access | ported | store/Rows.kt + roles table |
| main | `access/domain/RolePermission.kt` | rain-access | ported | role_permissions table |
| main | `access/domain/Session.kt` | rain-access | ported | store/Rows.kt + sessions, subject_cutoffs |
| main | `access/domain/SubjectDefaultRole.kt` | rain-access | ported | subject_default_roles table |
| main | `access/domain/SubjectPermission.kt` | rain-access | ported | subject_permissions table |
| main | `access/domain/SubjectRole.kt` | rain-access | ported | subject_roles table |
| main | `access/protection/AttemptLimiter.kt` | rain-access | ported | internal/attempt AttemptLimiting + RedisAttemptLimiter (gap 18) |
| main | `access/protection/BulkheadPasswordEncoder.kt` | rain-access | ported | internal/password HashingBulkhead on R4j registry |
| main | `access/protection/MemoryAttemptLimiter.kt` | rain-access | ported | MemoryAttemptLimiter bounded, refused in prod |
| main | `access/protection/SignInAttemptsObserver.kt` | rain-access | ported | LoginUseCase failure path + AttemptKeys.fingerprint |
| main | `access/revoke/EvictionPolicy.kt` | rain-access | ported | EvictionPolicyAttestation + RedisEvictionPolicy |
| main | `access/revoke/RedisRevocationList.kt` | rain-access | ported | internal/revocation/RedisRevocationList (session + cutoff keys) |
| main | `access/revoke/RevocationEvictionVerifier.kt` | rain-access | ported | EvictionPolicyCheck |
| main | `access/revoke/RevocationList.kt` | rain-access | ported | RevocationList + NoRevocationList |
| main | `access/security/AccessAuthenticationFilter.kt` | rain-access | ported | web/Security.kt AccessAuthenticationFilter |
| main | `access/security/AccessAuthorizationManager.kt` | rain-access | ported | web/Surface.kt AccessEnforcementInterceptor |
| main | `access/security/AccessDeniedRenderer.kt` | rain-access | ported | ProblemAccessDenied |
| main | `access/security/AccessSurface.kt` | rain-access | ported | AccessDeclarations; package-prefix exemption removed |
| main | `access/security/AccessSurfaceVerifier.kt` | rain-access | ported | web/Surface.kt AccessSurfaceVerifier |
| main | `access/security/AuthEntryPoint.kt` | rain-access | ported | ProblemEntryPoint |
| main | `access/security/CredentialConverter.kt` | rain-access | ported | PresentedToken |
| main | `access/security/PrincipalActor.kt` | rain-access | ported | PrincipalActor |
| main | `access/security/SecurityChainConfiguration.kt` | rain-access | ported | AccessSecurityChain |
| main | `access/spi/AccessPrincipal.kt` | rain-access | ported | AccessApi.kt AccessPrincipal, no permission list |
| main | `access/spi/Grants.kt` | rain-access | ported | Grants.kt |
| main | `access/spi/Registration.kt` | rain-access | ported | Subjects.kt SubjectRegistrar/SignUp |
| main | `access/spi/SpiMetadata.kt` | rain-access | dropped | package metadata only |
| main | `access/spi/SubjectCredentials.kt` | rain-access | ported | AccessProvisioning enrolPassword/hasPassword |
| main | `access/spi/Subjects.kt` | rain-access | ported | Subjects.kt |
| main | `access/store/AccessCatalog.kt` | rain-access | ported | store/CatalogueStore.kt |
| main | `access/store/AccessRepositories.kt` | rain-access | ported | jOOQ keyset Credential/Session/GrantStore |
| main | `access/store/AccessRepositoryFragments.kt` | rain-access | ported | folded into jOOQ stores |
| main | `access/token/AccessTokenIssuer.kt` | rain-access | ported | token/AccessTokens.kt |
| main | `access/token/AccessTokenVerifier.kt` | rain-access | ported | token/AccessTokens.kt |
| main | `access/token/PasswordEncoders.kt` | rain-access | ported | Argon2PasswordHasher |
| main | `access/token/RefreshCredential.kt` | rain-access | ported | token/RefreshCredentials.kt |
| main | `access/token/RotationClassifier.kt` | rain-access | ported | token/RefreshCredentials.kt |
| main | `access/usecase/AccessCatalogSynchronizer.kt` | rain-access | ported | CatalogueSynchronizer |
| main | `access/usecase/AccessSeeder.kt` | rain-access | ported | ProvisioningService/AccessProvisioning; seed file dropped |
| main | `access/usecase/AuthResponse.kt` | rain-access | ported | web/AccessWeb.kt AuthAnswer |
| main | `access/usecase/ChangePasswordUseCase.kt` | rain-access | ported | usecase/Passwords.kt |
| main | `access/usecase/GrantsResolver.kt` | rain-access | ported | GrantsService, per-request memo |
| main | `access/usecase/LoginUseCase.kt` | rain-access | ported | usecase/SignIn.kt two-phase |
| main | `access/usecase/OwnedTransactions.kt` | rain-access | ported | AccessTransactions |
| main | `access/usecase/PasswordCredentials.kt` | rain-access | ported | PasswordRules |
| main | `access/usecase/RevocationAnnouncer.kt` | rain-access | ported | SessionClosing + RevocationReplayTask |
| main | `access/usecase/RotateRefreshUseCase.kt` | rain-access | ported | RefreshUseCase |
| main | `access/usecase/SessionIssuer.kt` | rain-access | ported | SessionIssuer |
| main | `access/usecase/SessionUseCases.kt` | rain-access | ported | usecase/Sessions.kt |
| main | `access/usecase/SetSubjectPasswordUseCase.kt` | rain-access | ported | usecase/Passwords.kt |
| main | `access/usecase/SignUpUseCase.kt` | rain-access | ported | usecase/SignIn.kt |
| main | `access/usecase/SubjectGrantsUseCase.kt` | rain-access | ported | GrantsAdministration |
| main | `access/web/AuthController.kt` | rain-access | ported | web/AuthController.kt |
| main | `access/web/AuthCrossSiteFilter.kt` | rain-access | dropped | rain-web CrossSiteFilter |
| main | `access/web/AuthCrossSiteGuard.kt` | rain-access | dropped | rain-web CrossSiteFilter |
| main | `access/web/AuthTransportConfiguration.kt` | rain-access | ported | RainAccessAutoConfiguration.Web |
| main | `access/web/AuthViews.kt` | rain-access | ported | web/AccessWeb.kt views |
| main | `access/web/CredentialDelivery.kt` | rain-access | ported | CredentialCookies + DeliveryDecision |
| main | `access/web/DirectoryResources.kt` | rain-access | ported | DirectoryControllers + AccessPermissionCodes |
| main | `access/web/RoleController.kt` | rain-access | ported | DirectoryControllers RoleController |
| main | `access/web/RoleService.kt` | rain-access | ported | RoleAdministration; slugify dropped |
| main | `access/web/SubjectGrantController.kt` | rain-access | ported | DirectoryControllers SubjectGrantController |
| main | `audit/AccessAudit.kt` | rain-access | ported | internal/audit/AccessAudit.kt AccessAuditTypes |
| main | `audit/AuditConfiguration.kt` | rain-audit | ported | RainAuditAutoConfiguration |
| main | `audit/AuditDetail.kt` | rain-audit | ported | bounded scalar detail checked against the declared type, deterministic JSON (gap 21 part) |
| main | `audit/AuditEvent.kt` | rain-audit | ported | AuditEventType (declared detail keys) + AuditEvent + AuditOutcome |
| main | `audit/AuditLogRepository.kt` | rain-audit | ported | schema rain_audit via rain.jooq-schema; actor (type, id) with no foreign key (gap 17 part) |
| main | `audit/AuditRecorder.kt` | rain-audit | ported | JooqAuditRecorder: record inside the caller's transaction (refused outside), recordIndependently in its own; keyset reads with a bounded page |
| main | `audit/SignInAuditRecorder.kt` | rain-access | ported | LoginUseCase via AuditTrail.recordIndependently |
| main | `cli/OneShotReports.kt` | split | ported | split: LlmSmokeCheck -> rain-llm SmokeLlmCommand; RedlineReport dropped (product) |
| main | `cli/SeedStep.kt` | rain-boot | ported | rain-boot Seeder + seed command (ordered by order then name, names checked) |
| main | `config/AccessProperties.kt` | rain-access | ported | AccessProperties.kt |
| main | `config/AppProperties.kt` | rain-boot | dropped | spring.application.name is required instead; shutdown budget moves to rain-jobs with spring.lifecycle |
| main | `config/CasebankProperties.kt` | dropped | dropped | product configuration |
| main | `config/ClientProperties.kt` | split | ported | split: LlmProperties -> rain-llm rain.llm; BreakerProperties -> resilience4j instances config checked by rain-resilience; WpsProperties dropped |
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
| main | `config/RedisProperties.kt` | dropped | dropped | Boot spring.data.redis.*; revocation settings → rain-access |
| main | `config/ResourceDeclarations.kt` | dropped | dropped | replaced by revocation server eviction-policy check (gap 14) |
| main | `config/SeedProperties.kt` | dropped | dropped | product configuration; prod secret rule → @RequiredFromEnvironment (gap 37) |
| main | `config/SigningKey.kt` | rain-access | ported | internal/token/SigningKey.kt; entropy heuristic removed |
| main | `config/StorageProperties.kt` | dropped | dropped | product configuration |
| main | `config/WorkspaceProperties.kt` | dropped | dropped | product configuration |
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
| main | `http/limit/CredentialGate.kt` | rain-access | ported | AccessProperties.Gate + CredentialSurface |
| main | `http/limit/CredentialGateFilter.kt` | rain-access | ported | CredentialThrottleFilter; bulkhead to hashing bulkhead |
| main | `http/limit/JsonOnlyFilter.kt` | rain-web | ported | rain-access internal/web JsonOnlyFilter |
| main | `http/limit/TokenBucketThrottle.kt` | rain-web | ported | rain-web TokenBucketThrottle |
| main | `llm/LlmConfiguration.kt` | rain-llm | ported | rain-llm RainLlmAutoConfiguration (enabled-gated) |
| main | `llm/LlmErrors.kt` | rain-llm | ported | rain-llm LlmException family + RainLlmErrorCodes + LlmFaultTranslator |
| main | `llm/LlmGateway.kt` | rain-llm | ported | rain-llm LlmGateway over any ChatModel; LlmRequestCustomizer; TokenCounter outputBudget (gaps 38, 39) |
| main | `llm/LlmSettings.kt` | rain-llm | ported | rain-llm LlmSettings.of(LlmProperties), one validator (gap 40) |
| main | `llm/LlmSlotStore.kt` | rain-llm | ported | rain-llm LlmSlotStore with SlotGrant Granted |
| main | `llm/LlmSlots.kt` | rain-llm | ported | rain-llm LlmSlots, pools/classes from config, wait bounded by call budget |
| main | `llm/LlmSmokeProbe.kt` | rain-llm | ported | rain-llm SmokeLlmCommand (smoke-llm) |
| main | `llm/Utf8.kt` | rain-llm | dropped | only consumer was the CHARS_PER_TOKEN budget replaced by TokenCounter |
| main | `llm/persistence/LlmSlotRepository.kt` | rain-llm | ported | rain-llm JooqLlmSlotStore, schema rain_llm, PoolMissing distinct from Full |
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
| main | `persistence/LanguageCodes.kt` | dropped | dropped | product converter; JdbcConversionContribution instead |
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
| main | `redis/RedisClients.kt` | dropped | dropped | Boot spring.data.redis.* (gap 14) |
| main | `resilience/AdmissionGate.kt` | rain-resilience | ported | rain-resilience AdmissionGate over atomic BreakerRegistry.reserve; admit(free) dropped (gap 11) |
| main | `resilience/AuthBulkheadConfiguration.kt` | rain-access | ported | HashingBulkhead + HashingBulkheadCheck |
| main | `resilience/BreakerHealthContribution.kt` | rain-resilience | ported | rain-resilience breaker health per BreakerDeclaration |
| main | `resilience/BreakerRegistry.kt` | rain-resilience | ported | rain-resilience BreakerRegistry over container CircuitBreakerRegistry, no ofDefaults (gap 12) |
| main | `resilience/Breakers.kt` | rain-resilience | ported | enum Breaker dropped -> BreakerName/BreakerDeclaration/BreakerState/Reservation/AdmissionState |
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
| test | `FrameworkBoundaryTest.kt` | dropped | dropped | Lease boundary; replaced by RainArchRules |
| test | `access/AccessFieldNamesTest.kt` | rain-access | dropped | Go name inversion not ported |
| test | `access/PermissionCatalogueTest.kt` | rain-access | ported | GrantDeclarationsCheckTest; product code counts dropped |
| test | `access/protection/MemoryAttemptLimiterTest.kt` | rain-access | ported | MemoryAttemptLimiterTest, AttemptLimiterCapacityTest |
| test | `access/protection/SignInAttemptsObserverTest.kt` | rain-access | ported | SignInAttemptAuditTest |
| test | `access/revoke/EvictionPolicyTest.kt` | rain-access | ported | EvictionPolicyCheckTest |
| test | `access/revoke/RevocationListTest.kt` | rain-access | ported | LogoutAllWritesOneRedisKeyTest, RevocationListIT |
| test | `access/security/AccessSurfaceVerifierTest.kt` | rain-access | ported | AccessSurfaceVerifierTest + gap 21 tests |
| test | `access/security/CredentialConverterTest.kt` | rain-access | ported | PresentedTokenTest |
| test | `access/security/SecurityDefaultsTest.kt` | rain-access | ported | AccessAutoConfigurationTest, UserDetailsServiceExclusionFilterTest |
| test | `access/security/SecurityRefusalTest.kt` | rain-access | ported | SecurityRefusalTest |
| test | `access/token/AccessTokenTest.kt` | rain-access | ported | AccessTokenTest |
| test | `access/token/PasswordEncoderCompatibilityTest.kt` | rain-access | ported | PasswordHashingTest, HashingBulkheadTest |
| test | `access/token/RefreshCredentialTest.kt` | rain-access | ported | RefreshCredentialTest |
| test | `access/token/RotationClassifierTest.kt` | rain-access | ported | RotationClassifierTest |
| test | `access/usecase/AccessCatalogSynchronizerTest.kt` | rain-access | ported | CatalogueSynchronizerTest |
| test | `access/usecase/AccessSeederTest.kt` | rain-access | ported | AccessProvisioningTest |
| test | `access/web/AuthCrossSiteGuardTest.kt` | rain-access | dropped | guard dropped for rain-web CrossSiteFilter |
| test | `access/web/AuthViewsSerializationTest.kt` | rain-access | ported | AuthViewsSerializationTest |
| test | `access/web/CredentialDeliveryTest.kt` | rain-access | ported | CredentialCookiesTest, DeliveryDecisionTest |
| test | `access/web/RoleServiceTest.kt` | rain-access | ported | RoleAdministrationTest |
| test | `audit/AuditDetailTest.kt` | rain-audit | ported | rain-audit AuditDetailTest |
| test | `audit/AuditRecorderTest.kt` | rain-audit | ported | rain-audit AuditIT (transaction semantics against PostgreSQL) |
| test | `audit/SignInAuditRecorderTest.kt` | rain-audit | ported | rain-access SignInAttemptAuditTest, RefusedAttemptsWriteOneLockoutRowTest |
| test | `config/AccessConfigurationTest.kt` | rain-access | ported | AccessPropertiesRefusesZeroTest, MemoryLimiterRefusedInProdTest |
| test | `config/ConfigFileParityTest.kt` | dropped | dropped | Lease deployment files |
| test | `config/ConfigurationBindingTest.kt` | split | ported | split: body limit and budget -> RainWebPropertiesValidationTest, upload vs body -> MultipartLimitsCheckTest, stage/roles -> rain-boot, llm -> LlmPropertiesTest, jobs workers -> JobsConfigurationTest; workspace, receipt retention and supervisor grace dropped (product); stage from profile dropped (gap 36) |
| test | `config/ConfigurationProblemsTest.kt` | rain-core | ported | rain-core ConfigurationProblemTest + rain-boot RainConfigurationValidatorTest |
| test | `config/Deployments.kt` | dropped | dropped | Lease deployment files; replaced by rain-test ConfigDocuments |
| test | `config/EvictionDomainTest.kt` | rain-access | ported | EvictionPolicyCheckTest, RevocationServerPolicyIT |
| test | `config/RedisConfigurationTest.kt` | dropped | dropped | Boot spring.data.redis |
| test | `config/ResourceDeclarationsTest.kt` | dropped | dropped | mechanism dropped |
| test | `config/ShutdownConfigurationTest.kt` | rain-boot | dropped | shutdown budget is re-specified in rain-jobs |
| test | `config/SigningKeyTest.kt` | rain-access | ported | SigningKeyStrictBase64Test |
| test | `config/TransportConfigurationTest.kt` | rain-web | ported | rain-web ProdOriginRulesTest: prod refuses *, non-https and loopback origins (rain-core Loopback) |
| test | `config/WorkspaceConfigurationTest.kt` | dropped | dropped | product configuration |
| test | `crud/CrudAccessTableTest.kt` | rain-crud | ported | web/MountedResourceTest |
| test | `crud/CrudResourceTest.kt` | rain-crud | ported | web/CrudHttpTest + dialect HTTP refusal tests |
| test | `crud/GuardedResourceTest.kt` | rain-crud | ported | CrudResourcePolicyTest |
| test | `crud/MemoryStore.kt` | rain-crud | ported | MemoryStore + CrudStoreContract (memory and jOOQ) |
| test | `crud/PaginationArithmeticTest.kt` | rain-crud | ported | rewritten as CursorPageAssemblyTest for v1 page shapes |
| test | `crud/query/ContractQueryFixture.kt` | dropped | dropped | Lease schema; replaced by neutral Books fixture |
| test | `crud/query/QueryDialectTest.kt` | rain-crud | ported | query/DialectV1Test; heuristic cases rewritten as gap 29 tests |
| test | `http/CredentialGateOnTomcatTest.kt` | rain-web | ported | rain-access CredentialGateOnTomcatIT (encoded paths over a real Tomcat socket) |
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
| test | `http/limit/CredentialGateFilterTest.kt` | rain-web | ported | rain-access CredentialGateFiltersTest, HashingBulkheadTest |
| test | `http/limit/CredentialGateTest.kt` | rain-web | ported | rain-access CredentialSurfaceTest, AccessPropertiesRefusesZeroTest |
| test | `http/limit/JsonOnlyFilterTest.kt` | rain-web | ported | rain-access CredentialGateFiltersTest |
| test | `http/limit/TokenBucketThrottleTest.kt` | rain-web | ported | rain-web TokenBucketThrottleTest; timing case replaced by CallerTableFullTest |
| test | `llm/LlmGatewaySpringAiTest.kt` | rain-llm | ported | rain-llm LlmGatewayTest (ScriptedChatModel) |
| test | `llm/LlmPortTest.kt` | rain-llm | ported | rain-llm LlmPropertiesTest + LlmSlotsTest |
| test | `llm/SpringAiConfigurationTest.kt` | rain-llm | ported | rain-llm LlmAutoConfigurationTest |
| test | `llm/Utf8Test.kt` | rain-llm | dropped | Utf8.kt dropped |
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
| test | `redis/RedisWiringTest.kt` | dropped | dropped | Boot spring.data.redis |
| test | `resilience/AdmissionGateTest.kt` | rain-resilience | ported | rain-resilience AdmissionGateTest + AdmissionGateRaceTest |
| test | `resilience/BreakerHealthContributionTest.kt` | rain-resilience | ported | rain-resilience BreakerHealthContributionTest |
| test | `resilience/BreakerTest.kt` | rain-resilience | ported | rain-resilience BreakerRegistryTest |
| test | `workq/AttemptRunnerTest.kt` | rain-jobs | ported | AttemptThreadsTest |
| test | `workq/DefinitionGateTest.kt` | rain-jobs | ported | DefinitionGateTest |
| test | `workq/JobConcurrencyParityTest.kt` | dropped | dropped | Lease job ceilings |
| test | `workq/JobProfileTest.kt` | rain-jobs | ported | rewritten for BackoffLadder; Lease constants dropped |
| test | `workq/NoParkingInTheHandlerTest.kt` | rain-jobs | ported | NoParkingInTheHandlerTest |
| test | `workq/ScheduledTasksIsolationTest.kt` | rain-jobs | ported | ScheduledTasksIsolationTest |
| test | `workq/WorkQueueAdminTest.kt` | rain-jobs | dropped | DOCUMENT_SCOPED Lease vocabulary; CancelBySubjectIT covers cancelBySubject |
| test | `workq/WorkqWiringTest.kt` | rain-jobs | ported | JobsAutoConfigurationTest, JobTopologyTest |
| lease-it | `access/CredentialRepositoryIT.kt` | rain-access | ported | CredentialStoreIT |
| lease-it | `access/DirectoryStoreIT.kt` | rain-access | ported | rain-access RoleAdministrationIT, DirectoryPagesKeysetIT; store SQL part rain-crud JooqResourceStoreIT |
| lease-it | `access/RefreshRotationIT.kt` | rain-access | ported | RefreshRotationIT, ConcurrentRefreshThenGraceIT |
| lease-it | `access/RevocationListIT.kt` | rain-access | ported | RevocationListIT |
| lease-it | `access/SessionRepositoryIT.kt` | rain-access | ported | SessionStoreIT |
| lease-it | `persistence/AccessAggregateRoundTripIT.kt` | rain-access | ported | AccessAggregateRoundTripIT |
| lease-it | `access/AuthSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `access/DirectorySurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `access/SubjectGrantSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `audit/AuditTrailIT.kt` | rain-audit | ported | rain-audit AuditIT |
| lease-it | `persistence/AuditLogSchemaIT.kt` | rain-audit | ported | rain-audit AuditIT (schema, actor both-or-none, index-backed pages) |
| lease-it | `health/RootProbeIT.kt` | rain-observability | ported | probes: ProbeController/ReadinessMappingTest; X-Request-ID echo: RequestLogFilterTest; database: DatabaseHealthCheckTest; freshness: HealthCacheTest; `/` and `/favicon.ico` dropped (product surface) |
| lease-it | `llm/LlmSlotsIT.kt` | rain-llm | ported | rain-llm LlmSlotsIT + MissingBudgetRowIsExplicitIT |
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
| resource/doc | `src/test/resources/contract/envelope-golden.json` | rain-web | ported | rain-web problem-format-v1.golden.json + ProblemFormatGoldenTest, writeProblemGolden task |
| resource/doc | `src/test/resources/schema/schema-fingerprint.sql` | rain-test | dropped | RainPostgres gives each test a fresh database; module jOOQ code is generated from the module's own migrations |
| resource/doc | `src/test/resources/testconfig/application.yml` | rain-test | dropped | rain tests state rain.* per context; no shared test configuration file |
| resource/doc | `src/test/resources/contract/frontend-calls.json` | dropped | dropped | Lease web contract |
| resource/doc | `docs/backend/config/README.md` | docs/concepts/configuration.md | ported | unknown keys, every problem at once, dependent rules, production secrets from the environment -> docs/modules/boot.md; Redis, signing key and Go loader dropped |
| resource/doc | `docs/backend/core/runtime/README.md` | docs/concepts/runtime-roles-and-commands.md | ported | role-gated activation, component-owned loops and parallel drain under one grace -> docs/conventions.md and docs/modules/jobs.md; Go runtime, doctor and product runners dropped |
| resource/doc | `docs/backend/modules/README.md` | docs/concepts/runtime-roles-and-commands.md | ported | activation as a declaration, commands get no surface -> docs/conventions.md; module catalog, profiles and doctor dropped |
| resource/doc | `docs/backend/http/README.md` | docs/modules/web.md + docs/concepts/error-contract.md | ported | request log, correlation id, security headers, cross-site table, readiness logging, bounded caller table -> docs/modules/web.md; fiber, deadlines, auth gate and route registrar dropped |
| resource/doc | `docs/backend/jobs/README.md` | docs/modules/jobs.md | ported | queue step vs recurring pass, adapter vs work, deferred/permanent classification, next step inside the fence -> docs/modules/jobs.md; product steps dropped |
| resource/doc | `docs/backend/core/jobs/README.md` | docs/modules/jobs.md | ported | queue tables reached only through JobAdministration and fenced effects -> docs/modules/jobs.md; Go framework and migration history dropped |
| resource/doc | `docs/backend/access/README.md` | docs/modules/access.md | pending |  |
| resource/doc | `docs/backend/access/accounts.md` | docs/modules/access.md | pending |  |
| resource/doc | `docs/backend/degradation/README.md` | docs/modules/resilience.md | ported | dependency failure, one probe per cooldown, process-local state, uncharged deferral, degrading importance -> docs/modules/resilience.md and docs/modules/jobs.md; product pipeline dropped |
| resource/doc | `docs/backend/observability/README.md` | docs/modules/observability.md | ported | log correlation via MDC and the OTLP log bridge -> docs/modules/observability.md; Go instrumentation seams dropped |
| resource/doc | `docs/backend/slo/README.md` | docs/modules/observability.md | ported | thresholds as measured or target with a named check, alertable signals -> docs/modules/observability.md; product numbers dropped |
| resource/doc | `docs/backend/core/realtime/README.md` | docs/modules/realtime.md | ported | channel rules, transactional publish, subscribe-before-snapshot, gap/overflow/closed, startup refusal -> docs/modules/realtime.md |
| resource/doc | `docs/backend/core/redis/README.md` | docs/modules/access.md (revocation server) | pending |  |
| resource/doc | `PLAN.md §0-§2` | docs/conventions.md | ported | docs/conventions.md |
