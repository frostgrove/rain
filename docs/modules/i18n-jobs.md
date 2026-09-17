# rain-i18n-jobs

Optional durable-delivery adapter for `rain-i18n` and `rain-jobs`. A job/outbox carries a typed
`I18nDeliveryIntent`, explicit recipient locale/zone and either `CurrentAtRender` or an exact
`PinnedSnapshot` reference. A missing pinned catalog or overlay is the closed
`SnapshotUnavailable` outcome; it never becomes a newer catalog silently.

When both modules are enabled, `I18nJobContextProvider` is one independent provider in the jobs
durable-context envelope. It captures only an explicit `I18nJobDeliveryContext`, restores a scoped
`I18nJobRuntime` before handler execution, and clears it afterwards. The provider owns no tenant
authority, event data or job transport. A product combines those concerns by registering separate
providers, not by creating a `jobs-i18n-events` module.

Supplying `I18nSnapshotRepository` activates the provider automatically. At enqueue, use
`I18nJobContext.with(I18nJobContext.currentAtRender(view)) { ... }` or explicitly choose
`pinnedSnapshot(view)`; advanced code can call `I18nJobContextProvider` and `I18nDeliveryViews`
directly. The two choices are deliberately visible in both APIs.

For `PinnedSnapshot`, a product supplies an explicit `I18nDurablePinPolicy`. When its context
also has the durable `CatalogReleaseStore`, one explicit `CatalogReleaseScope`, and a transaction
manager, the magic path installs `CatalogReleaseStoreI18nDurableDeliveryPins`. It acquires the
exact catalog pin in the already active jobs enqueue transaction; terminal release and bounded
expiry sweep become their own committed, idempotent store operations. A product can replace that
bean with any `I18nDurableDeliveryPins` implementation without changing the provider or envelope.
The provider serializes opaque pin id, invocation, exact catalog reference and expiry; after the
jobs ledger has a terminal receipt, its neutral durable-context lifecycle hook invokes release.
`I18nDurablePinSweepWork` is registered automatically only when the product supplies the explicit
`I18nDurablePinSweepPolicy`. This is still one `jobs ↔ i18n` seam: neither the context nor the
sweep sees events, tenancy or request state.
