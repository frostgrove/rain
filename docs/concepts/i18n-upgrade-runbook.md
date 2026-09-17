# i18n grammar and ICU data upgrade runbook

`rain-mf2/v1` and the configured ICU/CLDR/tzdb identity are release identities, not incidental
library versions. An upgrade never changes the meaning of an already published artifact in place.

1. Pin the candidate ICU4J artifact and record its exact `icu-cldr-tzdb` identity. Build the
   catalog with the current production compiler and again with the candidate runtime.
2. Run the versioned grammar/profile corpus plus locale, formatting and rich-part goldens under both
   identities. A changed rendered byte/part/provenance result is a compatibility event, not an
   accepted diff.
3. Compile fresh canonical source/artifact bytes with the candidate identity and sign or otherwise
   authorize them through the normal release flow. Retain the old exact release for every declared
   request cache, job retry/dead-letter/redrive and tenant overlay horizon.
4. Deploy nodes configured for the candidate identity only after the candidate artifact is ready.
   A node whose `CatalogRuntimeIdentity` differs must refuse activation/rendering; never mix JDK
   and ICU time-zone results or silently use a process default.
5. Activate with opaque-head CAS, observe the bounded catalog change feed and repair missed push
   notifications by cursor catch-up. Verify `Content-Language`, locale-resolution and render
   observer outcomes without adding high-cardinality labels.
6. Roll back through the retained exact release under the same CAS token if the release is bad.
   Do not mutate an artifact, translation review stamp, overlay or pin to impersonate the old data.

New grammar syntax is a new `rain-mf2/vN` profile with its own corpus and engine identity. It never
reinterprets a `v1` artifact.
