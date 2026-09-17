# ADR 0004: i18n is an immutable catalog and explicit view

## Status

Accepted.

## Context

A conventional JVM message bundle combines mutable process defaults, late lookup and raw string
keys. That makes a locale, zone, wording revision and tenant branding hard to reproduce for a
request, a retrying job or an audit trail. It also lets a translation alter program contracts when
it should affect presentation only.

Rain needs the ergonomic path to be short while retaining an exact low-level escape hatch for
transport adapters, generated clients and exceptional product behavior.

## Decision

`rain-i18n` models a message as its qualified key, checked contract revision/digest and closed typed
arguments. It compiles a complete source declaration into an immutable content-addressed
`CatalogSnapshot`. Rendering happens only through an explicit `I18nView`, which owns the exact
snapshot, negotiated locale, formatting zone, presentation policy and permitted whole-message
overlays.

ICU4J is the Unicode, CLDR and time-zone formatter backend. A deployable runtime declares its
grammar/engine/ICU-CLDR-tzdb identity; artifact loading and controller activation refuse an
incompatible identity. Catalog source/artifact trust, release retention, tenant overlays and durable
delivery are separate optional adapters around this kernel.

The magic path is `rain-i18n-web`: one request scope exposes `I18nRuntime`, while the underlying
view and message definition remain available directly. No global locale, time zone, thread-local
catalog or reflection-based binder becomes authoritative.

## Consequences

- Business code does not pass localized strings, raw key maps or ambient locales across boundaries.
- A re-render can be tied to the exact snapshot and presentation context that produced it.
- Generated Kotlin bindings are the normal path; the validated manual `MessageDefinition` DSL is
  the supported low-level escape hatch.
- A browser contract is structural and public-only; it does not claim formatter parity with ICU4J.
- Applications that do not select i18n depend on none of ICU4J, catalog storage or locale bridges.
