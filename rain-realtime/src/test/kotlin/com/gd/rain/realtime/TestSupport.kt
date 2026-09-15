package com.gd.rain.realtime

import com.gd.rain.test.RainDatabase
import io.mockk.every
import io.mockk.mockk
import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Every bounded wait in these tests; reaching it fails the test. */
internal val BOUND: Duration = Duration.ofSeconds(10)

internal fun testProperties(
    subscriberBuffer: Int = 4,
    maxSubscriptions: Int = 16,
    minBackoff: Duration = Duration.ofMillis(20),
    maxBackoff: Duration = Duration.ofMillis(50),
    pollInterval: Duration = Duration.ofMillis(5),
): RealtimeProperties =
    RealtimeProperties(
        poolName = "test-realtime",
        subscriberBuffer = subscriberBuffer,
        maxSubscriptions = maxSubscriptions,
        minBackoff = minBackoff,
        maxBackoff = maxBackoff,
        pollInterval = pollInterval,
        connectTimeout = BOUND,
    )

/** The dedicated pool, pointed at a test database exactly as the auto-configuration points it at `spring.datasource`. */
internal fun listenerConnections(
    database: RainDatabase,
    properties: RealtimeProperties,
): HikariListenerConnections =
    HikariListenerConnections.of(
        properties,
        DataSourceProperties().apply {
            url = database.url
            username = database.username
            password = database.password
        },
    )

/** Records every reconnect wait without waiting; from the [holdAfter]th on it holds the listener until it is stopped. */
internal class RecordingWait(
    private val holdAfter: Int,
) : ReconnectWait {
    val durations: MutableList<Duration> = CopyOnWriteArrayList()
    private val recorded = Semaphore(0)

    override fun await(
        duration: Duration,
        stop: CountDownLatch,
    ): Boolean {
        durations += duration
        recorded.release()
        if (durations.size >= holdAfter) {
            stop.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)
            return false
        }
        return stop.count > 0L
    }

    fun awaitRecorded(count: Int) {
        check(recorded.tryAcquire(count, BOUND.toNanos(), TimeUnit.NANOSECONDS)) { "only ${durations.size} reconnect waits were recorded" }
    }
}

/** Holds every reconnect wait until the test lets it go, so "while it reconnects" is a state the test controls. */
internal class HeldReconnect : ReconnectWait {
    private val entered = Semaphore(0)
    private val proceed = Semaphore(0)

    override fun await(
        duration: Duration,
        stop: CountDownLatch,
    ): Boolean {
        entered.release()
        return proceed.tryAcquire(BOUND.toNanos(), TimeUnit.NANOSECONDS) && stop.count > 0L
    }

    fun awaitEntered() {
        check(entered.tryAcquire(BOUND.toNanos(), TimeUnit.NANOSECONDS)) { "the listener never began waiting to reconnect" }
    }

    fun proceed() {
        proceed.release()
    }
}

internal sealed interface Opening {
    data class Refused(
        val failure: SQLException,
    ) : Opening

    data class Served(
        val session: FakeSession,
    ) : Opening
}

/** Hands out connections in the order a test scripted them and records how each came back. */
internal class ScriptedConnections(
    openings: List<Opening>,
) : ListenerConnections {
    private val script = ConcurrentLinkedQueue(openings)
    val opened = AtomicInteger()
    val released: MutableList<Connection> = CopyOnWriteArrayList()
    val evicted: MutableList<Connection> = CopyOnWriteArrayList()

    @Volatile
    var closed: Boolean = false

    override fun open(): Connection {
        opened.incrementAndGet()
        return when (val next = script.poll() ?: throw SQLException("the test scripted no further connection", "08001")) {
            is Opening.Refused -> throw next.failure
            is Opening.Served -> next.session.connection
        }
    }

    override fun release(connection: Connection) {
        released += connection
    }

    override fun evict(connection: Connection) {
        evicted += connection
    }

    override fun close() {
        closed = true
    }
}

internal class Notice(
    private val channelName: String,
    private val payload: String,
) : PGNotification {
    override fun getName(): String = channelName

    override fun getPID(): Int = 0

    override fun getParameter(): String = payload
}

/**
 * One listening connection with no database behind it: statements are recorded, the server settings
 * answer [blockSize], notifications are whatever the test sends, and [drop] makes the next wait for
 * notifications fail as a terminated backend does.
 */
internal class FakeSession(
    blockSize: Int = NotifyRules.BLOCK_SIZE,
) {
    val executed: MutableList<String> = CopyOnWriteArrayList()
    val connection: Connection = mockk(relaxed = true)

    private val notifications: PGConnection = mockk()
    private val statement: Statement = mockk(relaxed = true)
    private val settings: ResultSet = mockk(relaxed = true)
    private val batches = LinkedBlockingQueue<Batch>()
    private val dropped = AtomicReference<SQLException?>()
    private val listenGate = AtomicReference<CountDownLatch?>()

    @Volatile
    private var handedOut: Batch? = null

    init {
        every { settings.next() } returns true
        every { settings.getString(1) } returns NotifyRules.MAX_CHANNEL_NAME_BYTES.toString()
        every { settings.getString(2) } returns blockSize.toString()
        every { statement.execute(any<String>()) } answers {
            val sql = firstArg<String>()
            if (sql.startsWith("LISTEN ")) listenGate.getAndSet(null)?.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)
            executed += sql
            false
        }
        every { statement.executeQuery(any<String>()) } answers {
            executed += firstArg<String>()
            settings
        }
        every { connection.createStatement() } returns statement
        every { connection.unwrap(PGConnection::class.java) } returns notifications
        every { notifications.getNotifications(any<Int>()) } answers { poll(firstArg()) }
    }

    /** Sends [notices] as one batch and returns once the listener has dispatched all of it. */
    fun send(vararg notices: Pair<String, String>) {
        val batch = Batch(notices.map { (name, payload) -> Notice(name, payload) }.toTypedArray())
        batches.put(batch)
        check(batch.dispatched.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)) { "the listener did not dispatch the batch" }
    }

    fun drop() {
        dropped.set(SQLException("terminating connection due to administrator command", "57P01"))
    }

    /** The next `LISTEN` waits until the returned latch is opened. */
    fun holdNextListen(): CountDownLatch = CountDownLatch(1).also(listenGate::set)

    fun subscriptionStatements(): List<String> = executed.filter { it.startsWith("LISTEN ") || it.startsWith("UNLISTEN \"") }

    private fun poll(timeoutMillis: Int): Array<PGNotification> {
        // Back at the wait means the batch handed out last time has been dispatched.
        handedOut?.dispatched?.countDown()
        handedOut = null
        dropped.getAndSet(null)?.let { throw it }
        val batch = batches.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) ?: return emptyArray()
        handedOut = batch
        return batch.notices
    }

    private class Batch(
        val notices: Array<PGNotification>,
    ) {
        val dispatched = CountDownLatch(1)
    }
}

/**
 * Real connections from [delegate], whose next wait for notifications fails on demand while the
 * connection itself stays alive — the case where returning it to the pool would hand its `LISTEN`s to
 * the next session.
 */
internal class FailingConnections(
    private val delegate: ListenerConnections,
) : ListenerConnections {
    private val armed = AtomicBoolean()
    private val originals: MutableMap<Connection, Connection> = Collections.synchronizedMap(IdentityHashMap())

    fun failNextPoll() {
        armed.set(true)
    }

    override fun open(): Connection {
        val original = delegate.open()
        val notifications = original.unwrap(PGConnection::class.java)
        val failing =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PGConnection::class.java)) { _, method, args ->
                if (method.name == "getNotifications" && armed.compareAndSet(true, false)) {
                    throw SQLException("injected failure on a live connection", "XX000")
                }
                forward(method, notifications, args)
            } as PGConnection
        val wrapped =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "unwrap" && args?.firstOrNull() == PGConnection::class.java) failing else forward(method, original, args)
            } as Connection
        originals[wrapped] = original
        return wrapped
    }

    override fun release(connection: Connection) {
        delegate.release(originalOf(connection))
    }

    override fun evict(connection: Connection) {
        delegate.evict(originalOf(connection))
    }

    override fun close() {
        delegate.close()
    }

    private fun originalOf(connection: Connection): Connection =
        checkNotNull(originals.remove(connection)) {
            "not a connection this test handed out"
        }

    private fun forward(
        method: Method,
        target: Any,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
