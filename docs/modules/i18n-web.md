# rain-i18n-web

Optional servlet/MVC bridge for `rain-i18n`. A filter mints one explicit request view from a supplied
catalog provider; `I18nRuntime` is request-scoped convenience, while the kernel's
`CatalogSnapshot.view` remains the full low-level SDK.

The module is not enabled merely by importing `rain-web`: an application supplies a
`CatalogSnapshotProvider`, and the auto-configuration then contributes `Accept-Language` input,
the filter and a Spring `LocaleContextResolver` bridge. Applications add explicit sources, such as
`ParameterServletLocaleSource`, only on routes where they are declared protocol inputs.

`Content-Language` is emitted only after a unique actual template locale is known; `Vary:
Accept-Language` is added only when that protocol source decided the request. The first dispatch
keeps its immutable context as a request attribute until a servlet request-destruction listener
cleans it, so async and error redispatches cannot select a later catalog release. The filter restores
any outer `LocaleContextHolder` state in `finally` on every dispatch; it is compatibility state,
never Rain's source of truth.

For ordinary MVC and RFC 9457 serialization, the filter applies `Content-Language` immediately
before Spring starts writing the body, rather than after a response has committed. A handler that
streams before rendering owns its representation metadata and must state it before writing.

`I18nRequestContext.capture()` creates an explicit immutable in-process hand-off for executor work.
`I18nLocaleContextTaskDecorator` may additionally bridge legacy Spring locale consumers, but it does
not create an inheritable or authoritative i18n thread-local. `LocalizedProblemMessages` is the
allowlist for localizing RFC 9457 human text without changing status, code, JSON pointer, retry or
internal-error redaction contracts.
## Local artifact bootstrap

`rain.i18n` is an optional validated configuration section. With `enabled=false`, its local
artifact and runtime leaves are forbidden; with `enabled=true`, `artifact-location` and all three
runtime identities are required. The local provider accepts only bounded `classpath:` or `file:`
canonical artifacts. Durable persistence/remote release authority replaces `CatalogSnapshotProvider`
and does not turn this bootstrap into a second catalog source.

Set `rain.i18n.enabled=true`, `rain.i18n.artifact-location` (`classpath:` or `file:` only), and the
exact `rain.i18n.runtime` profile/engine/ICU-CLDR-tzdb identity to obtain a default immutable
`CatalogSnapshotProvider`. The whole artifact is bounded and validated before the bean exists.
Supplying a provider directly takes precedence for durable or signed remote release modes.

## Servlet locale policy

`rain.i18n.web` controls the optional servlet magic separately. It is enabled by default once a
catalog provider exists; set `rain.i18n.web.enabled=false` to install no filter, MVC bridge or
request runtime. `query-source=lang` and `cookie-source=locale` opt in those named sources in
that precedence order before `Accept-Language`; malformed or duplicate locale cookies are a 406
refusal, never a guessed preference. `propagate-executors=true` publishes the named
`rainI18nLocaleContextTaskDecorator` for an application-selected executor only. It carries the
legacy Spring locale holder, while rendering work still receives an explicit `I18nContextSnapshot`
or `I18nView`.
