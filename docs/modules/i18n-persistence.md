# rain-i18n-persistence

Optional PostgreSQL lifecycle adapter for immutable i18n artifacts and catalog release heads.

It is a two-context `i18n ↔ persistence` bridge. It persists verified catalog artifacts,
immutable releases, opaque CAS heads, retention pins, audit records and a cursor-based change feed.
It neither depends on jobs/events/tenancy nor invents a combined integration module: those contexts
compose through their own narrow pairwise contracts.

The caller owns the Spring transaction. A release/head mutation writes its audit and change-feed
record in that same transaction; notifications are only a wake-up hint and consumers recover through
the durable bounded cursor. A pin release is identity-fenced by pin id, scope, exact catalog
reference and owner; bounded expiry sweeping is auditable as immutable `PIN_EXPIRED` evidence.

For the magic path, set `rain.i18n.persistence.enabled=true` and declare `runtime` plus every
retention/change-feed limit. This registers a local-build-only `TrustedCatalogLoader` and a
`CatalogReleaseStore`. Applications using signed remote artifacts replace the loader with their own
keyring-backed instance; the store's low-level transaction SDK stays the same.

If the application also declares one explicit `CatalogReleaseScope`, the same auto-configuration
provides a read-only `CatalogSnapshotProvider` backed by that scope's authoritative durable head.
It opens a short transaction for each read; it does not guess scope from a request, job or tenant,
and does not maintain an unbounded cache. An application can replace that bean with its own
cache/change-feed-backed provider without changing the `rain-i18n` or web contracts.

## Operations

With that same explicit scope, the module contributes four one-shot Rain commands:
`i18n-status`, `i18n-activate`, `i18n-rollback` and `i18n-prune`. They produce one bounded line
and never select a scope from a CLI argument. `i18n-activate` accepts a regular, non-symlink local
artifact and requires either `--create` or all of `--expected-revision`, `--expected-digest` and
`--expected-version`; `i18n-rollback` requires the same exact expected-head token plus an exact
target revision/digest. Both mutators require explicit `--actor` and `--operation` for immutable
audit evidence. The commands are a convenience layer: applications may instead use the complete
caller-transaction `CatalogReleaseStore` SDK for signed remote artifacts, custom authorization or
their release workflow.
