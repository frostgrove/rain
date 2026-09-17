# rain-i18n-integration

Optional vendor-neutral boundaries for catalog release exchange, translation-management systems,
public browser/client contracts and tenant-overlay administration.

Remote release packages carry an immutable `CatalogRef` **and** a provenance envelope; both must
identify the exact bounded artifact bytes. `CatalogReleaseSource.fetch(CatalogReleaseFetch)` returns
a stable, bounded page with an exclusive exact-release cursor, or an explicit invalid-cursor refusal.
`CatalogReleaseSink.publish` has a bounded receipt and makes only an exact re-publication
idempotent. `InMemoryCatalogReleaseExchange` is the deterministic source/sink conformance adapter:
it proves cursor ordering, replay, capacity and conflict behavior without trusting or activating
anything. Scheduling, retry and activation remain application-owned. Every package still goes
through `TrustedCatalogLoader` before persistence or activation.

`TranslationManagementConnector` exchanges declared source contracts and unreviewed translation
candidates only. It cannot bypass local review, compiler or activation gates. The deterministic
`InMemoryTranslationManagementConnector` is a connector conformance fixture.

`PublicClientCatalogContract` exports public message schemas without message text and declares
`formattingParity=false`; client rendering is a separately versioned concern.

`TenantCatalogAdministration` is the low-level SDK for set/review/activate/rollback commands. It
requires application-provided authorization for each exact command, expected version and a typed
operation receipt. `InMemoryTenantCatalogAdministration` gives deterministic, exact-base overlay
review/activation semantics for tests. Durable tenancy storage, audit and epoch fencing live in the
separate `rain-tenancy-i18n` adapter, not in this vendor-neutral module.
