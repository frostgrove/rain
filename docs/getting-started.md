# Getting started

From an empty Gradle project to a running rain HTTP application, then persistence, jobs and health checks. The
application below is the shape of `samples/rain-sample-minimal`; the build, and every command whose output is shown,
were run against this revision of rain.

## What you need

- JDK 25. The build declares a Java 25 toolchain.
- Gradle 9.7.1, the version rain's own wrapper pins.
- For the second half: a PostgreSQL server, and Docker. Building rain-jobs (and rain-audit, rain-llm) generates jOOQ code
  from the module's migrations on a throwaway PostgreSQL container, or on a server named by `JOOQ_CODEGEN_SERVER_URL`,
  `JOOQ_CODEGEN_SERVER_USER` and `JOOQ_CODEGEN_SERVER_PASSWORD`.

rain's build does not publish artifacts. An application consumes it as an included build: Gradle substitutes
`com.gd.rain:rain-*` with rain's projects.

## The project

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "greeter"

includeBuild("../rain")
```

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-web")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
```

`rain-dependencies` is the one platform: Spring Boot's, Spring AI's and OpenTelemetry's BOMs, the versions of the
third-party libraries rain uses, and rain's own modules, so modules are named without versions. rain-web brings
rain-boot, rain-core and rain-observability. The servlet container is the application's choice; the Web MVC starter
brings Tomcat.

## The application class

```kotlin
package com.example.greeter

import com.gd.rain.boot.command.runRain
import com.gd.rain.core.error.ErrorCodeCatalog
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@SpringBootApplication
@EnableConfigurationProperties(GreetingProperties::class)
class GreeterApplication {
    @Bean
    fun greeterErrorCodes(): ErrorCodeCatalog = GreeterErrorCodes
}

fun main(args: Array<String>) {
    runRain<GreeterApplication>(args)
}
```

`runRain` returns the running context when the process is started with roles, and exits with the command's code when it
is started as a command ([runtime roles and commands](concepts/runtime-roles-and-commands.md)).

## Configuration

`src/main/resources/application.yml` holds what every process of a deployment shares:

```yaml
spring:
  application:
    name: greeter
server:
  forward-headers-strategy: none
rain:
  web:
    body-limit: 16KB
    request-budget: 10s
    client-address: direct
greeter:
  greetings:
    reserved-names: [root, system]
    max-name-length: 64
```

Each value under `rain.web` is required, because each one is a door: how large a body may be, how long a request may
run, and whether a forwarding header is believed. `client-address: direct` has to agree with
`server.forward-headers-strategy: none` ([transport and health](concepts/transport-and-health.md)).

What differs per process is stated in the environment of that process, never inferred:

| Variable | Value |
|---|---|
| `RAIN_DEPLOYMENT_STAGE` | `dev`, `test` or `prod` |
| `RAIN_RUNTIME_ROLES` | a serving process: `api`; with jobs later, `api,worker` or `worker` |
| `RAIN_RUNTIME_COMMAND` | instead of roles, a one-shot process: `config-check`, later `migrate` |

## A section of the application's own

The application's configuration is validated in the same pass as rain's, and its problems are reported in the same
refusal.

```kotlin
package com.example.greeter

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("greeter.greetings")
data class GreetingProperties(
    val reservedNames: Set<String>,
    val maxNameLength: Int,
)

class GreeterConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec("greeter.greetings", GreetingProperties::class, Presence.REQUIRED) { greetings, _ ->
                problems {
                    expect(greetings.maxNameLength in 1..256, "greeter.greetings.max-name-length") {
                        "is ${greetings.maxNameLength}; it is between 1 and 256"
                    }
                    expect(greetings.reservedNames.none(String::isBlank), "greeter.greetings.reserved-names") {
                        "names a blank name"
                    }
                }
            },
        )
}
```

The contributor is read before any bean exists, so it is registered in `src/main/resources/META-INF/spring.factories`:

```properties
com.gd.rain.boot.config.ConfigurationContributor=\
com.example.greeter.GreeterConfigurationContributor
```

## Errors and a controller

```kotlin
package com.example.greeter

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

object GreeterErrorCodes : ErrorCodeCatalog {
    override val owner: String = "greeter"

    val NAME_RESERVED: ErrorCode = ErrorCode.of("name_reserved", "this name is reserved")

    override val codes: List<ErrorCode> = listOf(NAME_RESERVED)
}

data class GreetingRequest(
    val name: String?,
)

data class Greeting(
    val text: String,
    val at: Instant,
)

@RestController
@RequestMapping("/v1/greetings")
class GreetingController(
    private val greetings: GreetingProperties,
    private val clock: Clock,
) {
    @PostMapping
    fun greet(
        @RequestBody request: GreetingRequest,
    ): Greeting {
        val name =
            request.name?.takeIf(String::isNotBlank)
                ?: throw Fault.validation(listOf(Violation(path("name"), RainErrorCodes.REQUIRED)))
        if (name.length > greetings.maxNameLength) {
            throw Fault.validation(listOf(Violation(path("name"), RainErrorCodes.TOO_LONG)))
        }
        if (name in greetings.reservedNames) throw Fault.conflict(GreeterErrorCodes.NAME_RESERVED)
        return Greeting("hello, $name", clock.instant())
    }
}
```

A code reaches a client only when a catalog declares it; the catalog is a bean, and a code declared twice refuses the
start ([errors](concepts/errors.md)). The `Clock` is rain-boot's bean, which a test replaces.

## Running it

```sh
./gradlew bootJar
```

Check the configuration without serving anything:

```sh
RAIN_DEPLOYMENT_STAGE=dev RAIN_RUNTIME_COMMAND=config-check java -jar build/libs/greeter.jar
```

```
configuration: ok (stage=dev)
```

The exit code is 0. A configuration that is wrong never reaches the command; the start is refused with every problem at
once. Started with a role and none of the shared configuration:

```sh
RAIN_RUNTIME_ROLES=api java -jar build/libs/greeter.jar \
  --spring.config.location=optional:classpath:/none.yml --spring.application.name=greeter
```

```
the configuration has 3 problems:
  - rain.deployment.stage [required]: no value is provided; state one of dev, test, prod
  - greeter.greetings [required]: the section is required and no key under it is stated; it needs greeter.greetings.reserved-names, greeter.greetings.max-name-length
  - rain.web [required]: the section is required and no key under it is stated; it needs rain.web.body-limit, rain.web.request-budget, rain.web.client-address
```

Serve:

```sh
RAIN_DEPLOYMENT_STAGE=dev RAIN_RUNTIME_ROLES=api java -jar build/libs/greeter.jar
```

```sh
curl -s http://127.0.0.1:8080/live
{"status":"live"}

curl -s http://127.0.0.1:8080/ready
{"status":"ready","failing":[]}

curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:8080/v1/greetings
{"text":"hello, Ada","at":"2026-09-15T11:52:11.720720201Z"}

curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"root"}' http://127.0.0.1:8080/v1/greetings
{"type":"about:blank","title":"Conflict","status":409,"detail":"this name is reserved","code":"name_reserved"}

curl -s -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8080/v1/greetings
{"type":"about:blank","title":"Unprocessable Content","status":422,"detail":"the request is not valid","code":"validation_failed","errors":[{"pointer":"/name","code":"required","message":"this field is required"}]}
```

Refusals are `application/problem+json`, and every response carries the security headers
([rain-web](modules/web.md)). Readiness is `ready` with no checks: this application has no dependency to ask.

## Adding persistence

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-web")
    implementation("com.gd.rain:rain-persistence")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
```

`spring-boot-starter-jdbc` brings the connection pool; rain-persistence brings jOOQ, Flyway and the PostgreSQL driver.
Add to `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/greeter
    username: greeter
    password: greeter
  flyway:
    enabled: false
rain:
  persistence:
    statement-timeout: 5s
  health:
    checks:
      database: required
```

- `rain.persistence.statement-timeout` has no default: it bounds every jOOQ and `JdbcTemplate` statement, and no value
  is right for every application ([rain-persistence](modules/persistence.md)).
- `rain.health.checks.database` is required as soon as a `DataSource` exists: the `database` check runs, and how much it
  matters is the application's decision ([rain-observability](modules/observability.md)).
- `spring.flyway.enabled: false` keeps migration out of serving processes; it runs as its own step.

The application's migrations go where Flyway looks by default, `src/main/resources/db/migration/`:

```sql
-- V1__greetings.sql
CREATE TABLE greeting (
  id         UUID PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
```

The id column has no default; ids are minted by the application with the `IdGenerator` bean
([schema ownership](concepts/schema-ownership.md)). Migrate, as a command:

```sh
RAIN_DEPLOYMENT_STAGE=dev RAIN_RUNTIME_COMMAND=migrate java -jar build/libs/greeter.jar
```

The output lists every rain module schema on the classpath, then the application. With rain-jobs, added below:

```
migrate: rain_jobs applied 1, now at 1
migrate: application applied 1, now at 1
```

## Adding jobs

```kotlin
implementation("com.gd.rain:rain-jobs")
```

Declare a profile, a definition and its handler as beans:

```kotlin
package com.example.greeter

import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.BackoffLadder
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobProfile
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

data class GreetingSent(
    val name: String,
)

@Configuration(proxyBeanMethods = false)
class GreetingJobs {
    @Bean
    fun standardProfile(): JobProfile =
        JobProfile(
            id = "standard",
            attemptTimeout = Duration.ofMinutes(1),
            stepTimeout = Duration.ofSeconds(10),
            backoff = BackoffLadder(initial = Duration.ofSeconds(1), maximum = Duration.ofMinutes(5)),
            retries = 5,
            deferrals = 20,
            retention = Duration.ofDays(14),
        )

    @Bean
    fun greetingSent(): JobDefinition<GreetingSent> = JobDefinition.of("greeting.sent", "standard")

    @Bean
    fun greetingSentHandler(definition: JobDefinition<GreetingSent>): JobHandler<GreetingSent> =
        object : JobHandler<GreetingSent> {
            override val definition = definition

            override fun handle(
                payload: GreetingSent,
                attempt: Attempt,
            ) {
                log.info("greeted {} (attempt {})", payload.name, attempt.meta.attempt)
            }
        }

    private companion object {
        val log = LoggerFactory.getLogger(GreetingJobs::class.java)
    }
}
```

Enqueue from the controller, which now also takes the `WorkQueue` and the definition:

```kotlin
queue.enqueue(greetingSent, GreetingSent(name), EnqueueOptions(Dedupe.None, JobPriority(0)))
```

Deduplication and priority decide behaviour, so they are always stated. Configure the worker:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 24
rain:
  jobs:
    workers:
      greeting.sent: 2
    required-recurring: []
    drain-grace: 20s
    reserved-connections: 8
  health:
    checks:
      database: required
      jobs: degrading
```

| Property | Why it is stated |
|---|---|
| `rain.jobs.workers.greeting.sent` | every declared definition has a ceiling of concurrent attempts per process |
| `rain.jobs.required-recurring` | the recurring work this deployment runs; an empty list is a statement too |
| `rain.jobs.drain-grace` | how long a stopping worker waits for running attempts |
| `rain.jobs.reserved-connections` | connections kept for everything that is not a job scheduler |
| `spring.datasource.hikari.maximum-pool-size` | a worker checks that its demand fits: 2 profile threads + 2 recurring threads (rain's reaper and retention) + 2 schedulers × 4 db-scheduler connections + 1 lease renewer + 8 reserved = 21 |
| `rain.health.checks.jobs` | a worker runs the `jobs` check |

Run one process with both roles:

```sh
RAIN_DEPLOYMENT_STAGE=dev RAIN_RUNTIME_ROLES=api,worker java -jar build/libs/greeter.jar
```

```
started job scheduler standard with 2 threads
started job scheduler recurring with 2 threads
```

A greeting posted to `/v1/greetings` is enqueued in the request and handled by the worker:

```
greeted Grace (attempt 1)
```

The same image serves as separate processes. With `RAIN_RUNTIME_ROLES=api` the process enqueues and starts no
scheduler; its start logs that the `jobs` entry names a check it does not run (`not_evaluated`), because one
configuration serves every role. With `RAIN_RUNTIME_ROLES=worker` the process runs the schedulers and serves only the
probes. `config-check` runs with no role, so it reports the same `not_evaluated` line and exits 0.

## A health check of your own

Any `HealthCheck` bean joins readiness at the importance the application states:

```kotlin
@Bean
fun mailRelayHealthCheck(relay: MailRelay): HealthCheck =
    object : HealthCheck {
        override val name = "mail.relay"
        override val code = "mail"
        override val timeout: Duration? = null

        override fun probe() {
            check(relay.isConnected()) { "the mail relay is not connected" }
        }
    }
```

```yaml
rain:
  health:
    checks:
      mail.relay: degrading
```

Without that entry the process does not start. A failing `degrading` check turns readiness to `degraded` and keeps the
process in rotation; a failing `required` check makes it `not_ready` (503).

## Where to go next

- [Conventions](conventions.md) — what an application and a rain module follow.
- [Configuration](concepts/configuration.md), [runtime roles and commands](concepts/runtime-roles-and-commands.md),
  [errors](concepts/errors.md), [schema ownership](concepts/schema-ownership.md),
  [transport and health](concepts/transport-and-health.md).
- Module pages: [core](modules/core.md), [boot](modules/boot.md), [web](modules/web.md),
  [observability](modules/observability.md), [persistence](modules/persistence.md), [data-jdbc](modules/data-jdbc.md),
  [crud](modules/crud.md), [audit](modules/audit.md), [jobs](modules/jobs.md), [realtime](modules/realtime.md),
  [resilience](modules/resilience.md), [llm](modules/llm.md), [test](modules/test.md).
