# rain-tenancy-event

The only Rain module that depends on both tenant scopes and event namespaces.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-tenancy-event")
}
```

`TenantEventNamespace.of(scope)` derives a domain-separated event namespace from an already-minted tenant scope and
its epoch. It never stores the raw tenant reference; restore/reprovision gets a new epoch and therefore a new event
namespace. Neither `rain-event` nor `rain-tenancy` depends on the other.

Inside `TenantDataPlane.read/write/durable`, `TenantEventStoreFactory.forUnit(unit)` builds a PostgreSQL event store
from that exact unit's `DSLContext`, `BackingIdentity`, and `TransactionAuthority`. The event store opens no second
transaction or connection. This applies equally to shared-row and database-per-tenant units; the latter never touches
the control-plane datasource. The adapter is deliberately limited to tenancy plus event persistence—jobs, i18n,
realtime and projections plug in through their own host-module contracts rather than a product-combination module.
