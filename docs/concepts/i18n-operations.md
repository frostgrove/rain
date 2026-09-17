# i18n operations and capacity runbook

This is the release checklist for a catalog that has already passed the deterministic authoring
toolchain. It does not introduce a process-default locale, an ambient catalog head, or a combined
jobs/tenant/events operation.

## Author, review and publish

1. Run `rainI18nCheck` and K2 usage extraction on the exact source revision. An incomplete semantic
   usage manifest is a release refusal, not proof that a key is unused.
2. Use `review`, `merge` and `pseudo` from `rain-i18n-tool`; only a translation with matching source
   and contract digests enters a compiled artifact. Do not edit review stamps or a compiled artifact.
3. Compile, validate the exact profile/engine/ICU identity, and sign remote artifacts through the
   configured trust policy. Retain the previous release for every declared HTTP cache, durable job
   retry/redrive and tenant overlay horizon.
4. For a durable head, read `i18n-status`, then call `i18n-activate` or `i18n-rollback` with the
   exact revision/digest/version token, actor and operation. A conflict is a normal concurrent
   release outcome: re-read the head; never retry a stale token blindly.
5. Watch the bounded change-feed cursor after a release. Push is only a wake-up signal; each node
   repairs a missed notification by bounded cursor catch-up. `i18n-prune` never removes a retained
   or pinned release.

`rain-i18n-persistence` documents the command contract; the grammar/ICU identity sequence is in
[the upgrade runbook](i18n-upgrade-runbook.md). Tenant staging/review/activation uses only a minted
`TenantScope` through `rain-tenancy-i18n`; it has its own CAS/audit/change cursor and cannot alter
tenant admission or a global catalog head.

## HTTP and durable delivery

Check that a rendered HTTP response has the actual `Content-Language`; add `Vary:
Accept-Language` only if that protocol input selected the view. A fallback is visible through render
provenance and metrics, not an invisible success. A `PinnedSnapshot` job must retain and render its
exact catalog/overlay, while `CurrentAtRender` deliberately selects at handler time. A missing pin
is a closed failure—never silently render the current release.

## Dashboards and alerts

Install `rain-i18n-observability` when the process has a `MeterRegistry`. Its one bounded counter,
`rain.i18n.operations`, is described in the
[module documentation](../modules/i18n-observability.md). Dashboard the render and locale-resolution
outcome rates, fallback/layer distribution and durable change-feed lag. Alert on sustained render
failure, unexpected locale refusal, a fallback/layer distribution outside the product locale SLO,
or a cursor that cannot catch up within its retention budget. Alert thresholds, incident routing and
tenant-level drill-down belong to the deployment: the framework intentionally exposes no
tenant/message/locale-string labels to make such a dashboard convenient but unsafe.

## Capacity rehearsal

The kernel has a hard local ceiling: a source/artifact is at most 256 MiB and a catalog at most
1,000,000 messages (`I18nLimits.MAX_CATALOG_BYTES` and `MAX_MESSAGES`). A source decoder applies
the active local message-array bound before materializing the list; an artifact may narrow but never
widen the operator ceiling. Thus a literal 10M-message in-process catalog is intentionally refused,
rather than becoming an unbounded heap rehearsal disguised as a feature.

For the largest production-sized catalog, rehearse in a disposable environment with the same pinned
ICU identity and configured limits: canonical decode, compile, artifact verification, one
activate/rollback CAS cycle, change-feed catch-up, retained/pinned prune attempt, and parallel
en/ru/kk rendering. Record peak heap, compilation wall time, artifact size, cursor catch-up time
and refusal reason; keep the artifact and measurements with the release evidence. A product whose
data is larger than the hard scope must partition independent release domains explicitly—it must not
raise a global maximum, emit a 10M mutable map, or create an N-way integration package.
