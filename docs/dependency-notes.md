# Dependency notes

Facts about the libraries rain builds on, read from the published jars (Boot 4.1.1, Spring Framework
7.0.9, db-scheduler 16.12.0, Resilience4j 2.4.0, Spring AI 2.0.1). They are recorded because
auto-configuration ordering and exclusion name classes by their fully qualified names, and a wrong
name there fails silently.

## Auto-configuration classes

| Purpose | Class |
|---|---|
| jOOQ `DSLContext` | `org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration` |
| Flyway | `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration` |
| Flyway strategy SPI | `org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy` |
| Flyway customizer SPI | `org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer` |
| Spring Data JDBC repositories | `org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration` |
| DataSource | `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` |
| JdbcTemplate | `org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration` |
| In-memory security user | `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration` |
| Servlet security | `org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration` |
| Security filter registration | `org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration` |
| Redis | `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration` |
| MVC | `org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration` |
| MVC error controller | `org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration` |
| Tomcat customizer | `org.springframework.boot.tomcat.autoconfigure.TomcatWebServerFactoryCustomizer` |
| OpenTelemetry logging | `org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration` |
| Resilience4j circuit breakers | `io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration` |

## Behaviour verified in bytecode

- `WebMvcAutoConfiguration$ProblemDetailsErrorHandlingConfiguration` is gated on
  `spring.mvc.problemdetails.enabled` and its handler bean is
  `@ConditionalOnMissingBean(ResponseEntityExceptionHandler)`. A rain advice extending
  `ResponseEntityExceptionHandler` therefore replaces Boot's without any property.
- Health contracts live in `spring-boot-health`: `org.springframework.boot.health.contributor.{HealthIndicator, HealthContributor, Health, Status}`.
- `org.springframework.boot.EnvironmentPostProcessor` is the Boot 4 SPI; `org.springframework.boot.env.EnvironmentPostProcessor` is the deprecated alias.
- Spring Framework 7 ships `org.springframework.core.retry.{RetryTemplate, RetryPolicy, Retryable, RetryException}`.
- db-scheduler core contains `SchedulerBuilder`, `serializer.Serializer`, `serializer.JacksonSerializer` (Jackson 2) and `stats.MicrometerStatsRegistry`; the Boot starter is not needed. rain supplies a Jackson 3 `Serializer`.
- Spring AI's `ChatModel` is `org.springframework.ai.chat.model.ChatModel` in `spring-ai-model`.
