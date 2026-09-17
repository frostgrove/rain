# rain-tenancy

Optional tenant authority and scoped data-plane contracts. A `TenantRef` is only an identifier; it cannot become a
scope without `TenantAuthority` admitting the tenant's lifecycle and operation class.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-tenancy")
}
```

## Authority boundary

`HmacTenantAuthority` issues opaque `TenantScope`s with origin, epoch, placement, and lifecycle binding. It validates
current/retired keys in constant time and seals durable work against the exact job namespace, definition, invocation,
and payload digest. Durable restoration rechecks the control-plane resolution, lifecycle, epoch, and placement.

`TenantContext` accepts only an issued scope. Nested binding is permitted only for the same ref/epoch/origin; a pin
remains until a Spring transaction completes, including after a temporary unbind. `TenantRuntimeManager` validates an
acyclic task graph and closes every entered task in reverse order on success, failure, or cancellation.

## Service and async boundaries

When an application declares both `TenantAuthority` and `TenantDataPlane`, the optional Spring configuration installs
`@TenantRead` and `@TenantWrite` as service boundaries. They require an already-bound issued scope, open exactly the
matching data-plane unit, and enter `TenantRuntime` only inside that unit; `TenantRuntime.current().unit()` is therefore
not available to central work or to an async hand-off. `@TenantTransactional(READ|WRITE)` is a compatibility synonym.
Ordinary Spring `@Transactional` is deliberately not intercepted and remains a control-plane transaction.

The start-up verifier refuses a tenant-annotated private or final method/class: an annotation that cannot be proxied
must never look like an enforced boundary. Method annotations override a class default; two declarations at the same
level that name different operations are refused.

`TenantTaskDecorator` captures an already-issued scope only at in-process submission time, rejects a poisoned worker,
and clears its own worker binding even if task code leaks a nested binding. Wrap only executors that the application
names explicitly:

```kotlin
@Bean
fun tenantEventsExecutor(eventsExecutor: TaskExecutor): TaskExecutor =
    TenantTaskExecutor(eventsExecutor)
```

Unwrapped executors, schedulers, coroutine dispatchers, and durable jobs remain central/explicit. This is intentional:
there is no global executor post-processor and no combined tenancy-i18n/jobs/events adapter. Applications compose the
independent adapters they select at their own boundary.

## Servlet surface

Servlet controllers declare one tenancy shape: `@TenantRoute(READ|WRITE)` requires a verified tenant; `@CentralRoute`
requires a bounded reason and rejects a request that carries a tenant selection; and `@UniversalRoute(READ|WRITE)` runs
central when the resolver returns no candidate or tenant-aware when it returns one. A malformed or conflicting candidate
is rejected before either a central or tenant handler runs. The interceptor binds only for the handler lifetime and never
opens a request-wide transaction; an async hand-off closes the servlet worker binding before the worker is reused.

`TenantServletSurfaceVerifier` checks every mounted annotation-controller mapping and every readable functional
`RouterFunction` route when the servlet adapter is active. Generated controllers and functional route providers
implement `DeclaresItsOwnTenancy`; that one declaration table is used by both the verifier and runtime interceptor, so
a duplicate declaration is refused rather than resolved by bean or iteration order. Infrastructure handlers require an
explicit `TenantSurfaceExemption` with a reason. `PrincipalTenantResolutionSource` accepts an application-owned
principal resolver which returns only a plain resolution signal. `CanonicalHostTenantResolutionSource` consumes the
container-normalized server authority after rain-web's forwarded-header policy, validates port/IP/IDNA normalization,
and asks an application domain directory. `TrustedHeaderTenantResolutionSource` has no default and requires an
application `TrustedTenantHeaderVerifier`; only that explicitly installed verifier receives header values. All three
are `TenantResolutionSource`s, so agreeing candidates are accepted and every malformed or conflicting candidate is
refused before central fallback. None can construct a `TenantScope`; merely reading `X-Tenant` is not a supported
authority path.

When the application contributes at least one `TenantResolutionSource` plus a
`TenantResolutionDirectory` (for example its `TenantControlPlane`), Rain assembles
`CompositeTenantResolver` in deterministic Spring order. An application may instead provide its own `TenantResolver`;
Rain then never replaces or reorders it.

`TenantDurableJobContextProvider` and `TenantAuditScopeContributor` are independent adapters over the same scope:
the jobs contribution signs only the opaque scope token plus exact invocation/payload digest and the audit contribution
writes only a deployment-scoped digest plus epoch. Register either where its host module is used; neither imports or
combines i18n/event behavior.

## Control plane

`TenantControlPlane` has no general register, update, or upsert operation. It exposes closed lifecycle commands such
as `createDraft`, `activate`, `makeReadOnly`, `beginDeletion`, and `restoreAsNewEpoch`. Every existing-row command
carries an `ExpectedTenantControlVersion` and a caller-minted `TenantOperationId`. A repeated exact operation returns
its first durable result; reuse of the id with another target, version, placement, or tenant is refused as an
operation collision.

`JooqTenantControlPlane` owns `rain_tenancy`. It stores only a deployment-HMAC reference digest, never the raw
`TenantRef`, and writes the control row, immutable transition, operation receipt, and `TenantControlAudit.TRANSITION`
evidence in one transaction. Applications declare that audit type alongside the recorder; an undeclared audit event
rolls back the control change and its receipt. The request-resolution pipeline stays outside this API: a resolver can
use `controlPlane::lookup`, but a control plane never infers a tenant from HTTP input.

Database provisioning is an explicit fenced workflow, not an `activate` side effect. An application declares
`TenantProvisioningStep`s with stable ids, semantic revisions and `before`/`after` edges; startup rejects duplicate
ids, unknown dependencies and cycles. `TenantProvisioningWorkflow` persists one immutable graph fingerprint per
tenant epoch through `JooqTenantProvisioningLedger`. Each claimed step has an incrementing fence and lease token;
external work runs outside the short control-plane claim transaction and must use that fence in its own idempotency
protocol. A stale worker cannot subsequently mark a newer claim successful or failed. Retryable failures return the
step to pending, while a `TenantProvisioningClosedException` quarantines it. Rain never compensates a failed workflow
by dropping a database, role, bucket or secret automatically.

Construct `JooqTenantControlPlane` with that workflow as its `TenantProvisioningActivationGate`. Then `activate`
returns the durable, replayable `ProvisioningIncomplete` result until every required step for the current epoch is
successful; it does not append a transition or audit a false activation. A graph/revision/placement mismatch also
fails closed, so deployment drift cannot reinterpret earlier success. Step contexts expose only target, epoch,
placement and opaque fence — no control-plane mutation surface, raw provisioner credential or generic datasource.

Fleet work uses an explicitly issued `TenantGrant`, never an unbounded enumeration. `HmacTenantGrantAuthority` signs
the issuer, purpose, exact cohort, allowed operation classes and expiry; it validates each issued cohort member for
administrative admission and rechecks the target scope when a data plane enters it. Retired verification keys permit
bounded rotation. A changed cohort, operation, issuer or expiry is not a valid grant, and grant failures do not expose
the affected tenant reference.

`TenantFleet` is the corresponding low-level runner, not a global scheduler. The application supplies a durable
`TenantFleetLeaseManager`, an application-owned executor, and a finite `TenantFleetSpec` containing an operation id,
page size, concurrency limit, time budget, lease duration and (on resume) opaque cursor. It walks only the grant's
fixed cohort through `TenantDataPlane.admin`; each callback receives a stable `TenantFleetItem.id` for downstream
idempotency. A cursor advances only across a contiguous successful prefix. Failed or timed-out items can have started,
so their outcome is partial rather than a claim that no work happened; a resumed callback uses the same item id.

## Shared-row data plane

`SharedRowTenantDataPlane` is the explicit PostgreSQL implementation of `TenantDataPlane`. It opens one read or write
transaction, revalidates and binds the issued scope, then sets transaction-local `rain.tenant_ref_digest`,
`rain.tenant_epoch`, and `rain.tenant_operation` before it provides a `TenantUnit`. The digest is a separate
deployment-HMAC value encoded for a PostgreSQL GUC; it is neither a raw `TenantRef` nor the scope token.

Tenant rows must enforce their own RLS policies against those settings. The data plane deliberately does not generate
application table migrations or swap a global `DataSource`: code enters through `read`, `write`, `durable`, or a
grant-verified `admin` call. An existing ordinary Spring transaction is refused unless it was already entered by this
same data-plane boundary, preventing accidental control-plane/tenant-plane mixing and tenant switches before SQL.

`PostgresTenantRowVerifier` is the test/startup proof for an application-owned shared-row table. Its `TenantRowSpec`
requires `tenant_ref_digest bytea` with an `octet_length(...)=32` constraint, a non-null epoch, force-RLS, an
unprivileged non-owner application role, explicit SELECT/INSERT/UPDATE/DELETE policy coverage, tenant-leading
primary/unique keys and indexes, and tenant-aware child FKs. It never creates policies or migrations: the table's
bounded context owns those declarations. The companion integration probe must execute as the application role without
the GUCs and proves that direct reads return no rows and writes are rejected.

## Database-per-tenant data plane

`DatabaseTenantDataPlane` composes the same `TenantAuthority` with a `TenantDatabaseDirectory`, without replacing
the control-plane `DataSource`. A placement directory supplies a non-secret PostgreSQL endpoint, database id,
placement revision, credential revision, secret reference, and catalogue fingerprint; a separate `TenantSecretResolver`
supplies the short-lived credential. Neither value type prints its sensitive material.

The directory key is the deployment-HMAC reference digest plus epoch, placement revision, and credential revision.
It reserves the configured per-tenant connection budget before a shared asynchronous first-open, uses
`minimumIdle=0`, retains each pool until its last lease closes, evicts idle unborrowed entries, and refuses capacity
rather than breaking active tenants. Every new pool runs `PostgresTenantSourceFenceVerifier` using the application
credential before a tenant unit is exposed. The tenant database must contain exactly one `rain_tenancy_binding` row
with `origin`, `tenant_ref_digest`, `tenant_epoch`, `database_id`, and `catalogue_fingerprint`; any mismatch closes
the pool and returns a closed source-mismatch outcome.

### Database-mode durable jobs

`TenantJobOutbox` is the standalone tenancy↔jobs adapter in `com.gd.rain.tenancy.jobs`. It is not an alternate
`WorkQueue`: a database-per-tenant application writes a caller-minted `TenantJobOutboxId`, declared jobs definition,
exact pre-serialized payload bytes, and explicit `EnqueueOptions` into `rain_tenant_job_outbox` within its writable
tenant unit. Repeating the same id and contents returns the original source entry; retargeting it to another epoch,
definition, payload, or options is a collision. The order's eligibility time is fixed at source write, so relay delay
does not extend a requested delivery delay.

Provisioning must install `TenantJobOutboxSchema` in every tenant database catalogue. The source table is deliberately
outside `rain_tenancy`, because it belongs to the tenant database transaction. `TenantJobOutboxRelay` reads a finite
page under an ADMIN grant, releases that tenant transaction, then claims `rain_tenancy.tenant_delivery_receipt` and
calls `WorkQueue` in one control-plane transaction. Only after that commits does it open another tenant unit to
acknowledge the source row. A crash before source acknowledgement merely replays the receipt's original invocation;
a receipt or acknowledgement collision halts the lane and preserves the last acknowledged cursor. Handlers retain
their normal idempotency obligation.

`JobsTenantJobOutboxDispatcher` accepts only `TenantBindingMode.REQUIRED` definitions. It decodes the exact source
payload through the application `JobPayloadCodec`, preserves the source `notBefore`, and lets the independent
`TenantDurableJobContextProvider` mint the usual signed binding during the central enqueue. Thus an unavailable or
miswired tenant provider cannot silently turn tenant work into central work. This adapter contains no i18n or event
logic; those bounded contexts contribute their own jobs packages and fragments to the neutral jobs envelope.

## Scoped runtime capabilities

`HmacTenantCacheFactory` creates a `TenantCache` only for an admitted scope. The selected backend receives opaque
HMAC namespace/entry addresses containing the tenant epoch and current control-plane cache generation, never the raw
tenant reference or caller key. Incrementing that generation makes old entries unreachable; a backend failure is
returned to the caller and never falls back to an application-global cache. `TenantCacheRuntimeTask` exposes this same
adapter through `TenantRuntime.cache()` without replacing Spring's default cache manager.

`TenantSettingSpec` is a declared stable key with a bounded canonical codec, default and validator. A
`TenantSettingsFactory` loads one immutable, versioned snapshot; it does not offer arbitrary-map reads. Settings meant
to refer to secrets encode only a secret reference and are resolved by the owning integration. The corresponding
`TenantSettingsRuntimeTask` makes that fixed snapshot available as `TenantRuntime.settings()` for one lexical runtime.

`TenantRuntimeControlPlane` keeps runtime mutations separate from lifecycle control. A caller supplies both the
observed lifecycle control version and runtime-state version plus a `TenantOperationId`. `updateRuntimeSettings` accepts
only a `TenantSettingsRegistry.patch { ... }`: non-secret values are canonicalized by their declared codec, secret specs
accept only bounded secret references, and arbitrary raw maps cannot reach the database. `invalidateTenantCache` advances
the per-tenant opaque generation in the same fenced transaction. Both commands persist an idempotent receipt and audit
evidence with their mutation; inactive tenants and version conflicts are durable replayable outcomes, not best-effort
updates.

`HmacTenantObjectStoreFactory` is a separate scoped object-storage port. It derives an opaque HMAC prefix from tenant
namespace and epoch, rejects traversal in logical names, uses streaming content, and rechecks READ or WRITE admission
for each operation. It has no static/public URL method; a backend may opt into a bounded signed read URL only through
the already-authorized scoped store. Rain ships this port and its contract tests, not an implicit local-disk or S3
replacement.

No tenant functionality becomes active merely by adding this dependency. Servlet resolution, RLS data planes, control
plane persistence, jobs, audit, and pool directories are optional adapters that keep the same authority boundary.
