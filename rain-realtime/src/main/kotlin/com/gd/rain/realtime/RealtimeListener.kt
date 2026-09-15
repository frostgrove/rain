package com.gd.rain.realtime

import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One dedicated connection parked on `LISTEN`, serving every subscription of the process.
 *
 * **Sessions.** A session is one connection from the listener's own pool. It starts with `UNLISTEN *`,
 * so nothing a connection listened to before reaches it, and it checks the server against
 * [NotifyRules]. A session that fails finishes every subscription with [SubscriptionEnd.GAP] before
 * anything else happens and evicts its connection from the pool, so a connection that is still alive
 * never serves another session with its old `LISTEN`s. Then the listener waits on the
 * [ReconnectBackoff] ladder and opens a new session; subscriptions are not carried over.
 *
 * **Start-up.** [start] waits for the first session. If it does not go live within `connect-timeout`,
 * start is refused with [ListenerStartRefused] and nothing is retried: a process serving
 * subscriptions that will never deliver anything is worse than one that does not start.
 *
 * **Subscriptions.** The listening thread alone issues `LISTEN`/`UNLISTEN`, because both are scoped
 * to its connection. A subscribe records the change and waits until the thread has confirmed the
 * `LISTEN`; the thread applies changes one channel at a time, so its work between two waits for
 * notifications is proportional to the changes made, not to the number of subscriptions. At most
 * `max-subscriptions` are open at once; one more is refused.
 *
 * **Delivery never blocks.** An event goes to each subscriber of its channel without waiting; a
 * subscriber whose buffer is full is ended with [SubscriptionEnd.OVERFLOW]. A notification on a name
 * no subscription holds, or on a name that is not a valid channel, is counted in
 * [unsolicitedNotifications] and logged by the name's length, never its content.
 */
public class RealtimeListener internal constructor(
    private val connections: ListenerConnections,
    private val properties: RealtimeProperties,
    private val reconnectWait: ReconnectWait = ReconnectWait.UNTIL_STOPPED,
) : SmartLifecycle,
    AutoCloseable {
    init {
        val problems = properties.configurationProblems()
        require(problems.isEmpty()) { ConfigurationProblemsException.render(problems) }
    }

    private val backoff = ReconnectBackoff(properties.minBackoff, properties.maxBackoff)
    private val pollMillis = properties.pollInterval.toMillis().toInt()

    private val registry = ReentrantLock()
    private val liveChanged = registry.newCondition()

    /** Keyed by channel name, so a raw notification name is looked up without being parsed first. Guarded by [registry]. */
    private val channels = HashMap<String, ChannelEntry>()

    /** Channels whose wanted state changed since the listening thread last looked. Guarded by [registry]. */
    private val changes = ArrayDeque<Channel>()
    private var subscriptionCount = 0

    @Volatile
    private var live = false

    private val unsolicited = AtomicLong()
    private val probes = ConcurrentLinkedQueue<CompletableFuture<SessionProbe>>()
    private val lifecycle = Any()

    @Volatile
    private var run: Run? = null

    /** Starts the listening thread and waits for its first session; see the class documentation. */
    override fun start() {
        val current = Run()
        synchronized(lifecycle) {
            val previous = run
            check(previous == null || previous.exited.isDone) { "realtime: the listener is already running or still stopping" }
            run = current
        }
        Thread
            .ofPlatform()
            .name("rain-realtime-listener-${properties.poolName}")
            .daemon(true)
            .start { loop(current) }
        try {
            current.firstSession.get(properties.connectTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (failed: ExecutionException) {
            current.stop.countDown()
            throw ListenerStartRefused("realtime: the listener could not open its first session", failed.cause)
        } catch (timedOut: TimeoutException) {
            current.stop.countDown()
            throw ListenerStartRefused(
                "realtime: the listener did not open its first session within ${properties.connectTimeout.written()}",
                timedOut,
            )
        }
    }

    /** Asks the listening thread to stop; open subscriptions end with [SubscriptionEnd.CLOSED] as it leaves its session. */
    override fun stop() {
        run?.stop?.countDown()
    }

    /** As [stop], running [callback] once the listening thread has exited. */
    override fun stop(callback: Runnable) {
        val current = run
        if (current == null) {
            callback.run()
            return
        }
        current.stop.countDown()
        current.exited.whenComplete { _, _ -> callback.run() }
    }

    override fun isRunning(): Boolean {
        val current = run ?: return false
        return current.stop.count > 0L && !current.exited.isDone
    }

    override fun getPhase(): Int = PHASE

    /**
     * Stops, waits for the listening thread for at most `connect-timeout + poll-interval` (the longest
     * of its bounded waits), and closes the pool.
     */
    override fun close() {
        val current = run
        if (current != null) {
            current.stop.countDown()
            val bound = properties.connectTimeout.plus(properties.pollInterval)
            try {
                current.exited.get(bound.toNanos(), TimeUnit.NANOSECONDS)
            } catch (timedOut: TimeoutException) {
                log.warn("realtime: the listening thread did not exit within {}; closing its pool under it", bound.written(), timedOut)
            }
        }
        connections.close()
    }

    /** Whether a session is live, so that [subscribe] can succeed. */
    public fun isLive(): Boolean = live

    /** Notifications on a name no subscription held, or on a name that is not a valid channel, since this listener was built. */
    public fun unsolicitedNotifications(): Long = unsolicited.get()

    /** Open subscriptions; never more than `max-subscriptions`. */
    public fun subscriptionCount(): Int = registry.withLock { subscriptionCount }

    /**
     * Subscribes to [channel] and returns once the listening connection has issued its `LISTEN`, so an
     * event committed after this returns cannot be missed.
     *
     * Refused with a retryable [Fault]: `realtime_unavailable` when no session is live or the `LISTEN`
     * is not confirmed within [timeout], `realtime_subscription_limit` when `max-subscriptions` are open.
     */
    public fun subscribe(
        channel: Channel,
        timeout: Duration,
    ): Subscription {
        val subscription = Subscription(channel, properties.subscriberBuffer, SubscriptionOwner(::unsubscribe))
        registry.withLock {
            if (!live) throw refusal(RealtimeErrorCodes.UNAVAILABLE, channel)
            if (subscriptionCount >= properties.maxSubscriptions) throw refusal(RealtimeErrorCodes.SUBSCRIPTION_LIMIT, channel)
            val entry = channels.getOrPut(channel.name) { ChannelEntry(channel).also { changes.addLast(channel) } }
            entry.subscribers.add(subscription)
            subscriptionCount += 1
            if (entry.listening) subscription.confirmation.complete(true) else entry.awaiting.add(subscription)
        }
        val confirmed =
            try {
                subscription.confirmation.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (timedOut: TimeoutException) {
                false
            } catch (interrupted: InterruptedException) {
                remove(subscription, SubscriptionEnd.UNAVAILABLE)
                throw interrupted
            }
        if (confirmed) return subscription
        remove(subscription, SubscriptionEnd.UNAVAILABLE)
        throw refusal(RealtimeErrorCodes.UNAVAILABLE, channel)
    }

    /** Waits until a session is live; for tests that have to know a reconnect happened. */
    internal fun awaitLive(timeout: Duration): Boolean =
        registry.withLock {
            var remaining = timeout.toNanos()
            while (!live) {
                if (remaining <= 0L) return@withLock false
                remaining = liveChanged.awaitNanos(remaining)
            }
            true
        }

    /** What the live session's own backend reports, read on the listening thread after pending changes are applied. */
    internal fun probe(timeout: Duration): SessionProbe {
        val request = CompletableFuture<SessionProbe>()
        probes.add(request)
        return request.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    }

    private fun unsubscribe(subscription: Subscription) {
        remove(subscription, SubscriptionEnd.UNSUBSCRIBED)
    }

    private fun remove(
        subscription: Subscription,
        end: SubscriptionEnd,
    ) {
        registry.withLock {
            val name = subscription.channel.name
            val entry = channels[name]
            if (entry != null && entry.subscribers.remove(subscription)) {
                subscriptionCount -= 1
                entry.awaiting.remove(subscription)
                if (entry.subscribers.isEmpty()) {
                    channels.remove(name)
                    changes.addLast(subscription.channel)
                }
            }
            subscription.finish(end)
        }
    }

    private fun loop(current: Run) {
        try {
            var wait = backoff.min
            var connectedOnce = false
            while (current.stop.count > 0L) {
                val failure = session(current) ?: return
                if (failure.connected) {
                    connectedOnce = true
                    wait = backoff.min
                }
                if (!connectedOnce) {
                    current.firstSession.completeExceptionally(failure.cause)
                    return
                }
                log.warn(
                    "realtime: the listening session ended and its subscribers were told of the gap; retrying_in={}",
                    wait.written(),
                    failure.cause,
                )
                if (!reconnectWait.await(wait, current.stop)) return
                wait = backoff.next(wait)
            }
        } finally {
            endSession(SubscriptionEnd.CLOSED)
            current.firstSession.completeExceptionally(
                ListenerStartRefused("realtime: the listener stopped before its first session", null),
            )
            current.exited.complete(Unit)
        }
    }

    /** Runs one session; `null` when it ended because the listener is stopping. */
    private fun session(current: Run): SessionFailure? {
        val connection =
            try {
                connections.open()
            } catch (failure: Exception) {
                return SessionFailure(failure, connected = false)
            }
        var connected = false
        try {
            connection.autoCommit = true
            val notifications = connection.unwrap(PGConnection::class.java)
            connection.createStatement().use { statement ->
                statement.execute(UNLISTEN_ALL)
                NotifyRules.verify(statement)
            }
            val issued = HashSet<Channel>()
            goLive()
            connected = true
            current.firstSession.complete(Unit)
            while (current.stop.count > 0L) {
                reconcile(connection, issued)
                answerProbes(connection, issued)
                notifications.getNotifications(pollMillis).forEach(::dispatch)
            }
        } catch (failure: Exception) {
            // Before the connection goes anywhere and before any reconnect: subscribers learn of the hole first.
            endSession(SubscriptionEnd.GAP)
            try {
                connections.evict(connection)
            } catch (evictFailure: Exception) {
                failure.addSuppressed(evictFailure)
            }
            return SessionFailure(failure, connected)
        }
        endSession(SubscriptionEnd.CLOSED)
        try {
            connections.release(connection)
        } catch (releaseFailure: Exception) {
            log.warn("realtime: returning the listening connection to its pool failed", releaseFailure)
        }
        return null
    }

    /**
     * Applies the changes recorded before this call, one channel each, against what this session has
     * issued. A change is a hint to look at one channel, so a channel that came and went twice is still
     * settled by what the registry holds when it is looked at.
     */
    private fun reconcile(
        connection: Connection,
        issued: MutableSet<Channel>,
    ) {
        val pending = registry.withLock { changes.size }
        repeat(pending) {
            val channel = registry.withLock { changes.removeFirstOrNull() } ?: return
            val wanted = registry.withLock { channels.containsKey(channel.name) }
            if (wanted && issued.add(channel)) execute(connection, "LISTEN " + channel.quoted())
            if (!wanted && issued.remove(channel)) execute(connection, "UNLISTEN " + channel.quoted())
            if (wanted) registry.withLock { channels[channel.name]?.let(::confirm) }
        }
    }

    private fun confirm(entry: ChannelEntry) {
        entry.listening = true
        entry.awaiting.forEach { it.confirmation.complete(true) }
        entry.awaiting.clear()
    }

    private fun answerProbes(
        connection: Connection,
        issued: MutableSet<Channel>,
    ) {
        while (true) {
            val request = probes.poll() ?: return
            try {
                reconcile(connection, issued)
                request.complete(readProbe(connection))
            } catch (failure: Exception) {
                request.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private fun readProbe(connection: Connection): SessionProbe =
        connection.createStatement().use { statement ->
            val pid =
                statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                    check(rows.next()) { "realtime: pg_backend_pid() answered no row" }
                    rows.getInt(1)
                }
            val listening =
                statement.executeQuery("SELECT pg_listening_channels()").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            SessionProbe(pid, listening)
        }

    private fun dispatch(notification: PGNotification) {
        val name: String = notification.name
        val payload: String = notification.parameter
        val held =
            registry.withLock {
                val entry = channels[name] ?: return@withLock false
                val event = RealtimeEvent(entry.channel, payload)
                val readers = entry.subscribers.iterator()
                while (readers.hasNext()) {
                    val reader = readers.next()
                    val delivery = reader.deliver(event)
                    if (delivery == Delivery.QUEUED) continue
                    readers.remove()
                    subscriptionCount -= 1
                    entry.awaiting.remove(reader)
                    if (delivery == Delivery.FULL) {
                        reader.finish(SubscriptionEnd.OVERFLOW)
                        log.warn(
                            "realtime: a subscriber fell {} events behind and its stream ended with overflow channel={}",
                            properties.subscriberBuffer,
                            entry.channel,
                        )
                    }
                }
                if (entry.subscribers.isEmpty()) {
                    channels.remove(name)
                    changes.addLast(entry.channel)
                }
                true
            }
        if (!held) countUnsolicited(name)
    }

    private fun countUnsolicited(name: String) {
        val total = unsolicited.incrementAndGet()
        val reason =
            when (Channel.parse(name)) {
                is ChannelParse.Valid -> "no subscription holds the channel"
                is ChannelParse.Invalid -> "the name is not a valid channel"
            }
        log.warn(
            "realtime: counted an unsolicited notification; reason=\"{}\" name_bytes={} unsolicited_total={}",
            reason,
            name.toByteArray(Charsets.UTF_8).size,
            total,
        )
    }

    private fun goLive() {
        registry.withLock {
            check(
                channels.isEmpty() && subscriptionCount == 0,
            ) { "realtime: a session went live with subscriptions left from the one before" }
            live = true
            liveChanged.signalAll()
        }
    }

    private fun endSession(end: SubscriptionEnd) {
        registry.withLock {
            if (!live) return
            live = false
            channels.values.forEach { entry -> entry.subscribers.forEach { it.finish(end) } }
            channels.clear()
            changes.clear()
            subscriptionCount = 0
            liveChanged.signalAll()
        }
    }

    private fun execute(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun refusal(
        code: ErrorCode,
        channel: Channel,
    ): Fault = Fault(FaultKind.RETRYABLE, code, op = "realtime.subscribe", entity = channel.name)

    private class Run {
        val stop = CountDownLatch(1)
        val firstSession = CompletableFuture<Unit>()
        val exited = CompletableFuture<Unit>()
    }

    private class ChannelEntry(
        val channel: Channel,
    ) {
        val subscribers = LinkedHashSet<Subscription>()
        val awaiting = LinkedHashSet<Subscription>()
        var listening = false
    }

    private class SessionFailure(
        val cause: Exception,
        val connected: Boolean,
    )

    public companion object {
        /** The default phase: started after the web server, stopped before its graceful shutdown waits for open streams. */
        public const val PHASE: Int = SmartLifecycle.DEFAULT_PHASE

        internal const val UNLISTEN_ALL: String = "UNLISTEN *"

        private val log = LoggerFactory.getLogger(RealtimeListener::class.java)
    }
}

/** The listener's first session did not go live, so the process does not start. */
public class ListenerStartRefused(
    message: String,
    cause: Throwable?,
) : IllegalStateException(message, cause)

internal data class SessionProbe(
    val backendPid: Int,
    val listening: Set<String>,
)
