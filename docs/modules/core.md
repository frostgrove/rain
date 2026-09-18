# rain-core

The types every other module and every application's domain code can depend on: faults and error codes,
configuration problems, identifiers, advisory lock names and actors. rain-core has no Spring, no Jackson, no jOOQ
and no servlet API; `CoreDependenciesTest` fails the build when a class in `com.gd.rain.core` depends on any of
them.

Add it directly to a module that holds domain code and must not see a framework. A Spring application gets it
through every other rain module.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-core")
}
```

## What it contributes

Nothing at runtime: no auto-configuration, no properties, no beans. The types below are used by the modules that
do contribute — rain-boot reports `ConfigurationProblem`s, rain-web renders `Fault`s and registers
`RainErrorCodes`, rain-persistence mints ids through `IdGenerator`.

## API

| Package | Types |
|---|---|
| `com.gd.rain.core.error` | `Fault`, `FaultKind`, `ErrorCode`, `ErrorCodeCatalog`, `ErrorCodeRegistry`, `ErrorCodeRegistration`, `RainErrorCodes`, `Violation`, `PathStep`, `path(...)`, `ViolationOrigin`, `ViolationParameters`, `ViolationParameter`, `ViolationParameterValue`, `FaultTranslator` |
| `com.gd.rain.core.config` | `ConfigurationProblem`, `ProblemCode`, `ConfigurationProblemsException`, `ProblemCollector`, `problems { }` |
| `com.gd.rain.core.id` | `IdGenerator` |
| `com.gd.rain.core.lock` | `LockKey`, `keyOf(...)`, `Guard`, `Exclusively`, `Sharing` |
| `com.gd.rain.core.actor` | `Actor`, `CurrentActor` |
| `com.gd.rain.core.log` | `Correlation` |

### Faults

A `Fault` is the exception a refusal travels as. The model, the status of each kind and the wire format are
described in [errors](../concepts/errors.md). What a constructor refuses:

| Rule | Why |
|---|---|
| a `VALIDATION` fault names at least one violation | a client cannot act on "invalid" without knowing what |
| only `RETRYABLE` and `TOO_MANY_REQUESTS` carry `retryAfter`, and it is positive | nothing invents a delay for a refusal that repetition cannot fix |
| a message is non-empty and at most 16 KiB of UTF-8 (`Violation.MAX_MESSAGE_BYTES`) | a message is written to logs and bodies |

`detail` is the fault's own message, or the code's default message. `entity` and `op` never reach a client; they
name the refusing operation in `toString()` and therefore in the log.

```kotlin
throw Fault.validation(
    listOf(
        Violation(path("items", 0, "email"), RainErrorCodes.REQUIRED),
        Violation(
            path("items", 1, "name"),
            RainErrorCodes.TOO_LONG,
            parameters = ViolationParameters.build { integer("max", 64) },
        ),
    ),
)

throw Fault.conflict(TicketErrorCodes.TICKET_CLOSED)
throw Fault.retryable(retryAfter = Duration.ofSeconds(30))
```

Factories: `notFound`, `unauthorized`, `forbidden`, `conflict`, `badRequest`, `validation`, `retryable`,
`tooLarge`. Each takes the kind's default code unless another is given.

`Violation.ORDER` is a total order — by path (a prefix before its extensions, names before indexes at the same
depth), then `ViolationOrigin` (`INPUT` before `STATE`), code, message and typed parameters — so the same violations
always render as the same bytes.

`ViolationParameters` carries at most 16 uniquely named values. Names are lower snake case; the closed values are
bounded UTF-8 text, `Boolean`, `Long` and bounded `BigDecimal`. Parameters are not serialized by rain-core or rain-web.
They carry facts such as `min` and `max` to an explicit localization binding without putting a raw `Map<String, Any>`
or a pre-rendered locale-specific sentence into domain code.

### Error codes

An `ErrorCode` is `^[a-z][a-z0-9_]{0,63}$` with a non-blank default message; `ErrorCode.of` refuses anything else.
Two codes are equal when their values are. Codes are declared by an `ErrorCodeCatalog` with one `owner`:

```kotlin
object TicketErrorCodes : ErrorCodeCatalog {
    override val owner = "helpdesk-tickets"
    val TICKET_CLOSED = ErrorCode.of("ticket_closed", "the ticket is closed")
    override val codes = listOf(TICKET_CLOSED)
}
```

`ErrorCodeRegistry.of(catalogs)` answers `Registered(registry)` or `Refused(problems)`; a value declared twice,
by two catalogs or by one, is refused naming every owner, never merged. In a Spring application rain-web builds
the registry from every `ErrorCodeCatalog` bean ([rain-web](web.md)).

### Translators

A `FaultTranslator` turns a failure a transport did not raise into a `Fault`, or answers `null`. rain-web asks every
translator bean in order and renders the first answer. rain-persistence, rain-jobs and rain-llm ship one each.

### Configuration problems

`ConfigurationProblem(path, code, message, source)` is the one shape of every refusal a start-up can make; the codes
and the rendering are in [configuration](../concepts/configuration.md). `problems { }` collects them:

```kotlin
fun problemsOf(section: ExportProperties): List<ConfigurationProblem> =
    problems {
        expect(section.batch >= 1, "sample.export.batch") { "is ${section.batch}; a batch holds at least one row" }
        expect(section.target.isNotBlank(), "sample.export.target", ProblemCode.REQUIRED) { "no value is provided" }
    }
```

`ConfigurationProblemsException` carries every fatal problem found in one pass and refuses to be built without one.

### Identifiers

`IdGenerator.next(): UUID` is where a new identifier comes from. Rows are keyed by ids the application mints before
it writes them; rain-persistence supplies UUIDv7 ([schema ownership](../concepts/schema-ownership.md#identifiers)).
A test hands in a deterministic sequence.

### Advisory lock names

`keyOf(vararg parts)` derives a `LockKey` with FNV-1a 64 over the UTF-8 bytes of the parts, separated by a NUL byte,
so `("a", "bc")` and `("ab", "c")` differ. The derivation is fixed: two processes agree on a lock only if they derive
the same number, and a different derivation would make two builds take different locks for the same name. `Exclusively(key)` and `Sharing(key)` are the two modes a
[rain-persistence](persistence.md) `AdvisoryLocks` takes.

```kotlin
locks.take(Exclusively(keyOf("ticket", ticketId.toString())))
```

### Actors

`Actor(type, id)` names who performed an operation without a foreign key into any subject table: `type` matches
`^[a-z][a-z0-9_-]{0,63}$`, `id` is 1 to 128 characters. `CurrentActor.actor()` answers the actor of the operation on
the current thread, or `null`. The module that authenticates provides the bean; [rain-audit](audit.md) reads it.

### Correlation

`Correlation.MDC_REQUEST_ID` (`request_id`) is the MDC key rain-web's request log publishes the correlation id
under, and the key rain-audit reads it from.

## Error codes

`RainErrorCodes` (owner `rain-core`). rain-web registers this catalog in every servlet application.

| Code | Default message |
|---|---|
| `unique` | this value is already taken |
| `not_unique` | more than one record matches |
| `foreign_key` | the record this refers to does not exist |
| `restrict` | this record is still referred to |
| `required` | this field is required |
| `check` | this value is not allowed |
| `exclusion` | this value overlaps one that is already there |
| `too_long` | this value is too long |
| `out_of_range` | this value is out of range |
| `invalid_format` | this value is not in the expected format |
| `invalid_enum` | this value is not one of the allowed ones |
| `stale_version` | the record was changed by someone else |
| `validation_failed` | the request is not valid |
| `malformed_body` | the request body could not be read |
| `invalid_id` | the identifier could not be read |
| `invalid_cursor` | the page cursor could not be read |
| `unknown_field` | the request names a field that does not exist |
| `unknown_parameter` | the request names a parameter that does not exist |
| `bad_request` | the request could not be understood |
| `bad_query` | the query could not be read |
| `not_found` | not found |
| `conflict` | the request conflicts with the current state |
| `forbidden` | not allowed |
| `unauthenticated` | authentication is required |
| `too_large` | the request body is too large |
| `method_not_allowed` | this path does not answer that method |
| `not_acceptable` | no acceptable representation is available |
| `unsupported_media_type` | the request body has an unsupported media type |
| `too_many_requests` | too many requests; try again later |
| `deadlock` | the request could not be completed; try again |
| `serialization_failure` | the request could not be completed; try again |
| `lock_timeout` | the request could not be completed; try again |
| `statement_timeout` | the request could not be completed; try again |
| `transaction_aborted` | the request could not be completed; try again |
| `unavailable` | the request could not be completed; try again |
| `deadline_exceeded` | the request did not finish within its time budget |
| `unmapped_status` | the request failed |
| `internal` | the request failed |

`invalid_cursor` is rain-core's because rain-access and rain-crud both refuse a page cursor they cannot read with it, and
one application uses both: a code two catalogs declare refuses the start (`ErrorCatalogsRegisterTogetherTest` registers
every catalog of rain and its samples together).

## Health checks, schema, commands

None.

## What it does not do

- It maps a `FaultKind` to exactly one status and nothing else; rendering, the status table for statuses a
  framework chose, and `Retry-After` are rain-web's.
- It does not serialise anything and chooses no JSON library.
- It holds no global state: no registry singleton, no current-actor thread local of its own.
