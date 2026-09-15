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
| main | `audit/AuditConfiguration.kt` | rain-audit | pending |  |
| main | `audit/AuditDetail.kt` | rain-audit | pending |  |
| main | `audit/AuditEvent.kt` | rain-audit | pending |  |
| main | `audit/AuditLogRepository.kt` | rain-audit | pending |  |
| main | `audit/AuditRecorder.kt` | rain-audit | pending |  |
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
| main | `config/HttpProperties.kt` | rain-web | pending | rain.web |
| main | `config/JobsProperties.kt` | rain-jobs | pending | DEFAULT_WORKERS dropped |
| main | `config/RealtimeProperties.kt` | rain-realtime | pending |  |
| main | `config/RedisProperties.kt` | dropped | pending | Boot spring.data.redis.*; revocation settings → rain-access |
| main | `config/ResourceDeclarations.kt` | dropped | pending | replaced by revocation server eviction-policy check (gap 14) |
| main | `config/SeedProperties.kt` | dropped | pending | product configuration; prod secret rule → @RequiredFromEnvironment (gap 37) |
| main | `config/SigningKey.kt` | rain-access | pending | entropy heuristic removed (gap 21) |
| main | `config/StorageProperties.kt` | dropped | pending | product configuration |
| main | `config/WorkspaceProperties.kt` | dropped | pending | product configuration |
| main | `crud/BulkDeleteRequest.kt` | rain-crud | pending |  |
| main | `crud/CrudAccessTable.kt` | rain-crud | pending |  |
| main | `crud/CrudConfiguration.kt` | rain-crud | pending |  |
| main | `crud/CrudMounted.kt` | rain-crud | pending |  |
| main | `crud/CrudResource.kt` | rain-crud | pending |  |
| main | `crud/CrudRouteRegistrar.kt` | rain-crud | pending |  |
| main | `crud/CrudStore.kt` | rain-crud | pending |  |
| main | `crud/Operations.kt` | rain-crud | pending |  |
| main | `crud/PaginatedResponse.kt` | rain-crud | pending |  |
| main | `crud/ResourcePolicy.kt` | rain-crud | pending |  |
| main | `crud/persistence/JooqResourceStore.kt` | rain-crud | pending |  |
| main | `crud/persistence/QueryStatements.kt` | rain-crud | pending |  |
| main | `crud/persistence/Row.kt` | rain-crud | pending |  |
| main | `crud/query/Coerce.kt` | rain-crud | pending |  |
| main | `crud/query/FieldFold.kt` | rain-crud | pending |  |
| main | `crud/query/FilterNode.kt` | rain-crud | pending |  |
| main | `crud/query/QueryCompiler.kt` | rain-crud | pending |  |
| main | `crud/query/QueryConfig.kt` | rain-crud | pending |  |
| main | `crud/query/QueryDialectParser.kt` | rain-crud | pending |  |
| main | `crud/query/QueryDocument.kt` | rain-crud | pending |  |
| main | `crud/query/QueryRequest.kt` | rain-crud | pending |  |
| main | `crud/query/ResourceSchema.kt` | rain-crud | pending |  |
| main | `http/Access.kt` | rain-web | pending |  |
| main | `http/ApiPaths.kt` | dropped | pending | required rain.access.web.base-path |
| main | `http/CrossOriginFilter.kt` | rain-web | pending |  |
| main | `http/RequestLogConfiguration.kt` | rain-web | pending |  |
| main | `http/RequestLogFilter.kt` | rain-web | pending |  |
| main | `http/RootProbeController.kt` | rain-web | pending |  |
| main | `http/SocketDeadlines.kt` | rain-web | pending |  |
| main | `http/TomcatDeadlineCustomizer.kt` | rain-web | pending |  |
| main | `http/TransportBodyLimits.kt` | rain-web | pending |  |
| main | `http/TransportConfiguration.kt` | rain-web | pending |  |
| main | `http/error/EnvelopeErrorController.kt` | rain-web | pending |  |
| main | `http/error/EnvelopeWriter.kt` | rain-web | pending |  |
| main | `http/error/ErrorCodes.kt` | rain-core | ported | RainErrorCodes catalog + ErrorCodeRegistry (duplicates refused); DOMAIN codes dropped (gap 35) |
| main | `http/error/ErrorEnvelope.kt` | rain-web | pending |  |
| main | `http/error/ErrorEnvelopeAdvice.kt` | rain-web | pending |  |
| main | `http/error/Fault.kt` | rain-core | ported | rain-core Fault with typed ErrorCode and construction invariants |
| main | `http/error/FaultKind.kt` | rain-core | ported | status as Int, total kind table (gap 31 core half) |
| main | `http/error/Groups.kt` | rain-core | dropped | validation/general grouping replaced by the problem+json errors list |
| main | `http/error/StaleDocumentBody.kt` | dropped | pending | product body; problem extensions instead |
| main | `http/error/Violation.kt` | rain-core | ported | RFC 6901 pointer path, deterministic order |
| main | `http/filter/BodyLimitFilter.kt` | rain-web | pending |  |
| main | `http/filter/CrossSiteFilter.kt` | rain-web | pending |  |
| main | `http/filter/RequestBudgetFilter.kt` | rain-web | pending |  |
| main | `http/filter/RequestDeadline.kt` | rain-web | pending |  |
| main | `http/filter/SafeMethods.kt` | rain-web | pending |  |
| main | `http/filter/SecurityHeadersFilter.kt` | rain-web | pending |  |
| main | `http/limit/CallerTable.kt` | rain-web | pending |  |
| main | `http/limit/CredentialGate.kt` | rain-access | pending |  |
| main | `http/limit/CredentialGateFilter.kt` | rain-access | pending |  |
| main | `http/limit/JsonOnlyFilter.kt` | rain-web | pending |  |
| main | `http/limit/TokenBucketThrottle.kt` | rain-web | pending |  |
| main | `llm/LlmConfiguration.kt` | rain-llm | pending |  |
| main | `llm/LlmErrors.kt` | rain-llm | pending |  |
| main | `llm/LlmGateway.kt` | rain-llm | pending |  |
| main | `llm/LlmSettings.kt` | rain-llm | pending |  |
| main | `llm/LlmSlotStore.kt` | rain-llm | pending |  |
| main | `llm/LlmSlots.kt` | rain-llm | pending |  |
| main | `llm/LlmSmokeProbe.kt` | rain-llm | pending |  |
| main | `llm/Utf8.kt` | rain-llm | pending |  |
| main | `llm/persistence/LlmSlotRepository.kt` | rain-llm | pending |  |
| main | `lock/AdvisoryLockStore.kt` | rain-persistence | pending |  |
| main | `lock/AdvisoryLocks.kt` | rain-persistence | pending |  |
| main | `lock/LockConfiguration.kt` | rain-persistence | pending |  |
| main | `lock/LockKey.kt` | rain-persistence | ported | rain-core lock key derivation (FNV-1a 64) and guards |
| main | `lock/LockProperties.kt` | rain-persistence | pending |  |
| main | `lock/persistence/AdvisoryLockRepository.kt` | rain-persistence | pending |  |
| main | `observability/LoggingConfiguration.kt` | rain-observability | pending |  |
| main | `observability/health/ActuatorHealthContributors.kt` | rain-observability | pending |  |
| main | `observability/health/DatabaseHealthIndicator.kt` | rain-persistence | pending |  |
| main | `observability/health/HealthCache.kt` | rain-observability | pending |  |
| main | `observability/health/HealthConfiguration.kt` | rain-observability | pending |  |
| main | `observability/health/HealthModel.kt` | rain-observability | pending |  |
| main | `observability/health/HealthRegistry.kt` | rain-observability | pending |  |
| main | `persistence/AssignIdCallback.kt` | rain-data-jdbc | pending |  |
| main | `persistence/ConnectionBudget.kt` | dropped | pending | replaced by ConnectionDemandCheck in rain-jobs |
| main | `persistence/CurrentActor.kt` | rain-persistence | pending |  |
| main | `persistence/DataAccessFaults.kt` | rain-persistence | pending |  |
| main | `persistence/Ids.kt` | rain-persistence | pending |  |
| main | `persistence/JsonbPayload.kt` | rain-persistence | pending |  |
| main | `persistence/LanguageCodes.kt` | dropped | pending | product converter; JdbcConversionContribution instead |
| main | `persistence/OffsetDateTimeToInstantConverter.kt` | rain-data-jdbc | pending |  |
| main | `persistence/PersistenceConfiguration.kt` | rain-persistence | pending |  |
| main | `persistence/RealtimeDataSource.kt` | rain-realtime | pending |  |
| main | `persistence/RecordingFlywayMigration.kt` | rain-persistence | pending |  |
| main | `persistence/StatementBudget.kt` | rain-persistence | pending |  |
| main | `persistence/TransactionRetry.kt` | rain-persistence | pending |  |
| main | `persistence/WireEnum.kt` | rain-persistence | pending |  |
| main | `realtime/Channel.kt` | rain-realtime | pending |  |
| main | `realtime/RealtimeConfiguration.kt` | rain-realtime | pending |  |
| main | `realtime/RealtimeFault.kt` | rain-realtime | pending |  |
| main | `realtime/RealtimeListener.kt` | rain-realtime | pending |  |
| main | `realtime/RealtimePublisher.kt` | rain-realtime | pending |  |
| main | `realtime/Subscription.kt` | rain-realtime | pending |  |
| main | `redis/RedisClients.kt` | dropped | pending | Boot spring.data.redis.* (gap 14) |
| main | `resilience/AdmissionGate.kt` | rain-resilience | pending |  |
| main | `resilience/AuthBulkheadConfiguration.kt` | rain-access | pending |  |
| main | `resilience/BreakerHealthContribution.kt` | rain-resilience | pending |  |
| main | `resilience/BreakerRegistry.kt` | rain-resilience | pending |  |
| main | `resilience/Breakers.kt` | rain-resilience | pending |  |
| main | `workq/AttemptStatementTimeout.kt` | rain-jobs | pending |  |
| main | `workq/DefinitionGate.kt` | rain-jobs | pending |  |
| main | `workq/FencedEffects.kt` | rain-jobs | pending |  |
| main | `workq/Housekeeping.kt` | rain-jobs | pending |  |
| main | `workq/JobDefinition.kt` | rain-jobs | pending |  |
| main | `workq/JobProfile.kt` | rain-jobs | pending |  |
| main | `workq/Jobs.kt` | rain-jobs | pending |  |
| main | `workq/SchedulerWorkQueue.kt` | rain-jobs | pending |  |
| main | `workq/WorkqConfiguration.kt` | rain-jobs | pending |  |
| main | `workq/WorkqExecutionHandler.kt` | rain-jobs | pending |  |
| main | `workq/WorkqSchedulers.kt` | rain-jobs | pending |  |
| main | `workq/persistence/JobIntentRepository.kt` | rain-jobs | pending |  |
| main | `workq/persistence/JobInvocationRepository.kt` | rain-jobs | pending |  |
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
| test | `audit/AuditDetailTest.kt` | rain-audit | pending |  |
| test | `audit/AuditRecorderTest.kt` | rain-audit | pending |  |
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
| test | `crud/CrudAccessTableTest.kt` | rain-crud | pending |  |
| test | `crud/CrudResourceTest.kt` | rain-crud | pending |  |
| test | `crud/GuardedResourceTest.kt` | rain-crud | pending |  |
| test | `crud/MemoryStore.kt` | rain-crud | pending |  |
| test | `crud/PaginationArithmeticTest.kt` | rain-crud | pending |  |
| test | `crud/query/ContractQueryFixture.kt` | dropped | pending | Lease schema; replaced by a sample fixture |
| test | `crud/query/QueryDialectTest.kt` | rain-crud | pending |  |
| test | `http/CredentialGateOnTomcatTest.kt` | rain-web | pending |  |
| test | `http/CrossOriginFilterTest.kt` | rain-web | pending |  |
| test | `http/Refusals.kt` | rain-web | pending |  |
| test | `http/RequestLogFilterTest.kt` | rain-web | pending |  |
| test | `http/RootProbeControllerTest.kt` | rain-web | pending |  |
| test | `http/RootProbeRefusalTest.kt` | rain-web | pending |  |
| test | `http/TomcatDeadlineCustomizerTest.kt` | rain-web | pending |  |
| test | `http/TransportChainTest.kt` | rain-web | pending |  |
| test | `http/TransportDeadlinesTest.kt` | rain-web | pending |  |
| test | `http/TransportWiringTest.kt` | rain-web | pending |  |
| test | `http/error/ApiError.kt` | rain-web | pending |  |
| test | `http/error/ApiErrorContractTest.kt` | rain-web | pending |  |
| test | `http/error/EnvelopeErrorControllerTest.kt` | rain-web | pending |  |
| test | `http/error/ErrorCodesParityTest.kt` | dropped | pending | Go/Lease code parity |
| test | `http/error/ErrorEnvelopeGoldenTest.kt` | rain-web | pending |  |
| test | `http/error/ErrorEnvelopeTest.kt` | rain-web | pending |  |
| test | `http/error/FaultKindTest.kt` | rain-web | ported | rain-core FaultTest (kinds, invariants) |
| test | `http/error/UnreadableBodyTest.kt` | rain-web | pending |  |
| test | `http/filter/BodyLimitFilterTest.kt` | rain-web | pending |  |
| test | `http/filter/CrossSiteFilterTest.kt` | rain-web | pending |  |
| test | `http/filter/MountedPathTest.kt` | rain-web | pending |  |
| test | `http/filter/RequestBudgetFilterTest.kt` | rain-web | pending |  |
| test | `http/filter/SecurityHeadersFilterTest.kt` | rain-web | pending |  |
| test | `http/limit/CredentialGateFilterTest.kt` | rain-web | pending |  |
| test | `http/limit/CredentialGateTest.kt` | rain-web | pending |  |
| test | `http/limit/JsonOnlyFilterTest.kt` | rain-web | pending |  |
| test | `http/limit/TokenBucketThrottleTest.kt` | rain-web | pending |  |
| test | `llm/LlmGatewaySpringAiTest.kt` | rain-llm | pending |  |
| test | `llm/LlmPortTest.kt` | rain-llm | pending |  |
| test | `llm/SpringAiConfigurationTest.kt` | rain-llm | pending |  |
| test | `llm/Utf8Test.kt` | rain-llm | pending |  |
| test | `lock/AdvisoryLocksTest.kt` | rain-persistence | pending |  |
| test | `lock/LockKeyTest.kt` | rain-persistence | ported | rain-core LockKeyTest with the same reference vectors |
| test | `lock/LockPropertiesTest.kt` | rain-persistence | pending |  |
| test | `observability/ApplicationLogLevelTest.kt` | rain-observability | pending |  |
| test | `observability/LokiOffTest.kt` | rain-observability | pending |  |
| test | `observability/MutableClock.kt` | rain-observability | pending |  |
| test | `observability/OtelAppenderInstalledTest.kt` | rain-observability | pending |  |
| test | `observability/Transcript.kt` | rain-observability | pending |  |
| test | `observability/health/ActuatorHealthTest.kt` | rain-observability | pending |  |
| test | `observability/health/DatabaseHealthIndicatorTest.kt` | rain-observability | pending |  |
| test | `observability/health/FakeCheck.kt` | rain-observability | pending |  |
| test | `observability/health/HealthCacheTest.kt` | rain-observability | pending |  |
| test | `observability/health/HealthRegistryTest.kt` | rain-observability | pending |  |
| test | `observability/health/ReadinessMappingTest.kt` | rain-observability | pending |  |
| test | `persistence/AssignIdCallbackTest.kt` | rain-persistence | pending |  |
| test | `persistence/ConversionRegistrationTest.kt` | rain-persistence | pending |  |
| test | `persistence/FlywayMigrationSettingsTest.kt` | rain-persistence | pending |  |
| test | `persistence/IdsTest.kt` | rain-persistence | pending |  |
| test | `persistence/TransactionRetryTest.kt` | rain-persistence | pending |  |
| test | `realtime/ChannelNameTest.kt` | rain-realtime | pending |  |
| test | `realtime/RealtimeBackoffTest.kt` | rain-realtime | pending |  |
| test | `realtime/RealtimePublisherTest.kt` | rain-realtime | pending |  |
| test | `redis/RedisWiringTest.kt` | dropped | pending | Boot spring.data.redis |
| test | `resilience/AdmissionGateTest.kt` | rain-resilience | pending |  |
| test | `resilience/BreakerHealthContributionTest.kt` | rain-resilience | pending |  |
| test | `resilience/BreakerTest.kt` | rain-resilience | pending |  |
| test | `workq/AttemptRunnerTest.kt` | rain-jobs | pending |  |
| test | `workq/DefinitionGateTest.kt` | rain-jobs | pending |  |
| test | `workq/JobConcurrencyParityTest.kt` | dropped | pending | Lease job ceilings |
| test | `workq/JobProfileTest.kt` | rain-jobs | pending |  |
| test | `workq/NoParkingInTheHandlerTest.kt` | rain-jobs | pending |  |
| test | `workq/ScheduledTasksIsolationTest.kt` | rain-jobs | pending |  |
| test | `workq/WorkQueueAdminTest.kt` | rain-jobs | pending |  |
| test | `workq/WorkqWiringTest.kt` | rain-jobs | pending |  |
| lease-it | `access/CredentialRepositoryIT.kt` | rain-access | pending |  |
| lease-it | `access/DirectoryStoreIT.kt` | rain-access | pending |  |
| lease-it | `access/RefreshRotationIT.kt` | rain-access | pending |  |
| lease-it | `access/RevocationListIT.kt` | rain-access | pending |  |
| lease-it | `access/SessionRepositoryIT.kt` | rain-access | pending |  |
| lease-it | `persistence/AccessAggregateRoundTripIT.kt` | rain-access | pending |  |
| lease-it | `access/AuthSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `access/DirectorySurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `access/SubjectGrantSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `audit/AuditTrailIT.kt` | rain-audit | pending |  |
| lease-it | `persistence/AuditLogSchemaIT.kt` | rain-audit | pending |  |
| lease-it | `health/RootProbeIT.kt` | rain-observability | pending |  |
| lease-it | `llm/LlmSlotsIT.kt` | rain-llm | pending |  |
| lease-it | `lock/AdvisoryLocksIT.kt` | rain-persistence | pending |  |
| lease-it | `persistence/ApplicationOwnedIdsIT.kt` | rain-data-jdbc | pending |  |
| lease-it | `persistence/ConvertersIT.kt` | rain-data-jdbc | pending |  |
| lease-it | `persistence/OptimisticLockingIT.kt` | rain-data-jdbc | pending |  |
| lease-it | `persistence/JooqReadModelIT.kt` | rain-persistence | pending |  |
| lease-it | `persistence/MigrationIT.kt` | rain-persistence | pending |  |
| lease-it | `persistence/MigrateCommandIT.kt` | rain-persistence | pending |  |
| lease-it | `persistence/ConnectionBudgetIT.kt` | rain-jobs | pending |  |
| lease-it | `realtime/RealtimeListenerIT.kt` | rain-realtime | pending |  |
| lease-it | `surface/AnonymousSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `surface/AuthorizationIT.kt` | rain-sample | pending |  |
| lease-it | `surface/HttpSurfaceIT.kt` | rain-sample | pending |  |
| lease-it | `surface/RouterIT.kt` | rain-sample | pending |  |
| lease-it | `workq/WorkqIT.kt` | rain-jobs | pending |  |
| lease-it | `workq/SchedulerTopologyIT.kt` | rain-jobs | pending |  |
| lease-it | `workq/JobIntentRepositoryIT.kt` | rain-jobs | pending |  |
| lease-it | `workq/AttemptStatementTimeoutIT.kt` | rain-jobs | pending |  |
| lease-it | `ops/DeadJobsIT.kt` | rain-jobs | pending |  |
| lease-it | `OneShotRunnerIT.kt` | rain-sample | pending |  |
| lease-it | `seed/SeedCommandIT.kt` | rain-sample | pending |  |
| lease-it | `persistence/Databases.kt` | rain-test | pending |  |
| lease-it | `persistence/Applications.kt` | rain-test | pending |  |
| lease-it | `persistence/Schemas.kt` | rain-test | pending |  |
| lease-it | `persistence/Slices.kt` | rain-test | pending |  |
| lease-it | `AbstractIntegrationTest.kt` | rain-test | pending |  |
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
