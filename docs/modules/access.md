# rain-access

Who a caller is and what it may do: password sign-in with database sessions and rotating refresh credentials, a
short-lived HS256 access token, a revocation list on the application's Redis, role and permission grants over a
catalogue the application declares, a start-up verification that every mounted route declares its access, the
enforcement of each declaration before its handler runs, and a gate in front of the credential routes. Its tables live in
schema `rain_access`.

rain-access knows a caller as a subject: a type and an id. Whatever stands behind a subject type — people, services,
customers — belongs to the application. rain-access asks that type's directory whether a subject is active and how it
is described, and no table of it references a table of the application.

Add it when callers sign in and what a route may do depends on what they were granted. Adding it changes every route of
the application: in a process with the `api` role, a route that declares no access refuses the start.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-access")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // only when rain.access.revocation.store or rain.access.attempts.store is redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
```

It brings [rain-web](web.md), [rain-audit](audit.md), [rain-jobs](jobs.md) and [rain-resilience](resilience.md) — and
through them [rain-persistence](persistence.md) and [rain-observability](observability.md) — Spring Boot's Spring
Security module, Spring Security's JOSE support for the token, and Bouncy Castle for Argon2id. Building rain-access
itself generates its jOOQ code from its migration.

The Redis client is the application's. rain-access compiles against Spring Data Redis and does not bring it, so an
application whose revocation list and attempt counters are not on Redis carries no client. An application that states a
store as `redis` adds Spring Boot's Data Redis module (`spring-boot-starter-data-redis`, or `spring-boot-data-redis`,
which brings Lettuce); without it the start is refused.

What it brings comes with its own requirements: `rain.web`, `rain.persistence.statement-timeout` and `rain.jobs` are
required, and a `worker` runs rain-access's recurring work on rain-jobs' schedulers.

## Concepts

| Declared by the application | What it is |
|---|---|
| `MountedSubject(type, normalization)` | a kind of subject the credential routes serve, and the `IdentifierNormalization` its identifiers are stored and looked up by; at least one |
| `SubjectDirectory` | the module that owns one subject type; exactly one per mounted type |
| `SubjectRegistrar` | lets a caller with no account create a subject of its type; at most one per type; the sign-up route exists only when one is a bean |
| `ModuleGrants(module, permissions, roles)` | the permissions one module declares, and the codes it gives each declared system role |
| `SystemRoleDeclaration(slug, name, grantsEveryPermission)` | a role the application owns |
| `SurfaceExemption(handlerType, reason)` | a handler type the surface verification and the enforcement leave alone, with the reason |
| `@Access`, `DeclaresItsOwnAccess`, `MountsItsOwnSurface` ([rain-web](web.md#declaring-access)) | what each mounted route needs |

Everything but `@Access` is a bean. rain-access ships no subject type, no system role and no application permission; it
declares the six permissions of its own routes, as module `access`, and nothing else.

## What it contributes

`RainAccessAutoConfiguration` — after Boot's jOOQ auto-configuration, `RainRuntimeAutoConfiguration`,
`RainPersistenceAutoConfiguration`, `RainAuditAutoConfiguration`, `RainResilienceAutoConfiguration`,
`RainWebErrorAutoConfiguration`, Resilience4j's `BulkheadAutoConfiguration` and Boot's `DataRedisAutoConfiguration`,
before Boot's servlet web security and security filter auto-configurations, when a `DSLContext`, a
`PlatformTransactionManager` and an `AuditRecorder` exist.

`META-INF/spring.factories` registers `AccessConfigurationContributor` and an `AutoConfigurationImportFilter` that keeps
Boot's `UserDetailsServiceAutoConfiguration` out of the application: it would create an account named `user` with a
password printed to the log, which nothing revokes and nobody chose. Nothing else is filtered.

Every process, commands included:

| Bean | Condition | What it is |
|---|---|---|
| `accessErrorCodes` | — | `AccessErrorCodes` |
| `accessFaultTranslator` | — | the first `FaultTranslator`: an audit row that cannot be written, a revocation list or an attempt store that cannot be asked become retryable refusals |
| `accessSignedInEvent` … `accessDefaultRoleChangedEvent` | — | the twelve `AuditEventType` beans of [evidence](#evidence) |
| `accessModuleGrants` | — | module `access` with the permissions of rain-access's own routes |
| `accessSubjectRegistry`, `accessSubjectRegistryCheck` | — | the served subject types, and the check that mounts, directories and registrars agree |
| `accessGrantDeclarationsCheck` | — | the grant declarations agree with each other |
| `accessSigningKey`, `accessTokenIssuer`, `accessTokenVerifier`, `accessSessionFingerprints` | — | the resolved signing key and what it keys |
| `accessHashingBulkhead`, `accessHashingBulkheadCheck` | — | password hashing inside the Resilience4j bulkhead `rain-access-hashing`, and the check that the instance is configured explicitly |
| `accessNoRevocationList` | `revocation.store: none` | the list that announces nothing |
| `accessMemoryAttemptLimiter` | `attempts.store: memory` | attempt counters in this process |
| `accessCredentialStore`, `accessSessionStore`, `accessGrantStore`, `accessCatalogueStore` | no other bean of the type | the jOOQ stores |
| the use cases | — | sign-in, sign-up, refresh, sign-out, sign-out everywhere, password change and set, role and grant administration |
| `accessProvisioning` | no other `AccessProvisioning` bean | [`AccessProvisioning`](#accessprovisioning) |
| `grantsLookup` | no other `GrantsLookup` bean | [`GrantsLookup`](#grantslookup) |
| `accessPrincipalActor` | no other `CurrentActor` bean | the authenticated subject as the actor rain-audit records |

Roles `api`, `worker` and `seeder`:

| Bean | What it is |
|---|---|
| `accessCatalogueSynchronizer` | writes the declared catalogue when the context starts, in a lifecycle phase before every other, the web server's included |

Role `worker`:

| Bean | What it is |
|---|---|
| `accessSessionRetention` | the recurring work `access.session-retention` |

With Spring Data Redis on the classpath and `revocation.store: redis`:

| Bean | Role | What it is |
|---|---|---|
| `accessRevocationRedis` | every | the `RedisConnectionFactory` named or qualified `rainRevocation`, otherwise the application's one factory |
| `accessRevocationList` | every | the revocation list on that server |
| `accessRevocationServerCheck` | `api`, `worker` | the server keeps every key it is given |
| `accessRevocationHealthCheck` | `api`, `worker` | the `access.revocation` check |
| `accessRevocationReplay` | `worker` | the recurring work `access.revocation-replay` |

With Spring Data Redis on the classpath and `attempts.store: redis`:

| Bean | Role | What it is |
|---|---|---|
| `accessAttemptsRedis` | every | the `RedisConnectionFactory` named or qualified `rainAttempts`, otherwise the application's one factory |
| `accessRedisAttemptLimiter` | every | attempt counters on that server |
| `accessAttemptsServerCheck` | `api` | the server keeps every key it is given |
| `accessAttemptsHealthCheck` | `api` | the `access.attempts` check |

Without Spring Data Redis on the classpath, `accessRedisClientCheck` refuses a store stated as `redis`.

Servlet applications, every role:

| Bean | Condition | What it is |
|---|---|---|
| `accessSecurityFilterChain` | — | the one `SecurityFilterChain`, so Spring Boot's default chain never applies |
| `accessRequestPrincipal` | no other `RequestPrincipal` bean | the request log's `principal`: `type:id` |
| `accessErrorControllerExemption` | — | a `SurfaceExemption` for `ErrorController`: the error dispatch renders the refusal of a request whatever that request declared |
| `accessCredentialCookies`, `accessPageRequest` | — | the credential cookies and the page parameters |

Servlet applications, role `api`:

| Bean | Condition | What it is |
|---|---|---|
| `accessAuthController` | — | the credential routes |
| `accessSignUpController` | a `SubjectRegistrar` bean exists | the sign-up route |
| `accessRoleController`, `accessPermissionController`, `accessSubjectGrantController` | — | the administration routes |
| `accessDeclarations`, `accessEnforcementInterceptor`, `accessWebMvcConfigurer` | — | the enforcement of every declaration before its handler |
| `accessSurfaceVerifier` | — | the start-up surface verification |
| `accessJsonOnlyFilterRegistration` | — | filter `rainAccessJsonOnlyFilter` |
| `accessCredentialThrottleFilterRegistration` | — | filter `rainAccessCredentialThrottleFilter` |

A worker has the security chain and no route of rain-access; rain-web's probe-only filter answers everything but the
probes. `config-check` runs the subject, grant, bulkhead and Redis client checks; the surface verification and the Redis
server checks run only in the processes that have them.

## Configuration

`rain.access` is required whenever rain-access is on the classpath. Every choice that decides behaviour is required;
tuning numbers carry declared defaults. Every count is at least 1 and every duration positive, and a value outside its
bound is refused, never corrected.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.access.web.base-path` | required | the path every rain-access route is mounted under; the refresh cookie's path derives from it | one or more path segments with no trailing slash, `^(/[A-Za-z0-9._~-]+)+$` |
| `rain.access.web.delivery` | required | where a sign-in's credentials go: `cookies`, `body` or `both` ([delivery](#delivery)) | one of the three |
| `rain.access.web.page.default-size` | `50` | items of a list route that states no `limit` | at least 1 |
| `rain.access.web.page.max-size` | `200` | the largest `limit` a list route and `GrantsLookup.directPermissionsOf` accept | at least 1; not below `default-size` (`contradicts`) |
| `rain.access.web.max-bulk-ids` | `100` | the most ids one bulk request names: `POST <base>/roles/bulk-delete` | at least 1 |
| `rain.access.token.issuer` | required | the token's `iss`, and the only one accepted | not blank |
| `rain.access.token.audience` | required | the token's `aud`, and the only one accepted | not blank |
| `rain.access.token.signing-key` | required | the HS256 key ([signing key](#signing-key)) | resolves to at least 32 bytes; in `prod` not a raw literal, and from an environment variable |
| `rain.access.token.access-ttl` | required | how long an access token answers | positive; not longer than `session.idle-ttl` (`contradicts`) |
| `rain.access.session.ttl` | required | a session's absolute lifetime; no rotation moves it | positive |
| `rain.access.session.idle-ttl` | required | a session not rotated for this long is unusable | positive; not longer than `session.ttl` (`contradicts`) |
| `rain.access.session.refresh-grace` | `10s` | how long after a rotation the superseded refresh credential still rotates | positive; shorter than `session.idle-ttl` (`contradicts`) |
| `rain.access.session.rotation-attempts` | `3` | how many times a rotation that lost its compare-and-set reads the session again | at least 1 |
| `rain.access.session.revoke-batch` | `500` | sessions closed per transaction when every session of a subject is closed; also holders and permissions removed per transaction when a role is deleted | at least 1 |
| `rain.access.session.retention.keep-for` | required | how long an expired or closed session is kept | positive |
| `rain.access.session.retention.interval` | required | how often retention runs | positive |
| `rain.access.session.retention.batch` | `500` | rows one retention statement deletes | at least 1 |
| `rain.access.session.retention.batches-per-run` | `20` | batches of each kind one run takes | at least 1 |
| `rain.access.password.revoke-other-sessions-on-change` | required | whether a subject changing its own password closes its other sessions | a boolean |
| `rain.access.password.min-length` | `10` | the shortest new password, in Unicode code points | at least 1; not above `max-bytes` (`contradicts`) |
| `rain.access.password.max-bytes` | `256` | the longest password, in UTF-8 bytes: what the hasher reads | at least 1 |
| `rain.access.password.max-identifier-bytes` | `320` | the longest identifier, in UTF-8 bytes | at least 1 |
| `rain.access.hashing.queue` | required | callers that may wait for a hashing permit at once; one more is refused at once | at least 1 |
| `rain.access.hashing.argon2.salt-bytes` | `16` | Argon2id salt length | at least 1 |
| `rain.access.hashing.argon2.hash-bytes` | `32` | Argon2id hash length | at least 1 |
| `rain.access.hashing.argon2.parallelism` | `4` | Argon2id lanes | at least 1 |
| `rain.access.hashing.argon2.memory-kib` | `65536` | Argon2id memory | at least 1 |
| `rain.access.hashing.argon2.iterations` | `3` | Argon2id passes | at least 1 |
| `rain.access.attempts.store` | required | where failed attempts are counted: `redis` or `memory` | `memory` is refused in `prod` |
| `rain.access.attempts.per-identifier` | `10` | failures of one identifier of one subject type before it locks | at least 1 |
| `rain.access.attempts.per-address` | `50` | failures from one client address before it locks | at least 1 |
| `rain.access.attempts.window` | `15m` | the window a counter opens with its first failure | positive |
| `rain.access.attempts.lock-for` | `15m` | how long a locked key refuses | positive |
| `rain.access.attempts.memory.maximum-keys` | required when the store is `memory` | the most counters one process holds | at least 1; stated while the store is `redis` is `contradicts` |
| `rain.access.attempts.redis.key-prefix` | required when the store is `redis` | the prefix of every counter key | `^[A-Za-z0-9_.:-]{1,64}$`; stated while the store is `memory` is `contradicts` |
| `rain.access.attempts.redis.eviction-policy-attested` | absent | `noeviction`, stated after verifying it out of band, for a server that will not answer `CONFIG` | `noeviction` |
| `rain.access.revocation.store` | required | where closed sessions are announced: `redis` or `none` | one of the two |
| `rain.access.revocation.redis.key-prefix` | required when the store is `redis` | the prefix of every revocation key | `^[A-Za-z0-9_.:-]{1,64}$`; not `attempts.redis.key-prefix` while that store is `redis` too (`contradicts`); stated while the store is `none` is `contradicts` |
| `rain.access.revocation.redis.replay.interval` | required when the store is `redis` | how often the worker tells the list again what the database records | positive; shorter than `token.access-ttl` (`contradicts`) |
| `rain.access.revocation.redis.replay.page-size` | `500` | rows of one replay page | at least 1 |
| `rain.access.revocation.redis.replay.pages-per-run` | `20` | pages of each kind one replay run reads | at least 1 |
| `rain.access.revocation.redis.eviction-policy-attested` | absent | as for the attempt counters | `noeviction` |
| `rain.access.gate.throttle.per-minute` | required | tokens per minute of one client address's bucket on the credential routes | at least 1 |
| `rain.access.gate.throttle.burst` | required | that bucket's size | at least 1 |
| `rain.access.gate.throttle.callers` | required | client addresses the throttle tracks at once | at least 1 |
| `rain.access.grants.max-roles-per-subject` | required | the most roles one subject may hold; it bounds the permission question | at least 1 |
| `rain.access.provisioning.holder-page-size` | `100` | holders one page of `usableHolderOf` reads | at least 1 |
| `rain.access.provisioning.holder-page-budget` | `10` | pages `usableHolderOf` reads before it answers not evaluated | at least 1 |
| `rain.access.catalogue.chunk-size` | `500` | declared permissions written per statement by the catalogue synchronisation | at least 1 |

An enum value that is not one of the written names is a binding problem, not a default.

Stage `prod` refuses `attempts.store: memory` — counters in one process are not shared by its replicas, so every replica
admits the whole ceiling again — and a signing key written as a raw literal. The signing key comes from an environment
variable there.

### Signing key

| Written | Resolves to | Stage |
|---|---|---|
| `base64:<key>` | the key in strict RFC 4648 base64: the standard alphabet, padded, no whitespace, canonical, so one key has one spelling | every stage |
| `file:<absolute path>` | the file's content in the same strict base64, optionally followed by one line feed | every stage |
| anything else | the UTF-8 bytes of the literal | refused in `prod` |

The material is at least 32 bytes, HS256's key size. Nothing judges how random it looks: a length is a rule, an entropy
estimate is a guess. A refusal names `rain.access.token.signing-key` and quotes neither the key nor the file's path.
`openssl rand -base64 32` writes a key in the accepted form; in `prod` it is stated in the environment, e.g.
`RAIN_ACCESS_TOKEN_SIGNINGKEY=file:/run/secrets/access-signing-key`.

### What the deployment states besides `rain.access`

```yaml
resilience4j:
  bulkhead:
    instances:
      rain-access-hashing:
        max-concurrent-calls: 4
        max-wait-duration: 1s
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
rain:
  jobs:
    required-recurring: [access.session-retention, access.revocation-replay]
  health:
    checks:
      database: required
      jobs: degrading
      access.revocation: required
      access.attempts: required
```

| Property | Why it is stated |
|---|---|
| `resilience4j.bulkhead.instances.rain-access-hashing.max-concurrent-calls` | how many hashes are derived at once; stated on the instance itself, at least 1 |
| `resilience4j.bulkhead.instances.rain-access-hashing.max-wait-duration` | how long a caller waits for a permit; stated on the instance itself, not negative; it is also the `Retry-After` of `503 overloaded` |
| `rain.jobs.required-recurring` | in the `worker` role it names every `RecurringWork` bean: `access.session-retention` always, `access.revocation-replay` when `revocation.store` is `redis`; each is also a thread, and a connection, in the worker's demand ([rain-jobs](jobs.md#configuration)) |
| `rain.health.checks.access.revocation` | when `revocation.store` is `redis`, in `api` and `worker` |
| `rain.health.checks.access.attempts` | when `attempts.store` is `redis`, in `api` |
| `spring.data.redis.*` | Boot's connection factory, when a store is `redis` and the application defines no `RedisConnectionFactory` of its own |

A store that should live on a server of its own is given one by the application: a `RedisConnectionFactory` bean named
or qualified `rainRevocation` serves the revocation list, one named or qualified `rainAttempts` the attempt counters.
Without one, the store uses the application's `RedisConnectionFactory`.

### Bean-time problems

| Path | Code | When | Runs in |
|---|---|---|---|
| `access.subject` | `required` | no `MountedSubject` bean | every process |
| `access.subject:<type>` | `contradicts` | a type mounted twice; two directories or two registrars for a type; a directory or a registrar for a type nothing mounts | every process |
| `access.subject:<type>` | `required` | a mounted type no `SubjectDirectory` serves | every process |
| `access.grants:<module>` | `contradicts` / `invalid` | a module declaring its grants twice / giving a role a code the module does not declare | every process |
| `access.permission:<code>` | `contradicts` | a code declared by more than one module, naming each | every process |
| `access.system-role:<slug>` | `contradicts` / `required` | a system role declared twice / a module giving codes to a role no `SystemRoleDeclaration` declares | every process |
| `resilience4j.bulkhead.instances.rain-access-hashing` | `required` | the bulkhead auto-configuration is not active, or the instance is not stated | every process |
| `resilience4j.bulkhead.instances.rain-access-hashing.max-concurrent-calls`, `.max-wait-duration` | `required` / `invalid` | not stated on the instance / below 1, negative | every process |
| `resilience4j.bulkhead.instances.rain-access-hashing` | `contradicts` | a `BulkheadConfigCustomizer` names the instance | every process |
| `rain.access.revocation.store`, `rain.access.attempts.store` | `contradicts` | `redis` without Spring Data Redis on the classpath | every process |
| `access.surface:<METHOD path>` | `required` / `contradicts` / `invalid` | see [the surface](#the-surface-verification) | `api` |
| `access.surface:functional-route` | `invalid` | a functional route the verification cannot read | `api` |
| `rain.access.revocation.redis`, `rain.access.attempts.redis` | `invalid` | the server evicts keys, naming its policy, or is not reachable | revocation: `api`, `worker`; attempts: `api` |
| `rain.access.revocation.redis.eviction-policy-attested`, `rain.access.attempts.redis.eviction-policy-attested` | `required` / `not_evaluated` | the server will not say what it evicts, and the deployment did not attest / did attest `noeviction` | as above |

## Subjects and grants

```kotlin
@Configuration(proxyBeanMethods = false)
class HelpdeskAccess {
    @Bean
    fun agentsMounted(): MountedSubject = MountedSubject(SubjectType("agent")) { it.trim().lowercase() }

    @Bean
    fun agents(jdbc: JdbcTemplate): SubjectDirectory = AgentDirectory(jdbc)

    @Bean
    fun ticketGrants(): ModuleGrants =
        ModuleGrants(
            "tickets",
            listOf(PermissionDef("ticket.read", "Read tickets"), PermissionDef("ticket.close", "Close tickets")),
            roles = mapOf("triage" to setOf("ticket.read")),
        )

    @Bean
    fun administrator(): SystemRoleDeclaration = SystemRoleDeclaration("administrator", "Administrator", grantsEveryPermission = true)

    @Bean
    fun triage(): SystemRoleDeclaration = SystemRoleDeclaration("triage", "Triage", grantsEveryPermission = false)
}

class AgentDirectory(
    private val jdbc: JdbcTemplate,
) : SubjectDirectory {
    override val subjectType = SubjectType("agent")

    override fun isActive(id: UUID): Boolean =
        jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM agent WHERE id = ? AND active)", Boolean::class.java, id) == true

    override fun describe(id: UUID): Profile? =
        jdbc.query("SELECT name, email FROM agent WHERE id = ?", { row, _ -> Profile(row.getString(1), row.getString(2)) }, id).firstOrNull()

    override fun signedIn(
        id: UUID,
        at: Instant,
    ) {
        jdbc.update("UPDATE agent SET last_signed_in_at = ? WHERE id = ?", Timestamp.from(at), id)
    }
}
```

| Type | Rules |
|---|---|
| `SubjectType(name)` | matches `^[a-z][a-z0-9_-]{0,63}$`, the pattern an audit actor type obeys, so a subject is always recordable as an actor; a value, so a second kind of caller costs a `MountedSubject` and a `SubjectDirectory` and no change to rain |
| `SubjectRef(type, id)` | the pair every grant, credential and session is scoped by; `resourceId` is `type:id` |
| `IdentifierNormalization` | `normalize(presented)`; credentials are stored and looked up by the exact normalised string, on sign-in, sign-up, enrolment and when an operator sets a password |
| `SubjectDirectory` | `isActive(id)` is asked on every authenticated request, before every rotation, at sign-in and before a grant; `describe(id)` answers the `Profile(displayName, identifier, attributes)` shown to a client, or null; `signedIn(id, at)` runs inside the sign-in and sign-up transactions, so a failure refuses the sign-in |
| `SubjectRegistrar` | `register(SignUp(identifier, profile))` creates the subject and answers its id, inside the sign-up transaction together with the credential and the first session, so an account never exists without the credential it signs in with |
| `PermissionDef(code, name)` | `code` has two to four dot-separated segments, `^[a-z][a-z0-9_-]{0,31}(\.[a-z][a-z0-9_-]{0,31}){1,3}$`; `name` is 1 to 256 characters |
| `ModuleGrants(module, permissions, roles)` | `module` matches `^[a-z][a-z0-9_-]{0,63}$`; a role named in `roles` is a declared system role, and a code named there is one of `permissions` |
| `SystemRoleDeclaration(slug, name, grantsEveryPermission)` | `slug` matches `^[a-z][a-z0-9-]{0,63}$`; `name` is 1 to 256 characters; with `grantsEveryPermission` the role holds every permission there is, including ones declared after it, with no row per permission |

A directory's methods may run inside a transaction rain-access opened, so an implementation that reads the database
through the application's `JdbcTemplate` or `DSLContext` joins it rather than opening its own. Deactivating a subject in
its directory ends its access at the next request: the access token of an inactive subject is `401`, and its refresh
credential no longer rotates.

### The catalogue

Declarations are written by `CatalogueSynchronizer` when the context starts in the `api`, `worker` and `seeder` roles,
before the web server starts:

- every declared permission is a row of `permissions`, merged in sorted chunks of `catalogue.chunk-size`; a code that
  already has a row keeps its row, name and module;
- every `SystemRoleDeclaration` is a row of `roles` marked system, with its `grants_every_permission` as declared; a role
  that already has the slug is claimed as that system role;
- every code a module gives a system role is attached to it.

Nothing is detached or deleted: a code taken out of the declarations keeps its row and its attachments. Each statement is
idempotent, so replicas starting together agree. Declarations that contradict each other are refused by the grant
declarations check and never written.

rain-access's own routes declare these permissions, module `access`:

| Code | Name |
|---|---|
| `access.role.read` | Read roles and the permission catalogue |
| `access.role.write` | Create and rename roles, attach and detach their permissions |
| `access.role.delete` | Delete roles |
| `access.grant.read` | Read what a subject was granted |
| `access.grant.write` | Grant and revoke roles and permissions |
| `access.credential.write` | Set another subject's password |

A system role that grants every permission holds them; any other role is given them by an operator or by
`AccessProvisioning.ensureRole`.

### Roles and grants

A system role refuses rename, delete and detach with `403 system_role`; attaching a permission to it is allowed. An
application role is created with an explicit slug and deleted in bounded steps: its holders, then its permissions, are
removed `session.revoke-batch` rows per transaction, then the role row. A bulk delete names at most `web.max-bulk-ids`
ids — more is `400 bad_request` before any id is read — and reads every named role first: an unknown id is `404`, a
system role `403 system_role`, and nothing is deleted unless every one may go. A role named twice is deleted once, and
each deletion is recorded on its own (`RoleAdministrationRulesTest`, `DirectorySurfaceIT`).

A subject holds roles and direct permissions. Granting needs the subject to be active in its directory (`422
unusable_subject`) and, for a role, within `grants.max-roles-per-subject` (`409 too_many_roles`), counted up to the
ceiling and no further under an advisory lock per subject. Revoking asks nothing about the subject: taking a role from a
subject that was just deactivated is exactly what an operator does next. A grant already held and a revoke of nothing
succeed and record nothing.

A subject type's default role, bound with `AccessProvisioning.setDefaultRole`, is granted to every subject a sign-up
creates. The database holds one password credential per subject (`uq_credentials_subject_password`) and one subject per
identifier within a type (`uq_credentials_identifier`); an insert overwrites nothing, and which index absorbed it is read
back rather than parsed from an error.

## The surface

### Declaring access

| Declaration | A request passes when |
|---|---|
| `@Access(public = true, why = "…")` | always |
| `@Access(authenticated = true, why = "…")` | it authenticated as an active subject; otherwise the refusal [kept](#the-security-chain) for the token it presented, or `401 unauthenticated` when it presented none |
| `@Access(permissions = ["ticket.read", …])` | its subject holds every named code — directly, through a role, or through a role that grants every permission; a request without a principal is refused as for `authenticated`, a missing code is `403 forbidden` |

`@Access` is read from the handler method, else from its class, composed annotations included. A controller whose
routes are derived from a table implements `DeclaresItsOwnAccess` and answers an `EndpointDeclaration` per method and
pattern, as rain-crud's resources do. A functional `RouterFunction` route is declared by a `MountsItsOwnSurface` bean;
rain-web declares its probes that way.

```kotlin
@RestController
@RequestMapping("/tickets")
class TicketController(
    private val tickets: TicketQueries,
) {
    @Access(permissions = ["ticket.read"])
    @GetMapping
    fun list(): List<TicketView> = tickets.firstPage()

    @Access(public = true, why = "the status page is shown before anyone signs in")
    @GetMapping("/status")
    fun status(): StatusView = tickets.status()
}

@Bean
fun reportRoutes(reports: Reports): RouterFunction<ServerResponse> =
    RouterFunctions.route().GET("/reports/daily") { ServerResponse.ok().body(reports.daily()) }.build()

@Bean
fun reportSurface(): MountsItsOwnSurface =
    object : MountsItsOwnSurface {
        override fun mountedDeclarations() = listOf(EndpointDeclaration("GET", "/reports/daily", permissions = listOf("ticket.read")))
    }
```

### The surface verification

`AccessSurfaceVerifier` is a configuration check of every `api` process. It reads Spring MVC's
`requestMappingHandlerMapping` and `routerFunctionMapping` and refuses the start, naming each route as
`access.surface:<METHOD path>`, when:

| Code | Found |
|---|---|
| `required` | a request mapping whose handler declares nothing, whatever package or library its controller comes from |
| `required` | a readable functional route no `MountsItsOwnSurface` declares |
| `contradicts` | one method and path declared twice |
| `contradicts` | a `DeclaresItsOwnAccess` declaration for a route nothing mounts |
| `invalid` | a declaration that is public and authenticated, public and permissioned, names an empty permission, declares nothing, or is public or authenticated with no `why` |
| `invalid` | a permission no `ModuleGrants` declares |
| `invalid` (path `access.surface:functional-route`) | a functional route whose predicate is not a conjunction of at most one method and one path per nesting level, plus predicates that only narrow a request (headers, parameters, versions): a disjunction, a negation, a resource route, a route with no path |

A mapping without a method answers every method no other mapping of its pattern names, and is verified for each of them.
A functional route with no method predicate answers every method. The one exemption is a `SurfaceExemption` bean whose
`handlerType` the handler bean is assignable to; rain-access declares one for `ErrorController`.

### Enforcement

`AccessEnforcementInterceptor` runs before every handler of a request mapping or a functional route, before the handler's
arguments — its body included — are read. It finds the declaration the verification checked: for a request mapping by
the handler and its best-matching pattern, for a functional route by `METHOD pattern` — the pattern Spring's
`RouterFunctionMapping` records in `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, as a request mapping's is
(`FunctionalRoutesAreVerifiedTest` routes through Spring's own mapping) — where a `HEAD` request with no `HEAD` declaration
is held to the route's `GET` declaration (a `GET` route answers `HEAD`, RFC 9110 §9.3.2). A public declaration passes;
otherwise the request needs a principal — without one it is refused with the refusal the security chain
[kept](#the-security-chain) for the credential it presented, or `401 unauthenticated` when it presented none — and a
permissioned one asks `GrantsLookup.heldBy` for exactly the named codes, once per request. A request that reaches a
handler with no declaration is an internal failure, never a pass. A route another handler mapping serves — a WebSocket
upgrade — is not enforced here: its `MountsItsOwnSurface` declaration is checked for form and duplicates, and the route
checks what it declares before it serves.

### The security chain

The one `SecurityFilterChain` is stateless and authorizes nothing by path: no login page, no basic authentication, no
logout filter, no request cache, no CSRF token (rain-web's cross-site filter refuses a forged write), no header writer
(rain-web writes the security headers) and no CORS processing (rain-web's). Spring Security's own `401` and `403` are
problem documents with a code and no `WWW-Authenticate` header, so no browser prompts for a password.

In it, `AccessAuthenticationFilter` turns a presented access token into an `AccessPrincipal`. A request presenting no
token continues anonymous; whether its route needs a caller is the route's declaration. A presented token that
authenticates nobody refuses nothing in the filter either: the request continues anonymous, and why the token failed is
kept on it. A public route serves it as the anonymous request it is — a browser still holding the cookie of a closed
session, or of an expired token, signs in again and refreshes — and a route whose [enforcement](#enforcement) needs a
caller answers with the kept refusal (`StaleCredentialDoesNotBlockPublicRoutesTest`, `RedisStoresApplicationIT`). The
first of these the filter finds is kept:

| Found | Kept refusal |
|---|---|
| two access cookies | `401 unauthenticated`, detail "the request carries more than one access cookie" |
| two `Authorization` headers | `401 unauthenticated`, "the request carries more than one Authorization header" |
| a token in a cookie and in a header | `401 unauthenticated`, "the request presents an access token in a cookie and in a header" |
| the token does not verify, an expired one included | `401 unauthenticated`, "the access token is not valid" |
| its subject type is not served | `401 unauthenticated`, "the access token names a subject type this application does not serve" |
| its session was closed, by itself or by a cutoff of its subject | `401 unauthenticated`, "the session has been closed" |
| the revocation list cannot be asked | `503 revocation_unavailable` |
| its subject is not active | `401 unauthenticated`, "the subject is not active" |

A route another handler mapping serves reads no kept refusal: the check it makes of its own declaration sees an anonymous
request.

An `Authorization` header with a scheme other than `Bearer` (compared case-insensitively), or a value that is not one
token, presents nothing. The principal is kept on the request, so the request log names it after the security context is
cleared.

## API

### AccessPrincipal

`AccessPrincipal(subject, session, sessionIssuedAt, tokenExpiresAt)` is Spring Security's principal of an authenticated
request; `getName()` is `type:id`. It carries no permission list.

### GrantsLookup

| Member | Behaviour |
|---|---|
| `principalOf()` | the principal of the request running on this thread, or null |
| `heldBy(subject, codes)` | the subset of `codes` the subject holds, in one bounded statement over exactly those codes; a code nothing declares is held by nobody; inside a servlet request, what was found is remembered for the rest of the request |
| `directPermissionsOf(subject, after, limit)` | a keyset page of permissions granted to the subject directly, in permission-id order; `PermissionPage(items, next)`; `limit` 1 to `web.page.max-size`, otherwise `422 out_of_range` |
| `directoryOf(type)` | the directory of a served type, or null |

```kotlin
class TicketClosing(
    private val grants: GrantsLookup,
    private val tickets: TicketStore,
) {
    fun close(id: UUID) {
        val principal = grants.principalOf() ?: throw Fault(FaultKind.UNAUTHORIZED, RainErrorCodes.UNAUTHENTICATED)
        if ("ticket.close" !in grants.heldBy(principal.subject, setOf("ticket.close"))) throw Fault.forbidden()
        tickets.close(id)
    }
}
```

### AccessProvisioning

The writes seeding and operations perform, each idempotent. A method that derives a password hash refuses to run inside a
transaction (`IllegalStateException`): a hash takes a bulkhead permit and seconds of CPU, and a pooled connection held for
that time is one no request can have.

| Member | Behaviour |
|---|---|
| `ensureRole(slug, name, permissions)` | creates an application role when no role has the slug, then attaches the named codes it does not hold; never detaches, never renames; an undeclared code is `422 unknown_permission`; a slug off its pattern, or a name that is blank or over 256 characters — what the role routes refuse — is an `IllegalArgumentException`; answers the role's id |
| `setDefaultRole(type, slug)` | binds the role a sign-up of `type` is granted, and records `default-role-changed` only when the binding changes; an unserved type is `400 unknown_subject_type`, an unknown slug `422 unknown_role` |
| `grantRole(subject, slug)` | as the grant route: the subject active, within the role ceiling |
| `enrolPassword(subject, identifier, password)` | `Enrolled` when a credential was written under the normalised identifier, `AlreadyEnrolled` when the subject already has one; `409 identifier_taken` when another subject of the type signs in with it; hashes |
| `hasPassword(subject)` | whether the subject has a password credential |
| `usableHolderOf(slug)` | a holder of the role who is active and has a password — someone who can actually sign in and hand out roles; examines a keyset page of `provisioning.holder-page-size` holders at a time; `Found(subject)`, `NoneFound` when every holder was examined, or `NotEvaluated(pagesScanned)` when `holder-page-budget` pages ran out first |

A grant row survives the deactivation of its subject and never required the subject to exist, so "somebody holds the
administrator role" is not an answer to "somebody can administer this deployment"; `usableHolderOf` is. A `Seeder`
([rain-boot](boot.md)) that has to leave a deployment administrable asks it and refuses on anything but `Found`.

## HTTP surface

Every route is under `rain.access.web.base-path` (`<base>` below) and is mounted in the `api` role. Every route refuses a
query parameter it does not read (`400 unknown_parameter`), and an id in the path is a canonical UUID or `400 invalid_id`
— no other spelling names the same id.

A body is one JSON object naming only the fields its route reads; the names are checked before any field is read, so a
misspelt field is heard rather than ignored, as a misspelt query parameter is (`JsonBodyIsStrictTest`,
`DirectorySurfaceIT`, `AccountSurfaceIT`):

| Body | Answer |
|---|---|
| names a field the route does not read | `422 validation_failed`, one `unknown_field` violation at each such field's pointer; a body naming more than 100 is refused naming 100, marked `partial` |
| JSON that is not an object: an array, a string, a number, `true`, `null` | `400 malformed_body` |
| text that is not JSON | `400 bad_request` |
| absent | read as an object with no fields: each required field is `422 validation_failed` with a `required` violation at its pointer |
| a field of the wrong JSON type | `422 validation_failed`, `invalid_format` at the field |

### Credential routes

| Method and path | Access | Request | Answer |
|---|---|---|---|
| `POST <base>/auth/{subjectType}/login` | public | `{"identifier","password"}`; `Rain-Auth-Delivery` when delivery is `both` | `200`, a sign-in answer |
| `POST <base>/auth/{subjectType}/register` | public; mounted only when a `SubjectRegistrar` bean exists | `{"identifier","password","profile":{…}}`, `profile` an optional object of strings; `Rain-Auth-Delivery` when delivery is `both` | `201`, a sign-in answer; `404` for a served type with no registrar |
| `POST <base>/auth/refresh` | public | the refresh cookie, or `{"refreshToken"}` | `200`, a sign-in answer delivered the way the credential arrived |
| `POST <base>/auth/logout` | authenticated | — | `204`; clears both cookies unless delivery is `body` |
| `POST <base>/auth/logout-all` | authenticated | `{"includingCurrent": true or false}` | `200` `{"closed": n}`, the sessions marked closed; clears both cookies when `includingCurrent` and delivery is not `body` |
| `POST <base>/auth/password` | authenticated | `{"current","next"}` | `204` |
| `GET <base>/auth/me` | authenticated | — | `{"subject","session","profile"}` |
| `GET <base>/auth/sessions` | authenticated | `after`, `limit` | a page of the subject's open sessions, newest first |
| `DELETE <base>/auth/sessions/{sessionId}` | authenticated | — | `204`; a session already closed closes nothing more; a session of another subject matches nothing and closes nothing |

A sign-in delivered in the body:

```http
POST /api/auth/agent/login
Content-Type: application/json
Rain-Auth-Delivery: body

{"identifier":"ada@example.test","password":"correct horse battery"}
```

```json
{
  "principal": {
    "subject": {"type": "agent", "id": "018f5b3a-1c2d-7e4f-8a9b-0c1d2e3f4a5b"},
    "session": "018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c",
    "profile": {"displayName": "Ada", "identifier": "ada@example.test", "attributes": {}}
  },
  "accessToken": "eyJ…",
  "accessExpiresAt": "2026-09-15T10:05:00Z",
  "refreshToken": "1.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.…",
  "refreshExpiresAt": "2026-10-15T10:00:00Z"
}
```

Delivered in cookies, the answer is `{"principal": …}` alone. `profile` is absent when the directory describes nothing.
A rotation in the body:

```http
POST /api/auth/refresh
Content-Type: application/json

{"refreshToken":"1.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.…"}
```

The sessions page, `GET /api/auth/sessions?limit=10`:

```json
{
  "items": [
    {
      "id": "018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c",
      "current": true,
      "userAgent": "rain-test/1",
      "address": "203.0.113.7",
      "createdAt": "2026-09-15T10:00:00Z",
      "lastUsedAt": "2026-09-15T10:00:00Z",
      "expiresAt": "2026-10-15T10:00:00Z"
    }
  ],
  "next": null
}
```

`userAgent` (at most 256 characters) and `address` are absent when the sign-in recorded none. `next` is always present,
null on the last page; the session cursor is `<epoch microseconds>_<session id>`. A session a cutoff closed is left out of
its page after the page is read, so a page can hold fewer than `limit` items and still have a `next`.

### Administration routes

| Method and path | Permission | Request | Answer |
|---|---|---|---|
| `GET <base>/roles` | `access.role.read` | `after` (a slug), `limit` | a page of roles by slug |
| `GET <base>/roles/{roleId}` | `access.role.read` | — | a role |
| `POST <base>/roles` | `access.role.write` | `{"slug","name"}` | `201`, the role |
| `PATCH <base>/roles/{roleId}` | `access.role.write` | `{"name"}` | the role |
| `DELETE <base>/roles/{roleId}` | `access.role.delete` | — | `204` |
| `POST <base>/roles/bulk-delete` | `access.role.delete` | `{"ids":[…]}`, a non-empty array of at most `web.max-bulk-ids` canonical ids | `204` |
| `GET <base>/roles/{roleId}/permissions` | `access.role.read` | `after` (a permission id), `limit` | a page of the role's permissions |
| `POST <base>/roles/{roleId}/permissions` | `access.role.write` | `{"permission"}`, a code | `204` |
| `DELETE <base>/roles/{roleId}/permissions/{code}` | `access.role.write` | — | `204` |
| `GET <base>/permissions` | `access.role.read` | `after` (a code), `limit` | a page of the catalogue by code |
| `GET <base>/subjects/{subjectType}/{subjectId}/roles` | `access.grant.read` | `after` (a role id), `limit` | a page of `{"roleId","slug","grantedAt"}` |
| `GET <base>/subjects/{subjectType}/{subjectId}/permissions` | `access.grant.read` | `after` (a permission id), `limit` | a page of `{"permissionId","code","grantedAt"}` |
| `POST <base>/subjects/{subjectType}/{subjectId}/roles` | `access.grant.write` | `{"role"}`, a slug | `204` |
| `DELETE <base>/subjects/{subjectType}/{subjectId}/roles/{slug}` | `access.grant.write` | — | `204` |
| `POST <base>/subjects/{subjectType}/{subjectId}/permissions` | `access.grant.write` | `{"permission"}`, a code | `204` |
| `DELETE <base>/subjects/{subjectType}/{subjectId}/permissions/{code}` | `access.grant.write` | — | `204` |
| `PUT <base>/subjects/{subjectType}/{subjectId}/password` | `access.credential.write` | `{"password"}` | `204` |

A role is `{"id","slug","name","system","grantsEveryPermission","createdAt"}`; a permission
`{"id","code","name","module","createdAt"}`. A page is `{"items":[…],"next":…}` with `next` the cursor of the following
page or null. `limit` is 1 to `web.page.max-size` and defaults to `web.page.default-size`; a limit outside it or a
parameter stated twice is `400 bad_query`, a cursor that cannot be read `400 invalid_cursor`. A list names no relation to
load alongside: a role's permissions are their own page.

`PUT …/password` takes the identifier from the directory's profile, normalised the way sign-in normalises what it looks
up; writes the credential, or replaces the identifier and hash of the existing one; and closes every session of the
subject. A subject the directory does not describe is `404`. An identifier another subject of the type signs in with is
`409 identifier_taken` whether or not the subject already had a credential, and nothing is written
(`SetSubjectPasswordTest`, `DirectorySurfaceIT`).

### Refusals

| Answer | When |
|---|---|
| `400 invalid_delivery` | delivery is `both` and `Rain-Auth-Delivery` is absent, repeated, or anything but exactly `cookies` or `body` |
| `400 unknown_subject_type` | the path names a subject type this application does not serve, with a violation at `/subjectType` |
| `400 invalid_id`, `400 unknown_parameter`, `400 bad_query`, `400 invalid_cursor` | see above |
| `400 malformed_body` | a body that is JSON but not an object |
| `400 bad_request` | a body that is not JSON; a bulk delete naming more than `web.max-bulk-ids` ids, with an `out_of_range` violation at `/ids` |
| `401 bad_credentials` | a sign-in that does not prove its password, or a password change whose current password is wrong |
| `401 unauthenticated` | a refresh credential that is not usable, presented twice or not at all; a route that needs a principal, called without one or with an access token that authenticates nobody |
| `403 forbidden` | a permission is missing |
| `403 system_role` | renaming, deleting or detaching a permission from a system role |
| `404 not_found` | a role, or an operator's subject, that does not exist |
| `409 identifier_taken`, `409 already_enrolled` | a sign-up or password set whose identifier another subject of the type signs in with; a sign-up for a subject that already has a password |
| `409 too_many_roles` | a grant beyond `grants.max-roles-per-subject` |
| `415 unsupported_media_type` | a body on a credential route that is not `application/json` |
| `422 validation_failed` | what the request itself spells wrong: a field the route does not read (`unknown_field`), a missing (`required`) or mistyped (`invalid_format`) field, an id in a bulk delete that is not canonical (`invalid_id`), a new password under `password.min-length` (`weak_password`) or over `password.max-bytes` (`password_too_long`), an identifier over its bound (`too_long`) |
| `422 invalid_format`, `422 unique` | a slug that does not match the slug pattern; a slug another role already has |
| `422 required`, `422 too_long` | a role name that is blank; a role name over 256 characters |
| `422 unknown_role`, `422 unknown_permission`, `422 unusable_subject` | a slug no role has, a code no module declares, a grant to a subject that is not active |
| `429 too_many_attempts` | the identifier or the address is locked; `Retry-After` is the rest of the lock |
| `429 too_many_requests` | the client address is past its bucket on the credential routes; `Retry-After` is the gap between two tokens |
| `503 overloaded` | no hashing permit in time, or the waiting queue is full; `Retry-After` is the bulkhead's `max-wait-duration` when it is positive |
| `503 credential_changed` | the credential changed between verification and write twice |
| `503 audit_unavailable` | the evidence of a change could not be written; nothing was changed |
| `503 revocation_unavailable` | the revocation list could not be asked or told |
| `503 unavailable` | the attempt store could not be asked |

A `422` raised by a rule of the roles, the catalogue or the directory carries its one violation's code as the top-level
`code` as well, with the violation at the field — `422 unknown_role` at `/role`, `422 too_long` at `/name` — so a client
branches on the code without reading `errors`; only what the request itself spells wrong answers `validation_failed`
(`DirectorySurfaceIT`).

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"this application serves no subject of this type","code":"unknown_subject_type","errors":[{"pointer":"/subjectType","code":"unknown_subject_type","message":"\"robot\" is not a subject type this application serves"}]}
```

### Delivery

| `rain.access.web.delivery` | A sign-in answers | A request authenticates with | A refresh reads |
|---|---|---|---|
| `cookies` | both credentials in cookies, `{"principal"}` in the body | the `__Host-rain-access` cookie; `Authorization` is not read | the `__Secure-rain-refresh` cookie; the body is not read |
| `body` | both credentials and their expiries in the body, no cookie | `Authorization: Bearer <token>`; cookies are not read | `{"refreshToken"}`; cookies are not read |
| `both` | what the sign-in's `Rain-Auth-Delivery: cookies` or `body` states | either, never both at once | either, never both at once; the answer goes where the credential came from |

A refresh answers the way its credential arrived, so a script cannot turn a cookie-borne credential into a readable one;
a refused cookie refresh clears the refresh cookie.

| Cookie | Path | Lifetime | Attributes |
|---|---|---|---|
| `__Host-rain-access` | `/` | until the access token expires | `HttpOnly`, `Secure`, `SameSite=Strict` |
| `__Secure-rain-refresh` | `<base>/auth/refresh`, the one route that spends it | until the session expires | `HttpOnly`, `Secure`, `SameSite=Strict` |

Clearing writes both with an empty value and `Max-Age=0`. The prefixes require what the attributes state from a browser
that honours them, so the credential routes are served over HTTPS; a page on another origin reaches them only as far as
`rain.web.cors` and the cross-site filter allow ([transport and health](../concepts/transport-and-health.md)).

## Sessions and tokens

### The access token

The short-lived half of the pair: a JWT signed HS256, header `typ` `JWT`, with exactly these claims.

| Claim | Value |
|---|---|
| `sub` | the subject id |
| `sty` | the subject type |
| `sid` | the session id |
| `sit` | when the session was issued, in epoch milliseconds; a subject cutoff is compared against it |
| `iss` | `token.issuer` |
| `aud` | `token.audience`, as one string |
| `iat` | the issue instant, in whole seconds |
| `exp` | `now + token.access-ttl`, clamped to the session's own expiry, in whole seconds |

A verification accepts HS256 and nothing else, `typ` `JWT`, an expiry that is present, checked with no clock skew
against the application's clock, the configured issuer, exactly the configured audience, a subject type in its format,
and a subject and session in canonical UUID form; anything else is a token that does not verify. The token carries no
grant: what the subject may do is read on every decision.

### Refresh credentials and rotation

A session is one sign-in and the lineage of refresh credentials it issued. A refresh credential is
`<generation>.<session id>.<43 characters of base64url>` (32 random bytes); the session stores only the SHA-256 digest of
the current one and of the one before it. The prefix authenticates nothing — the digest does — but it names the session
and the generation, so a credential several rotations old is recognised as a replay of that session.

A presented credential finds its session by the current digest, then the previous digest, then — for a credential older
than that — by the session its prefix names, when that session has moved past the generation after it. A session whose
subject has a cutoff covering it is unusable. Otherwise the classification is, first match wins:

1. the session is closed or expired → unusable;
2. not rotated for longer than `session.idle-ttl` → unusable;
3. the current digest → rotate;
4. a generation older than the previous one → replay;
5. not the previous digest → unusable;
6. no rotation recorded → unusable;
7. the previous digest within `session.refresh-grace` of the rotation, inclusive → rotate again;
8. otherwise → replay.

To rotate, the subject type is served and the subject active, and the session moves to the next generation by a
compare-and-set on the generation and digest just read, which sets the rotation instant and the last use. A rotation that
loses — another request rotated first — reads the session again, classifies again and mints from the generation it has
now, at most `session.rotation-attempts` times; losing every time is `401` and a warning. The answer is a new access token
for the same session and the new refresh credential; the session's expiry does not move.

Two tabs refreshing one credential at once both succeed through the grace: one rotates, the other rotates again
(`ConcurrentRefreshThenGraceIT`). A replay closes the session with reason `refresh-replayed`, announces it to the
revocation list and is `401`: a credential this session really issued and somebody kept. Unusable closes nothing. No
transaction wraps a rotation; each statement is atomic, and a rollback would undo the closing a replay exists to make.

The idle bound counts from the last rotation, not from the last request: an access token that answers does not move it.
Since `token.access-ttl` is not longer than `session.idle-ttl`, a client that refreshes when its token expires keeps its
session.

### Signing out

`POST …/logout` closes the principal's own session and `DELETE …/sessions/{id}` another session of the same subject,
each in a transaction with its evidence, then announces the closed session to the revocation list. A list that cannot be
told is `503 revocation_unavailable` after the session is closed in the database; the replay announces it again. A
session that is already closed is `204` as well: the call closes nothing, records no second row, and announces the
session again with the instant it was closed, since the list may have lost it. A session of another subject matches
nothing, records nothing and announces nothing (`LogoutRecordsOnlyWhatItClosedTest`, `AccountSurfaceIT`).

`POST …/logout-all` closes every session of the subject issued up to now, keeping the current one unless
`includingCurrent` is true:

1. in one transaction, the subject's cutoff — "every session issued up to this instant is closed, except this one" — is
   written to `subject_cutoffs` with its evidence; a cutoff never replaces a later one;
2. after it commits, the cutoff is announced as one revocation key, whatever the number of sessions;
3. the sessions are marked closed `session.revoke-batch` per transaction, until a batch closes fewer.

From the commit on, the cutoff is what refuses those sessions: at every request, at rotation and in the session list. A
list that could not be told is reported after the batches ran. Closing seven sessions costs one cutoff announcement and no
session announcement (`LogoutAllWritesOneRedisKeyTest`).

### Changing and setting a password

A subject changing its own password presents the current one, which behind a valid session is a password oracle: it is
charged to the same attempt keys a sign-in with the account's identifier is, and a wrong one is recorded exactly as a
failed sign-in — a `sign-in-failed` row in a transaction of its own, and one `lockout-opened` row for each key the failure
locked — and answers `401 bad_credentials`. A locked key refuses sign-in and password change alike with `429
too_many_attempts`, and a successful change clears the identifier's counter (`ChangePasswordTest`). The current
password is verified and the new one hashed outside any transaction; the new hash is written only while the credential is
still the version verified, with one re-run when it changed and then `503 credential_changed`. With
`password.revoke-other-sessions-on-change`, the same transaction writes a cutoff keeping the current session, completed
as for `logout-all`.

An operator setting another subject's password (`PUT …/password`) closes every session of the subject, the same way;
an identifier another subject of the type signs in with is `409 identifier_taken`, whether or not the subject had a
credential.

### Sign-in

A sign-in runs in two phases, so no pooled connection is held while a hash is derived
(`LoginHoldsNoConnectionWhileHashingIT`). A caller inside a transaction is refused.

Outside any transaction:

1. the presented identifier is normalised by the subject type's `IdentifierNormalization`;
2. the attempt limiter admits the attempt, or it is `429 too_many_attempts`, charged nothing and recorded nowhere;
3. an empty identifier, one over `password.max-identifier-bytes` or a password over `password.max-bytes` is a failure;
4. the credential is read and the password verified inside the hashing bulkhead — against a real dummy hash when no
   credential exists, so a miss costs what a hit costs;
5. a subject that is not active is a failure;
6. a hash derived with weaker parameters than the declared Argon2id ones is derived again, inside the bulkhead.

Then in one transaction: the credential row locked `FOR UPDATE` and required to be exactly the version and identifier
phase one verified, the upgraded hash written, the session inserted, the directory told `signedIn`, and the `signed-in`
evidence recorded — a failure to record it rolls everything back (`503 audit_unavailable`, no session, no cookie). A
credential that changed between the phases is verified again once; changed again, the sign-in is `503
credential_changed`.

A failure is charged to the limiter, recorded as `sign-in-failed` in a transaction of its own, and answers `401
bad_credentials`, which says nothing about which half was wrong. A failure that locks a key records one `lockout-opened`
row; the attempts refused while it stays locked record nothing. A success clears the identifier's counter and logs
`signed in` with the subject type and `sid_fp`.

A sign-up hashes outside any transaction, then in one transaction asks the registrar to create the subject, writes the
credential, grants the type's default role, issues the first session, tells the directory and records `signed-up` and
`signed-in`. A taken identifier refuses the whole of it. A sign-up is not charged to the attempt limiter; the credential
throttle stands in front of it.

## Attempts, hashing and the credential gate

### Attempt limiting

Each attempt is charged to two keys: its identifier within its subject type (`perIdentifier`), and its client address
(`perAddress`). A key's window opens with its first failure; the failure that reaches a key's ceiling locks the key for
`lock-for`; a locked key refuses every attempt charged to it. A success clears the identifier's counter and leaves the
address's. No key contains the identifier: an identifier key is the subject type and the SHA-256 of the normalised
identifier. The client address is `request.remoteAddr`, which is a proxy's forwarded address only when `rain.web.client-address`
is `forwarded`.

| Store | Counters | Bound |
|---|---|---|
| `redis` | shared by every replica; each key a counter `<prefix>{<key>}:n` and a lock `<prefix>{<key>}:l` under one hash tag, so both live on one cluster slot; a failure is one Lua script — `INCR`, `PEXPIRE` to the window when the increment created the counter, `SET NX PX lock-for` at the ceiling | O(1) commands per attempt whatever the number of keys; expiry is the server's, nothing sweeps (`RedisAttemptLimiterIT`) |
| `memory` | in this process, refused in `prod` | at most `memory.maximum-keys` counters in a heap ordered by expiry |

The memory table's bound is the security property, and so is its rule for making room: only a counter whose window and
lock have both run out may be forgotten, because forgetting a live counter hands its caller a free reset. Making room
looks at the heap root. A table full of live counters refuses an attempt it would have to seat a new counter for — `429`,
until the earliest counter expires — while the counters it holds keep counting; a failure it cannot seat is logged as
uncounted (`AttemptLimiterCapacityTest`).

An attempt store that cannot be asked refuses rather than admits: `503 unavailable`.

### The hashing bulkhead

Every Argon2id derivation and verification runs inside the Resilience4j bulkhead `rain-access-hashing`, looked up in the
registry the Resilience4j starter builds and configured by its properties alone. At most `max-concurrent-calls`
derivations run at once, a caller waits at most `max-wait-duration` for a permit, and at most `rain.access.hashing.queue`
callers wait at once; one more is refused at once with `503 overloaded` rather than queued for a permit it would reach only
after its request had ended. A request whose budget has run out is refused before it waits (`503 deadline_exceeded`), and
the budget's interrupt ends a wait. A permit is given back when the work fails.

Hashes are Argon2id PHC strings at the declared parameters, through Spring Security's encoder. The hasher derives and
verifies its dummy hash when it is built, which also proves at start-up that Bouncy Castle is present.

### The credential gate

Two filters of the `api` role stand on the credential surface — `<base>/auth` and every path under it — after rain-web's
filters and before Spring Security's chain. Both match the path as handler mappings match it, percent-decoded, so an
encoded spelling of a credential route is gated too (`CredentialGateOnTomcatIT`).

| Order | Filter | What it does |
|---|---|---|
| `HIGHEST_PRECEDENCE + 50` | `rainAccessJsonOnlyFilter` | an unsafe request with a body (a positive `Content-Length`, or a `Transfer-Encoding` without one) whose `Content-Type` is not `application/json` is `415 unsupported_media_type`: nothing a `<form>` can send reaches a credential route |
| `HIGHEST_PRECEDENCE + 60` | `rainAccessCredentialThrottleFilter` | a `TokenBucketThrottle` of `gate.throttle` per client address on unsafe requests; past the bucket is `429 too_many_requests` with `Retry-After` |

Reads and every route outside the surface pass both. rain-web's cross-site filter, earlier in the chain, refuses a
cross-site write to these routes as to any other (`403 cross_site`).

## Revocation

The token is a signature, so its verification does not read the session it belongs to. Closing a session is two facts:
the row in `sessions`, which is the record, and an announcement to the revocation list, which every authenticated request
reads. With `revocation.store: none` there is no list: a closed session's access token answers until it expires, at most
`token.access-ttl` later, while its refresh credential stops rotating at once.

### The list on Redis

| Key | Value | Lives until |
|---|---|---|
| `<prefix>s:<session id>` | `1` | `revoked_at + token.access-ttl`, the last instant a token of that session can verify |
| `<prefix>c:<type>:<subject id>` | `<cutoff epoch milliseconds>:<kept session id, or empty>` | `cutoff_at + token.access-ttl` |

A token is revoked when its session's key exists, or when its subject's cutoff key names a cutoff at or after the token's
`sit` and a session other than the kept one. A check is one `MGET` of both keys. A revocation whose lifetime has already
passed writes no key: no token can name it. Closed sessions are written in one pipeline; a cutoff is written by a script
that never replaces a later cutoff. The list fails closed: a read that fails is never "live" — the request
is anonymous, and a route that needs a caller answers `503 revocation_unavailable` (`RevocationListIT`, `RedisOutageIT`).

### The server keeps what it is told

The list reads a missing key as a session nobody closed. A key is meant to vanish exactly when the last token that could
name it expires; a Redis under `maxmemory` with an evicting policy removes keys itself, earlier, coldest first — and a
revocation between a sign-out and the next presentation of its token is as cold as a key gets. The two removals read the
same, so a burst of memory would hand closed sessions back to their holders. Eviction is a property of the server, not of
a keyspace: a separate database index or a key prefix separates names, not memory. A `volatile-*` policy is no exception,
since every key of the list carries an expiry. An evicted attempt counter, likewise, is a free reset.

So each `api` process, and each `worker` for the list, checks at start-up with `CONFIG GET maxmemory-policy` — of every
master in a cluster, where the worst answer wins, because the key a session lands on lives on one node:

| The server | Result |
|---|---|
| answers `noeviction` | passes |
| answers any other policy | `invalid`, naming the policy; an attestation does not overrule an answer |
| answers `PING` and will not answer `CONFIG`, or answers no policy | `required` at `…eviction-policy-attested`; with `noeviction` attested, `not_evaluated`, logged, and the start goes on |
| answers nothing | `invalid`, not reachable, attested or not |

A managed Redis usually forbids `CONFIG`; its deployment verifies the policy out of band and states the attestation
(`RevocationServerPolicyIT`). A server that holds a cache as well is one whose memory the cache fills; the application
gives the list, or the counters, a `RedisConnectionFactory` of their own under `rainRevocation` or `rainAttempts`. The
readiness checks `access.revocation` and `access.attempts` ping the factory their store uses.

### Replay

An announcement can be lost: the database commits the closing, the list is down for a moment, and until the replay runs
the token keeps answering. The recurring work `access.revocation-replay` (role `worker`, store `redis`) tells the list
again, every `replay.interval`, what the database records for the last access-token lifetime — the only revocations a
live token can still name: sessions closed in `(now − access-ttl, now]` and cutoffs written in that window.

Each run reads keyset pages of `replay.page-size` over `(revoked_at, id)` and over `(cutoff_at, subject)`, at most
`replay.pages-per-run` of each. When a run stops at its budget, it keeps a watermark and the next run continues from it
while the watermark is inside the window, and a warning names what was announced. A run that reaches the present drops
the watermark, so the next run tells the list the whole window again: a revocation committed late, with an instant before
the watermark, is announced by the next pass, and a list that lost its keys is repaired rather than trusted. Announcing
what the list already holds changes nothing. The watermark is held by the process that ran the pass. The interval is
shorter than `token.access-ttl`, or a revocation the list missed would stay unannounced for the whole life of the token
it should stop (`RevocationReplayIT`).

## Evidence

rain-access records through [rain-audit](audit.md), module `access`. A change and its evidence commit together; evidence
that cannot be written refuses the change with `503 audit_unavailable`. A failed attempt is recorded in a transaction of
its own. The actor of a row is the authenticated subject (`accessPrincipalActor`), or the subject that signed in.

| Action | Resource kind | Detail keys |
|---|---|---|
| `signed-in` | `session` | `subject_type`, `address`, `user_agent` |
| `sign-in-failed` | `subject-type` | `identifier_fp` (the first six bytes of the identifier's SHA-256, in hex), `address` |
| `lockout-opened` | `attempt-key` | `key_kind`, `subject_type`, `address`, `lock_for_ms` |
| `signed-up` | `subject` | — |
| `signed-out` | `session` | — |
| `session-revoked` | `session` | — |
| `sessions-closed` | `subject` | `reason`, `kept_session` |
| `password-changed` | `subject` | `by` (`self` or `operator`), `other_sessions_closed` |
| `password-enrolled` | `subject` | `by` |
| `grant-changed` | `subject` | `change`, `grant_kind`, `grant` |
| `role-changed` | `role` | `change`, `slug`, `permission` |
| `default-role-changed` | `subject-type` | `slug` |

A call that changes nothing records nothing: a grant already held, a revoke of nothing, attaching a permission a role holds
or detaching one it lacks, binding the default role a type already has, closing a session already closed
(`GrantsAdministrationTest`, `RoleAdministrationRulesTest`, `ProvisioningRulesTest`, `LogoutRecordsOnlyWhatItClosedTest`).

A log line names a session by `sid_fp`, the first eight bytes of an HMAC-SHA256 of the session id in hex, never by the
id: a session id in a log store is half of what it takes to close somebody else's session. The HMAC key is derived from
the signing key under a fixed label, so replicas agree without a second secret. No identifier, password or token is
logged.

## Error codes

`AccessErrorCodes` (owner `rain-access`):

| Code | Default message | Rendered for |
|---|---|---|
| `bad_credentials` | the identifier or the password is wrong | a failed sign-in or current password, `401` |
| `weak_password` | the password is shorter than this service accepts | a violation of `422 validation_failed` |
| `password_too_long` | the password is longer than this service accepts | a violation of `422 validation_failed` |
| `too_many_attempts` | too many sign-in attempts; wait before trying again | a locked key, `429` with `Retry-After` |
| `overloaded` | the service is busy; try again | the hashing bulkhead, `503` |
| `unknown_role` | no role with this slug exists | `422` |
| `unknown_permission` | no permission with this code is declared | `422` |
| `system_role` | this role is part of the application and cannot be changed here | `403` |
| `unknown_subject_type` | this application serves no subject of this type | `400` |
| `unusable_subject` | no active subject with this id exists | `422` |
| `too_many_roles` | the subject holds as many roles as this service allows | `409` |
| `already_enrolled` | this subject already signs in with a password | `409` |
| `identifier_taken` | this identifier already signs in | `409` |
| `invalid_delivery` | the request does not say how credentials are delivered | `400` |
| `credential_changed` | the credential changed while the request was being checked; try again | `503` |
| `audit_unavailable` | the change could not be recorded; nothing was changed | `503` |
| `revocation_unavailable` | whether this session is still open could not be established; try again | `503` |

rain-access also answers with `RainErrorCodes` ([rain-core](core.md#error-codes)): `unauthenticated`, `forbidden`,
`not_found`, `invalid_id`, `invalid_cursor` (declared once, in rain-core, since rain-crud pages with it too),
`unknown_parameter`, `bad_query`, `bad_request`, `malformed_body`, `unsupported_media_type`, `too_many_requests`,
`unavailable`, and `validation_failed` with its violation codes; `invalid_format`, `unique`, `required`, `too_long` and
`out_of_range` are also the top-level code of a `422` a rule raises ([refusals](#refusals)).

## Health checks

| Check | Code | Runs in | Fails when |
|---|---|---|---|
| `access.revocation` | `access.revocation` | `api`, `worker`, when `revocation.store` is `redis` | the revocation list's Redis server does not answer `PING` within the check's budget |
| `access.attempts` | `access.attempts` | `api`, when `attempts.store` is `redis` | the attempt counters' Redis server does not answer `PING` within the check's budget |

## Schema

`rain_access`, migrated from `classpath:db/rain/access`. A grant, a credential and a session point at a subject — a type
and an id — never at an application table, so any kind of caller needs a directory and no migration here. Foreign keys
stay inside the schema and nothing cascades: every removal is a bounded batch rain-access issues itself, because one
cascading statement over a role held by millions of subjects is bounded by nothing. No column has a default for time or
identity; ids are UUIDv7 minted by the application and every instant comes from its clock.

| Table | Holds | Keys and indexes |
|---|---|---|
| `permissions` | the catalogue: code, name, module | `uq_permissions_code` |
| `roles` | slug, name, `is_system`, `grants_every_permission` (only a system role grants every permission) | `uq_roles_slug`; `ix_roles_every_permission` over the roles that grant every permission |
| `role_permissions` | what a role holds | `pk_role_permissions (role_id, permission_id)`; `ix_role_permissions_permission` |
| `subject_roles` | roles a subject holds | `pk_subject_roles (subject_type, subject_id, role_id)`; `ix_subject_roles_role (role_id, subject_type, subject_id)` for a role's holders |
| `subject_permissions` | permissions granted directly | `pk_subject_permissions (subject_type, subject_id, permission_id)` |
| `subject_default_roles` | the role a sign-up of a type is granted | primary key `subject_type`; `ix_subject_default_roles_role` |
| `credentials` | the subject, provider `password`, the normalised identifier, the secret hash, a version guarding every write | `uq_credentials_identifier (subject_type, provider, identifier)`; `uq_credentials_subject_password (subject_type, subject_id)` where the provider is `password` |
| `sessions` | the subject, current and previous refresh digests, generation, user agent, address, created, last used, rotated, expiry, revocation instant and reason | `uq_sessions_token_hash`; `ix_sessions_previous_token_hash`; `ix_sessions_live (subject_type, subject_id, created_at, id, expires_at, last_used_at)` over open sessions; `ix_sessions_revoked (revoked_at, id)`; `ix_sessions_expired (expires_at, id)` |
| `subject_cutoffs` | one cutoff per subject and the session it kept | `pk_subject_cutoffs (subject_type, subject_id)`; `ix_subject_cutoffs_cutoff (cutoff_at, subject_type, subject_id)` |

Constraints tie the columns together: a revocation instant exactly with its reason, a rotation instant exactly with a
previous digest, a generation from 1, a version from 0. `sessions.revoked_reason` is one of `signed-out`,
`signed-out-everywhere`, `closed-by-subject`, `password-changed`, `password-set`, `refresh-replayed`.

## Scale guarantees

| Operation | Access path | Bound | Proven by |
|---|---|---|---|
| the permission question, per request | the asked codes through `uq_permissions_code`; `EXISTS` probes on `pk_subject_permissions`, on `pk_subject_roles` joined to `pk_role_permissions`, and on the subject's roles that grant every permission | the asked codes × the subject's roles, which `grants.max-roles-per-subject` bounds; never the size of a table | `PermissionCheckBoundedCostIT` |
| a subject's sessions | `ix_sessions_live`, keyset on `(created_at, id)` | `limit + 1` rows | `SessionPagesKeysetIT` |
| roles, permissions, a role's permissions, a subject's roles and direct permissions, a role's holders | `uq_roles_slug`, `uq_permissions_code`, `pk_role_permissions`, `pk_subject_roles`, `pk_subject_permissions`, `ix_subject_roles_role` | `limit + 1` rows, keyset; no total | `DirectoryPagesKeysetIT` |
| replay pages | `ix_sessions_revoked`, `ix_subject_cutoffs_cutoff` | `replay.page-size` × `replay.pages-per-run` per kind per run | `DirectoryPagesKeysetIT`, `RevocationReplayIT` |
| retention | `ix_sessions_expired`, `ix_sessions_revoked`, `ix_subject_cutoffs_cutoff` | `retention.batch` rows per statement, `retention.batches-per-run` per kind per run | `SessionsRetentionIT` |
| closing every session of a subject | one cutoff row, one revocation key; then `session.revoke-batch` sessions per transaction | independent of the number of sessions until the batches | `LogoutAllWritesOneRedisKeyTest`, `SessionStoreIT` |
| deleting a role | `session.revoke-batch` holders, then permissions, per transaction | independent of the number of holders per statement | `RoleAdministrationIT` |
| catalogue synchronisation | inserts that keep existing rows, an indexed read of each chunk's codes | proportional to the declarations, never to what the tables hold | `CatalogueSynchronizerTest`, `AccessAggregateRoundTripIT` |
| the role ceiling | `subject_roles` counted up to `grants.max-roles-per-subject` | at most the ceiling | `AccessAggregateRoundTripIT` |
| a usable holder | `ix_subject_roles_role`, then the page's ids through the credential indexes | `holder-page-size` × `holder-page-budget`, then `NotEvaluated` | `AccessProvisioningTest` |
| sign-in, rotation, sign-out | `uq_credentials_identifier`; `uq_sessions_token_hash`, `ix_sessions_previous_token_hash`, the primary key | one row | — |
| the revocation check | one `MGET` of two keys | per request | `RevocationListIT` |
| attempt limiting | Redis: one script per key; memory: one heap root per room made | per attempt | `RedisAttemptLimiterIT`, `AttemptLimiterCapacityTest` |
| hashing | the bulkhead's permits and `hashing.queue` | no pooled connection held while hashing | `LoginHoldsNoConnectionWhileHashingIT`, `HashingBulkheadTest` |

The keyset pages are proven from the query plan with sequential scans, bitmap scans and sorts priced out: the named index
under a `Limit`, no sequential scan, no sort, no node the planner could only use disabled, and no `Filter` on the index —
every condition an index condition. The permission question is proven with rain-test's `QueryPlans`: no sequential scan of
any grant table, the named indexes read by condition, and the one `Filter` allowed a correlated `EXISTS` over rows a lookup
of the unique code index returned. Retention statements use their index under a `Limit`. None of these statements counts
rows.

## Recurring work, commands

| Recurring work | Role | Runs |
|---|---|---|
| `access.session-retention` | `worker` | every `session.retention.interval`: deletes sessions that expired or were closed more than `retention.keep-for` ago, and cutoffs older than `session.ttl` (every session they close has expired), in batches; warns when it stops at its budget |
| `access.revocation-replay` | `worker`, `revocation.store: redis` | every `revocation.redis.replay.interval`, as [above](#replay) |

Each is a [rain-jobs](jobs.md#recurring-work) `RecurringWork`, run by exactly one worker of the cluster per interval, and
is named in the worker's `rain.jobs.required-recurring`: `access.session-retention` always, `access.revocation-replay`
when `revocation.store` is `redis`. A worker whose list leaves one out, or names one it does not run, is refused
([rain-jobs](jobs.md#configuration)). No command.

## What it does not do

- It signs in with a password only: `credentials.provider` admits `password` and nothing else. It offers no password reset,
  no second factor, no external identity provider and no API key.
- It keeps no account of its own: whatever a subject is, and whether it is active, is its directory's.
- It puts no grant in a token and authorizes nothing by path; every decision reads the grants for exactly the codes it
  needs.
- It verifies and enforces the routes of Spring MVC's `requestMappingHandlerMapping` and `routerFunctionMapping`; a route
  another handler mapping serves checks its own access.
- It exempts no handler by its package or its library.
- It detaches, renames and deletes nothing in the catalogue on its own, and deletes no subject's rows when the subject
  goes.
- It announces nothing with `revocation.store: none`, and never reads a list it cannot reach as "live".
- It shares no attempt counter between processes with `attempts.store: memory`.
- It holds no pooled connection while a hash is derived.
