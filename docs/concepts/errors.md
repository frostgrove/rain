# Errors

Every refusal rain answers is an RFC 9457 `application/problem+json` body in **problem format v1**. Controllers,
the MVC exception handler, the error controller and the transport filters that refuse before MVC all render through
one `ProblemRenderer`, so the same refusal is the same bytes wherever it happened.

```json
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "the request is not valid",
  "code": "validation_failed",
  "errors": [
    {"pointer": "/items/0/email", "code": "required", "message": "this field is required"}
  ],
  "partial": true
}
```

## The model (rain-core, no Spring)

- **`Fault`** — the exception a refusal is thrown as: a `FaultKind`, a registered `ErrorCode`, a `detail`, the
  `violations`, whether they are `partial`, an optional `retryAfter`, the `cause`.
- **`FaultKind`** — a closed set; each kind renders as exactly one status:

  | Kind | Status | Default code |
  |---|---|---|
  | `BAD_REQUEST` | 400 | `bad_request` |
  | `UNAUTHORIZED` | 401 | `unauthenticated` |
  | `FORBIDDEN` | 403 | `forbidden` |
  | `NOT_FOUND` | 404 | `not_found` |
  | `METHOD_NOT_ALLOWED` | 405 | `method_not_allowed` |
  | `NOT_ACCEPTABLE` | 406 | `not_acceptable` |
  | `CONFLICT` | 409 | `conflict` |
  | `TOO_LARGE` | 413 | `too_large` |
  | `UNSUPPORTED_MEDIA_TYPE` | 415 | `unsupported_media_type` |
  | `VALIDATION` | 422 | `validation_failed` |
  | `TOO_MANY_REQUESTS` | 429 | `too_many_requests` |
  | `INTERNAL` | 500 | `internal` |
  | `RETRYABLE` | 503 | `unavailable` |

- **`Violation`** — one thing wrong with the input: a path of steps (rendered as an RFC 6901 JSON pointer), a code
  and an optional message. A `VALIDATION` fault without violations cannot be constructed.
- **`ErrorCode`** — `^[a-z][a-z0-9_]{0,63}$` with a default message.

```kotlin
throw Fault(
    FaultKind.CONFLICT,
    TicketErrorCodes.TICKET_CLOSED,
    detail = "the ticket is closed",
)
```

## Codes are registered

Codes are declared by `ErrorCodeCatalog` beans, one owner per catalog:

```kotlin
object TicketErrorCodes : ErrorCodeCatalog {
    override val owner = "helpdesk-tickets"
    val TICKET_CLOSED = ErrorCode.of("ticket_closed", "the ticket is closed")
    override val codes = listOf(TICKET_CLOSED)
}
```

At start-up every catalog is registered together. Two catalogs with one owner, a blank owner, a malformed code and a
code declared twice are refused in one report. A fault that carries a code no catalog declares is not sent to the
client as is: it renders as `500 internal`, and the unregistered code is logged.

## Rules of v1

- `type` is `about:blank`, `title` is the status's reason phrase, `detail` is the fault's detail.
- `errors` is present only when the fault names violations; sorted deterministically, at most 100 entries, each
  `{pointer, code, message}` (the code's default message when the violation has none).
- `partial: true` is present when the fault said its violations are partial or when violations were cut at 100.
- `Retry-After` is sent **only** when the fault carries `retryAfter` (whole seconds, rounded up). Only `RETRYABLE`
  and `TOO_MANY_REQUESTS` may carry one; nothing invents a delay.
- An internal fault says only `the request failed`, names no violations, and its cause is logged with the request id.
- Keys are written in exactly this order, by a mapper no application Jackson setting reaches.

## Refusals rain did not raise

Spring MVC refuses requests itself (unsupported media type, missing parameter, async timeout, …). rain's exception
handler extends `ResponseEntityExceptionHandler`, so Boot's own problem-details handler backs off, and maps the
status Spring chose through the **status table v1**: the status passes through as the kind with the same status. A
status outside the table is not guessed at — it renders as `500 unmapped_status` and is logged.

Two refusals take precedence over whatever Spring chose: a request whose budget ran out is `503 deadline_exceeded`,
and a request whose body went over `rain.web.body-limit` is `413 too_large`.

## Data access

`FaultTranslator` beans turn an exception into a fault. rain-persistence ships one keyed on the PostgreSQL SQLSTATE,
read from the whole cause chain and every `SQLException.nextException`:

| SQLSTATE | Fault |
|---|---|
| 23505 unique_violation | 409 `unique` |
| 23503 foreign_key_violation | 409 `foreign_key` |
| 23001 restrict_violation | 409 `restrict` |
| 23P01 exclusion_violation | 409 `exclusion` |
| 23502 not_null_violation | 422 with a `required` violation |
| 23514 check_violation | 422 with a `check` violation |
| 22001 string_data_right_truncation | 422 with a `too_long` violation |
| 22003 numeric_value_out_of_range | 422 with an `out_of_range` violation |
| 22P02 invalid_text_representation | 400 `invalid_format` |
| 40P01 deadlock_detected | 503 `deadlock` |
| 40001 serialization_failure | 503 `serialization_failure` |
| 55P03 lock_not_available | 503 `lock_timeout` |
| 57014 query_canceled | 503 `statement_timeout` |
| 25P02 in_failed_sql_transaction | 503 `transaction_aborted` |

Without a SQLSTATE, Spring's `OptimisticLockingFailureException` is 409 `stale_version` and `DuplicateKeyException`
is 409 `unique`.

A state that is not in the table (including 42P01 undefined_table and 42703 undefined_column, which mean the schema
and the code disagree) is not classified: the fault is 500 `internal` and the cause is logged.
