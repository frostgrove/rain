# rain-audit

The append-only audit trail: evidence of who did what, written in the transaction that made the change, in schema
`rain_audit`, with no foreign key into any subject table.

Add it when the application has to answer, later, who changed a resource or what an actor did.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-audit")
}
```

It brings [rain-persistence](persistence.md). Building rain-audit itself generates its jOOQ code from its migration
([schema ownership](../concepts/schema-ownership.md)).

## What it contributes

`RainAuditAutoConfiguration` — after Boot's jOOQ auto-configuration and `RainPersistenceAutoConfiguration`, when a
`DSLContext` and a `PlatformTransactionManager` exist, in every role:

| Bean | Condition | What it is |
|---|---|---|
| `auditRecorder` | no other `AuditRecorder` bean | `JooqAuditRecorder`, reading the `IdGenerator`, the `Clock`, the `CurrentActor` bean when there is one, and every `AuditEventType` bean |
| `auditEventTypesCheck` | — | every event type id is declared once |

## Configuration

None.

Bean-time problem:

| Path | Code | When |
|---|---|---|
| `audit:<module>.<action>` | `contradicts` | two `AuditEventType` beans with the same module and action |

## API

| Type | What it is |
|---|---|
| `AuditEventType(module, action, resourceKind, detailKeys)` | a kind of evidence, declared as a bean by the module or application that records it; `module`, `action` and `resourceKind` match `^[a-z][a-z0-9_-]{0,63}$`; `id` is `<module>.<action>` |
| `AuditOutcome` | `OK` (`ok`), `REFUSED` (`refused`), `FAILED` (`failed`) |
| `AuditDetail.of(vararg entries)` | at most 32 entries under keys matching `^[a-z][a-z0-9_]{0,63}$`; values are strings of at most 1024 characters, integers or booleans; serialised as one JSON object with keys in lexical order, so the same detail is always the same bytes |
| `AuditEvent(type, outcome, resourceId, detail, actor)` | one piece of evidence; `detail` may only carry keys its type declares; `resourceId` is 1 to 256 characters; `actor` overrides the current actor when the event is about someone else |
| `AuditRecorder.record(event)` | inserts inside the caller's transaction, so the evidence commits or rolls back with the change; refused with `IllegalStateException` outside a transaction |
| `AuditRecorder.recordIndependently(event)` | inserts in a transaction of its own, for an outcome the caller's transaction will not commit, such as a refusal |
| `AuditRecorder.ofResource(resourceKind, resourceId, after, limit)` | one page of a resource's trail, newest first |
| `AuditRecorder.ofActor(actor, after, limit)` | one page of an actor's trail, newest first |
| `AuditPage(entries, next)`, `AuditCursor(occurredAt, id)`, `AuditEntry` | a page, where the next one continues (null on the last page), and one row |

Declaring a detail schema per event type, instead of a list of words that must not appear, means that what reaches the
trail is exactly what the declaration allows. Recording an event whose type is not a declared bean is an
`IllegalArgumentException`.

Each row takes its id from `IdGenerator`, `occurred_at` from the `Clock`, the actor from the event or else from
`CurrentActor`, and `request_id` from the MDC key `request_id` that rain-web's request log sets.

```kotlin
@Bean
fun ticketClosed(): AuditEventType = AuditEventType("helpdesk", "ticket-closed", "ticket", setOf("reason"))

class TicketClosing(
    transactions: PlatformTransactionManager,
    private val tickets: TicketStore,
    private val audit: AuditRecorder,
    private val ticketClosed: AuditEventType,
) {
    private val transaction = TransactionTemplate(transactions)

    fun close(id: UUID, reason: String) {
        transaction.executeWithoutResult {
            tickets.close(id)
            audit.record(
                AuditEvent(
                    type = ticketClosed,
                    outcome = AuditOutcome.OK,
                    resourceId = id.toString(),
                    detail = AuditDetail.of("reason" to reason),
                ),
            )
        }
    }
}

val first = audit.ofResource("ticket", id.toString(), after = null, limit = 50)
val second = first.next?.let { audit.ofResource("ticket", id.toString(), after = it, limit = 50) }
```

## Schema

`rain_audit.audit_log`, migrated from `classpath:db/rain/audit`:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | primary key, minted by the application |
| `occurred_at` | `TIMESTAMPTZ NOT NULL` | |
| `actor_type`, `actor_id` | `TEXT` | both or neither (`audit_log_actor_complete`) |
| `request_id` | `TEXT` | |
| `module`, `action`, `resource_kind` | `TEXT NOT NULL` | |
| `resource_id` | `TEXT` | |
| `outcome` | `TEXT NOT NULL` | `ok`, `refused` or `failed` |
| `detail` | `JSONB NOT NULL` | |

The table has no update path and no version column: a row that can be rewritten states nothing. The actor is a
typed reference with no foreign key, so a person and a service account are both actors, and deleting a subject never
deletes its evidence ([ADR 0001](../adr/0001-schema-per-module.md)).

## Scale guarantees

| Read | Index | Shape |
|---|---|---|
| `ofResource` | `ix_audit_log_resource (resource_kind, resource_id, occurred_at DESC, id DESC)` | keyset seek past the cursor on `(occurred_at, id)`, `LIMIT limit + 1` |
| `ofActor` | `ix_audit_log_actor (actor_type, actor_id, occurred_at DESC, id DESC)` | the same |

`limit` is 1 to 500 (`AuditRecorder.MAX_PAGE`). No read counts rows. `AuditIT` asserts that the resource page's
plan reads `ix_audit_log_resource` and is bounded under plan criterion v2 (`QueryPlan.boundedScan`). An insert is one
statement.

## Error codes, health checks, commands

None.

## What it does not do

- It deletes nothing: rain-audit has no retention; how long evidence is kept is the application's decision.
- It does not search inside `detail`, count entries, or serve the trail over HTTP.
- It does not record anything on its own; every event is recorded by the code that knows what happened.
