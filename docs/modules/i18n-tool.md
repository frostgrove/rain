# rain-i18n-tool

Optional deterministic authoring toolchain.

`CatalogTool` performs strict offline source checks and compilation. From the same fully checked
snapshot it can generate reflection-free Kotlin bindings (typed argument shapes — including a
singleton value for a zero-argument message — and exact
contract-validated `MessageDefinition`s), or a TypeScript declaration plus public-only structural
manifest. The TypeScript contract expressly sets `formattingParity=false`: it does not smuggle a
second renderer into the browser.

`TypeScriptPublication` writes an immutable `generations/sha256-*` directory, forces both role
files, then atomically moves the sole `current.json` pointer. A reader pins one pointer and verifies
fixed filenames, regular non-symlink paths, size limits and all digests. Atomic move unavailability
or a malformed/tampered publication is a typed refusal.

`PseudoLocalizer` supplies deterministic accent and RTL pseudo profiles. It preserves braced MF2
expressions and allowed markup byte-for-byte, replaces only literal runs, creates fresh approved
review stamps and recompiles the resulting source before returning it.

`TranslationAuthoringTool.review` stamps or rejects one declared translation against its exact
current source digest. Its non-pruning `merge` carries work only across unchanged source and
contract identities, reports retained obsolete keys, and never silently deletes a message.

`rain-i18n-tool` also packages a K2 compiler plugin. It consumes resolved IR rather than source
spelling: only the core `MessageKey` constructor counts, a same-named overload does not, and any
dynamic constructor operand makes the manifest incomplete. Generated Kotlin factories carry a
binary `RainI18nGeneratedBinding` marker with their exact key/revision/contract digest. The K2
plugin indexes those resolved factories, records their real calls, omits constructors inside their
generated bodies, and treats a callable-reference escape as incomplete evidence. This is the
semantic replacement for claims a lexer cannot make; it is pinned to the Kotlin compiler version
used by this tool release.

## Gradle plugin

The published `com.gd.rain.i18n` plugin is disabled until `rainI18n.enabled.set(true)`; an
unconfigured project therefore probes no catalog and produces no generated files. Its conventional
source is `src/main/i18n/catalog.json`, and every location remains overrideable through the
`rainI18n` extension. It registers `rainI18nCheck`, `rainI18nExtract`, `rainI18nCompile`,
`rainI18nGenerateKotlin` and `rainI18nExportTypeScript`; `check` depends on the first two when
enabled. Compile and Kotlin generation replace a single output atomically. TypeScript uses the
existing content-addressed publisher. In a Kotlin/JVM project the magic path also adds the
generated Kotlin directory to `main` and makes `compileKotlin` depend on generation. A caller may
still invoke `rainI18nGenerateKotlin`, choose another generated directory/package, or consume the
low-level generator directly; automatic source-set wiring is convenience, not a second contract.

When a Kotlin compilation is present, enabling the plugin automatically passes the packaged K2
plugin to every compilation. It writes separate manifests under
`build/reports/rain-i18n/k2/<compile-task>.json`, so main/test or multiplatform compilations never
race over one output. `failOnDynamicUsage` is `true` by default and makes that compilation fail
only after the bounded manifest has been atomically published; setting it to `false` exposes a
deliberately incomplete manifest for a caller-owned boundary. `semanticUsageDirectory` replaces
the default location, while the compiler-plugin option (`manifest`, `fail-on-dynamic`) remains the
full low-level path for non-Gradle builds.

`rainI18nExtract` remains an explicit conservative lexical manifest for direct Kotlin/Java source
inspection and Java-only projects. It is not semantic evidence and must not be used to infer
generated-binder usage; use the per-compilation K2 manifest for that decision.

The application distribution exposes the same low-level operations as `rain-i18n-tool`: `check`,
`compile`, `generate-kotlin`, `export-typescript`, `review`, `merge` and `pseudo`. `check` is
read-only. Each other single-file command uses a non-symlink descriptor lock, forced staging file
and atomic same-directory replacement; TypeScript continues to use its generation/pointer protocol.
