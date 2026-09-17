# rain-tenancy-i18n

The sole optional adapter allowed to depend on both `rain-tenancy` and `rain-i18n`.

`TenantI18nRuntimeTask` enters after Rain has minted and admitted a `TenantScope`. Its provider sees
that opaque scope and an exact catalog snapshot, then returns tenant default locale/time zone and an
already reviewed `TENANT` overlay. The task creates one explicit `I18nView`, records the scope epoch
and exact catalog/overlay identities, exposes it through `TenantI18nRuntime.current()` for scoped
ergonomics, and restores the prior state through its paired runtime lease.

`TenantOverlayStore` is the explicit low-level lifecycle SDK: staging, review, activation and rollback
are fenced by an optimistic epoch-local version and an operation receipt. A revision is immutable; a
draft has no content-addressed overlay reference, and an active overlay is returned only for its exact
base catalog snapshot. `TenantOverlayAdministration` is the magic-first facade: it accepts a minted
`TenantScope`, rechecks its `ADMIN` grant and derives the store partition, while the full store/command
SDK remains available for exceptional workflows. `InMemoryTenantOverlayStore` is deterministic
conformance infrastructure.

`PostgresTenantOverlayStore` owns only `rain_tenancy_i18n`: it joins a caller-owned transaction,
serializes one opaque tenant namespace + epoch through a transaction advisory lock, and writes head,
immutable audit evidence, operation receipt and cursor-addressed change row atomically. `readChanges`
is the repair path for missed invalidations; there is deliberately no background watcher. A namespace
is a 32-byte digest derived from a minted `TenantScope`, never the raw tenant reference.

The adapter cannot accept a raw tenant id, mutate tenant resolution/lifecycle/RLS/routing, install an
application overlay, or mutate JVM/Spring global locale state. It is strictly the `tenancy ↔ i18n`
bridge: a product that also uses jobs or events composes their independent pairwise bridges rather
than adding a `tenancy-i18n-jobs` or other N-way package.
