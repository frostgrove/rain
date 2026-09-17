# rain-mf2/v1 conformance corpus

`rain-mf2/v1` is a fixed artifact profile, not a best-effort parser. The executable corpus is
[`Mf2ProfileCorpusTest`](../../rain-i18n/src/test/kotlin/com/gd/rain/i18n/Mf2ProfileCorpusTest.kt);
its cases are the minimal stable examples that every engine claiming this profile must accept or
refuse before it can load a catalog.

| Area | Canonical accepted form | Required refusal boundary |
|---|---|---|
| Text and metadata | `{ $text :string }`, `u:id`, `u:dir=ltr\|rtl\|auto\|inherit` | unknown universal metadata/direction, unbounded or malformed token |
| Numbers | `:number`, `:integer`, `:percent`, `:offset offset=1.5`, `:unit unit=meter` | incompatible argument type, unknown option, malformed offset, absent/invalid unit |
| Money | `{ $money :currency }` | non-money input and invalid number/currency option |
| Dates | `:date`, `:time`, `:datetime` | non-date/non-instant input and invalid style option |
| Declarations | `.input`, `.local $next = { $count :offset add=1 }` | shadowing, unknown input/local, non-expression declaration |
| Select | `.match $kind $count`, exact/cardinal/ordinal keys and all-`*` fallback | duplicate selector/key, noncanonical numeric/plural key, no fallback, unclosed variant |
| Rich structure | `{#strong u:id=person}{ $text }{/strong}` for an allowlisted rich contract | plain output, unapproved tag, attributes such as `href`, mismatched/unclosed tags |

Whitespace in this page is explanatory. Artifact source uses its canonical encoder; the corpus
asserts the parsed profile, not a browser or JDK formatter. Locale-dependent formatting goldens
remain separate so a grammar upgrade cannot hide an ICU/CLDR output change.
