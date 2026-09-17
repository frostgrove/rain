# rain-i18n-test

Deterministic catalog, view and message-binding fixtures for consumers testing `rain-i18n` adapters.
It fixes every test input explicitly — locale, zone, catalog identity and wording — and never mutates
JVM locale/time-zone defaults. The module is test-only support; it adds no production runtime policy.

Its conformance suite pins ICU 78.3 output for `en-US`, `ru` and `kk` across values, ranges,
plural rules, temporal/list/relative formatting and display names. A parallel rich-message golden
also fixes the locale-specific number rendering, safe structural parts and automatic bidi isolates.
Accent and RTL pseudo catalogs are checked separately by `rain-i18n-tool` before they can be used
as a fixture source.
