# rain-realtime

PostgreSQL `LISTEN`/`NOTIFY` as an in-process bus: a publisher that notifies on the caller's transaction, and a listener
that parks one dedicated connection on `LISTEN` and fans notifications out to bounded subscriptions across every
process sharing the database.

It is an accelerator, not storage: there is no history, no replay, no acknowledgement and no delivery guarantee. The
source of truth stays an ordinary read. A lost connection is a gap, and a consumer that learns of a gap reads the
current state again and subscribes again.

Add it when a client should learn of a change without polling for it.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-realtime")
}
```

It brings [rain-persistence](persistence.md), [rain-observability](observability.md), HikariCP and the PostgreSQL driver.

## What it contributes

`RainRealtimeAutoConfiguration` — after Boot's `DataSourceAutoConfiguration` and jOOQ auto-configuration, only when a
key under `rain.realtime` is stated. The same test decides whether validation treats the optional section as present, so
the section is either validated and wired, or absent and neither.

| Bean | Condition | What it is |
|---|---|---|
| `realtimeErrorCodes` | — | `RealtimeErrorCodes` |
| `realtimePublisher` | no other `RealtimePublisher` bean | `pg_notify` through the application's `DSLContext`; exists in every role, because it holds nothing |
| `realtimeListener` | role `api` | `RealtimeListener`, a `SmartLifecycle`; its pool is closed with the bean |
| `realtimeListenerHealthCheck` | role `api` | the `realtime.listener` check |

The listener opens its connection from its own one-connection Hikari pool, beside the application's pool: it holds the
connection for the life of the process, which would otherwise take one connection out of the application's budget for
good. Only `spring.datasource.url`, `username`, `password` and `driver-class-name` are read; `spring.datasource.hikari.*`
configures the application's pool, not this one. The pool is never trimmed or recycled under the listener
(`maxLifetime`, `idleTimeout` and `keepaliveTime` are 0) and keeps no idle connection of its own.

## Configuration

`rain.realtime` is optional; stating any key under it requires the three without defaults.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.realtime.pool-name` | required | the name of the listener's own pool | not blank |
| `rain.realtime.subscriber-buffer` | required | events one subscriber may fall behind by before its stream ends with `OVERFLOW` | 1 to 2147483646 |
| `rain.realtime.max-subscriptions` | required | open subscriptions this process holds at most | at least 1 |
| `rain.realtime.min-backoff` | `200ms` | the wait before reconnecting after a session that connected | positive |
| `rain.realtime.max-backoff` | `10s` | the longest wait between reconnects | not below `min-backoff` |
| `rain.realtime.poll-interval` | `200ms` | how long one wait for notifications lasts before subscription changes are applied again; it bounds how long a subscribe waits for its `LISTEN`, not how late an event is | a whole number of milliseconds from 1 ms to 2147483647 ms |
| `rain.realtime.connect-timeout` | `10s` | how long the listener waits for its connection, the first one at start-up included | at least `250ms`, below which Hikari would substitute its own value |

`spring.datasource.url` is required where the listener is built.

`subscriber-buffer` and `max-subscriptions` have no default because together they are the memory the bus may hold: at
most `max-subscriptions × subscriber-buffer` events of up to 7999 bytes each.

```yaml
rain:
  realtime:
    pool-name: realtime-listener
    subscriber-buffer: 32
    max-subscriptions: 1000
  health:
    checks:
      realtime.listener: required
```

## API

### Channels

`Channel.of(name)` or `Channel.parse(name)` (answering `ChannelParse.Valid` or `ChannelParse.Invalid(rule, message)`).
The rules, in the order they are checked (`ChannelRule`):

| Rule | Refuses |
|---|---|
| `EMPTY` | an empty name |
| `TOO_LONG` | more than 63 UTF-8 bytes: PostgreSQL truncates an identifier silently, so two names agreeing in their first 63 bytes would be one channel |
| `CHARACTER` | any character outside `A-Z a-z 0-9 _ - : .`; `LISTEN` takes an identifier, not a parameter, and a set with no quote cannot end one |

### Publishing

```kotlin
transaction.executeWithoutResult {
    tickets.save(ticket)
    publisher.publish(Channel.of("ticket.${ticket.id}"), """{"id":"${ticket.id}","version":${ticket.version}}""")
}
```

`NOTIFY` inside a transaction is delivered at commit and discarded on rollback by the server, so an event about a write
that rolled back cannot reach a subscriber. Published outside a transaction it would be delivered at once, and nothing
would show the guarantee was lost; so that is refused. The `DSLContext` takes its connection from the transaction, as
Boot's jOOQ auto-configuration does. Refusals, checked in this order before any statement:

| `PublishRefusal` | When |
|---|---|
| `PayloadNotText` | the payload holds a NUL or an unpaired surrogate |
| `PayloadTooLarge(atLeastBytes)` | the payload is more than 7999 UTF-8 bytes; counting stops as soon as the limit is passed |
| `NotInTransaction` | no transaction is active |

Large data belongs in a table; an event carries an identifier and a revision.

### Subscribing

```kotlin
fun stream(
    id: UUID,
    send: (String) -> Unit,
): SubscriptionEnd =
    listener.subscribe(Channel.of("ticket.$id"), Duration.ofSeconds(5)).use { subscription ->
        send(tickets.currentJson(id))
        generateSequence { subscription.poll(Duration.ofSeconds(15)) }
            .onEach { if (it is Next.Event) send(it.event.payload) }
            .filterIsInstance<Next.Ended>()
            .first()
            .end
    }
```

Subscribing before reading the current state closes the window between "read the old state" and "subscribed after the
new one": an event in between waits in the buffer. The consumer still applies an event idempotently or discards a stale
one by its revision.

| Member | Behaviour |
|---|---|
| `RealtimeListener.subscribe(channel, timeout)` | returns once the listening connection has issued its `LISTEN`, so an event committed after it returns cannot be missed; refused with a retryable `Fault`: `realtime_unavailable` when no session is live or the `LISTEN` is not confirmed within `timeout`, `realtime_subscription_limit` when `max-subscriptions` are open |
| `RealtimeListener.isLive()` | whether a session is live |
| `RealtimeListener.subscriptionCount()` | open subscriptions |
| `RealtimeListener.unsolicitedNotifications()` | notifications on a name no subscription held, or on a name that is not a valid channel; each is logged with the name's length, never its content |
| `Subscription.poll(timeout)` | `Next.Event(event)`, `Next.Idle` when the timeout passed, or `Next.Ended(end)` at once when the stream has ended |
| `Subscription.drain()` | what is already buffered, without waiting |
| `Subscription.close()` | ends the stream with `UNSUBSCRIBED`; idempotent |
| `Subscription.end`, `isOpen` | how the stream ended, or null while open |

| `SubscriptionEnd` | Meaning | What the holder does |
|---|---|---|
| `GAP` | the listening connection dropped; events may be missing | read the current state again and subscribe again |
| `OVERFLOW` | `subscriber-buffer` events were waiting and another arrived | the same |
| `CLOSED` | the listener stopped with the process | finish |
| `UNAVAILABLE` | listening on the channel was not confirmed within the subscribe timeout | subscribe again later |
| `UNSUBSCRIBED` | the holder closed the subscription | nothing |

The end travels through the same queue as the events, as a sentinel behind them, so events delivered before the end are
read before it and a poller parked on an empty queue wakes.

### Sessions

A session is one connection. It starts with `UNLISTEN *`, so nothing a connection listened to before reaches it, and
reads `max_identifier_length` and `block_size` from the server; a server built with other limits than `NotifyRules`
version 1 declares (63 and 8192) is refused with `NotifyRulesMismatch`. A session that fails first ends every
subscription with `GAP`, then evicts its connection from the pool, so a connection still alive never serves another
session with its old `LISTEN`s. The listener then waits on the backoff ladder — `min-backoff` after a session that
connected, doubling on each failure to connect, never beyond `max-backoff` — and opens a new session. Subscriptions are
not carried over.

Start waits for the first session. If it is not live within `connect-timeout`, the start is refused with
`ListenerStartRefused` and nothing is retried: a process that accepts subscriptions that will never deliver is worse than
one that does not start. The listener starts after the web server and stops before the server's graceful shutdown waits
for open streams, so streams end with `CLOSED`.

## Error codes

`RealtimeErrorCodes` (owner `rain-realtime`):

| Code | Default message | When |
|---|---|---|
| `realtime_unavailable` | live updates are not available right now; try again | no live session, or `LISTEN` not confirmed in time; `503` |
| `realtime_subscription_limit` | too many live update subscriptions are open; try again later | `max-subscriptions` reached; `503` |

## Health checks

| Check | Code | Runs in | Fails when |
|---|---|---|---|
| `realtime.listener` | `realtime` | `api`, when `rain.realtime` is stated | the listener has no live session |

## Scale guarantees

- One connection per process listens for every subscription.
- Delivery never blocks: an event goes to each subscriber of its channel without waiting, and a subscriber that fell
  behind is ended, not waited for.
- The listening thread's work between two waits is proportional to the subscription changes made, not to the number of
  subscriptions; a notification finds its channel by name in a map.
- Memory is bounded by `max-subscriptions × subscriber-buffer` events.
- Checking a payload costs at most 8000 characters, whatever its size.

## Schema, commands

None.

## What it does not do

- It stores, replays or acknowledges nothing.
- It never delivers an event for a transaction that rolled back, and never publishes outside a transaction.
- It does not restore subscriptions after a gap.
- It listens in no process without the `api` role.
