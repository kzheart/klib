package me.kzheart.klib.data.postgresql

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import me.kzheart.klib.data.StorageException
import me.kzheart.klib.data.jdbc.JdbcDataSources
import me.kzheart.klib.scope.Disposable
import org.postgresql.Driver
import org.postgresql.PGConnection

class PostgreSqlNotification internal constructor(private val channel: String, private val payload: String, private val processId: Int) {
    fun channel(): String = channel
    fun payload(): String = payload
    fun processId(): Int = processId
}

class PostgreSqlNotifications private constructor() {
    companion object {
        @JvmStatic fun listen(jdbcUrl: String, properties: Properties, channels: Set<String>, options: PostgreSqlListenOptions,
                              listener: PostgreSqlNotificationListener): PostgreSqlListenSession {
            require(jdbcUrl.startsWith("jdbc:postgresql:")) { "LISTEN requires a PostgreSQL JDBC URL" }
            require(channels.isNotEmpty() && channels.all { it.isNotBlank() && '\u0000' !in it }) { "channels must contain non-blank identifiers without NUL" }
            val copy = Properties().apply { properties.stringPropertyNames().forEach { setProperty(it, properties.getProperty(it)) } }
            // URL 属性优先于 Properties；末尾覆盖保证会话选项的超时不会被旧 URL 参数禁用。
            val seconds = (options.connectTimeout.toMillis() + 999L) / 1000L
            val url = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "connectTimeout=$seconds&loginTimeout=$seconds&socketTimeout=$seconds"
            val source = JdbcDataSources.driver(Driver::class.java.name, Driver::class.java.classLoader, url, copy)
            return PostgreSqlListenSession({ source.connection }, LinkedHashSet(channels), options, listener).also { it.start() }
        }
    }
}

/** 每次独占一个物理连接；接收与串行分发分离，队列溢出明确终结而不静默丢弃。 */
class PostgreSqlListenSession internal constructor(private val connect: () -> Connection, private val channels: Set<String>,
                                                  private val options: PostgreSqlListenOptions,
                                                  private val listener: PostgreSqlNotificationListener) : Disposable, AutoCloseable {
    private val lock = Any()
    private val queue = ArrayBlockingQueue<Event>(options.queueCapacity)
    private val subscribed = CompletableFuture<Void>()
    private val terminated = CompletableFuture<Void>()
    @Volatile private var stopping = false
    @Volatile private var receiverDone = false
    @Volatile private var connection: Connection? = null
    private var failure: Throwable? = null
    private var reaping = false
    private val id = IDS.incrementAndGet()
    private val receiver = Thread({ receive() }, "klib-pg-listen-$id-receive").apply { isDaemon = true }
    private val dispatcher = Thread({ dispatch() }, "klib-pg-listen-$id-dispatch").apply { isDaemon = true }
    internal fun start() { dispatcher.start(); receiver.start() }
    fun ready(): CompletionStage<Void> = subscribed.thenApply { null }
    fun completion(): CompletionStage<Void> = closeView()
    fun closeAsync(): CompletionStage<Void> { stop(null); return closeView() }
    override fun close() = dispose()
    override fun dispose() {
        val completion = closeAsync()
        check(!isSessionThread()) { "Do not wait for LISTEN close inside its callback; use closeAsync and return" }
        try { completion.toCompletableFuture().get(options.closeTimeout.toMillis(), TimeUnit.MILLISECONDS) }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StorageException("Interrupted while draining LISTEN; resources may still be active", interrupted)
        } catch (error: Exception) { throw StorageException("LISTEN close did not complete successfully; inspect completion", error) }
    }

    private fun receive() {
        var retries = 0
        var generation = 0L
        try {
            while (!stopping) {
                try {
                    val connection = connect()
                    this.connection = connection
                    try {
                        if (stopping) break
                        connection.autoCommit = true
                        connection.createStatement().use { statement ->
                            statement.queryTimeout = ((options.connectTimeout.toMillis() + 999L) / 1000L).toInt()
                            val limit = statement.executeQuery("SHOW max_identifier_length").use { it.next(); it.getInt(1) }
                            require(channels.all { it.toByteArray(StandardCharsets.UTF_8).size <= limit }) { "LISTEN channel exceeds server identifier length" }
                            for (channel in channels) statement.execute("LISTEN \"${channel.replace("\"", "\"\"")}\"")
                        }
                        if (stopping) break
                        generation++
                        if (!offer(Event.Listening(generation))) break
                        subscribed.complete(null)
                        val pg = connection.unwrap(PGConnection::class.java)
                        while (!stopping) {
                            val notifications = pg.getNotifications(options.pollTimeout.toMillis().toInt())
                            if (stopping) break
                            retries = 0 // 成功轮询后才重置连续失败，避免仅握手成功就无限重试。
                            if (notifications != null) for (notification in notifications) {
                                if (!offer(Event.Notification(PostgreSqlNotification(notification.name, notification.parameter, notification.pid)))) break
                            }
                        }
                    } finally {
                        try { connection.close() } catch (cleanup: Throwable) { stop(cleanup) }
                        this.connection = null
                    }
                } catch (error: Throwable) {
                    if (stopping) break
                    if (error !is SQLException || terminal(error) || retries >= options.reconnectAttempts) {
                        stop(error); break
                    }
                    if (!offer(Event.Failure(error))) break
                    val multiplier = 1L shl minOf(retries, 30)
                    val initial = options.initialBackoff.toMillis()
                    val maximum = options.maxBackoff.toMillis()
                    val delay = if (initial > maximum / multiplier) maximum else initial * multiplier
                    retries++
                    try { Thread.sleep(delay) }
                    catch (interrupted: InterruptedException) { if (!stopping) stop(interrupted) }
                }
            }
        } finally {
            receiverDone = true
            stop(null)
        }
    }

    private fun dispatch() {
        try {
            while (!receiverDone || queue.isNotEmpty()) {
                val event = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                try {
                    when (event) {
                        is Event.Listening -> listener.onListening(event.generation)
                        is Event.Notification -> listener.onNotification(event.value)
                        is Event.Failure -> listener.onFailure(event.error, false)
                    }
                } catch (error: Throwable) { stop(error) }
            }
            val terminal = synchronized(lock) { failure }
            if (terminal != null) try { listener.onFailure(terminal, true) }
            catch (error: Throwable) { synchronized(lock) { if (error !== terminal) terminal.addSuppressed(error) } }
        } catch (error: Throwable) { stop(error) }
    }

    /** 与 close 的接收截止线共用一把锁，截止后不再入队；已入队仍按序排空。 */
    private fun offer(event: Event): Boolean = synchronized(lock) {
        if (stopping) false
        else if (queue.offer(event)) true
        else { stop(RejectedExecutionException("LISTEN notification queue is full")); false }
    }
    private fun stop(error: Throwable?) {
        synchronized(lock) {
            if (error != null) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
            stopping = true
            if (!reaping) {
                reaping = true
                Thread({
                    // pgJDBC 允许另一线程 close；不让 closeAsync 的调用线程做阻塞 JDBC。
                    try { connection?.close() } catch (cleanup: Throwable) { stop(cleanup) }
                    receiver.join(); dispatcher.join()
                    val cause = synchronized(lock) { failure }
                    subscribed.completeExceptionally(cause ?: CancellationException("LISTEN closed before subscribing"))
                    if (cause == null) terminated.complete(null) else terminated.completeExceptionally(cause)
                }, "klib-pg-listen-$id-close").apply { isDaemon = true }.start()
            }
        }
        if (Thread.currentThread() !== receiver) receiver.interrupt()
    }
    private fun isSessionThread(): Boolean = Thread.currentThread() === dispatcher || Thread.currentThread() === receiver
    private fun closeView(): CompletableFuture<Void> {
        val view = object : CompletableFuture<Void>() {
            private fun checkWait() { check(isDone || !isSessionThread()) { "A LISTEN callback cannot wait for its own close" } }
            override fun join(): Void? { checkWait(); return super.join() }
            override fun get(): Void? { checkWait(); return super.get() }
            override fun get(timeout: Long, unit: TimeUnit): Void? { checkWait(); return super.get(timeout, unit) }
        }
        terminated.whenComplete { _, error -> if (error == null) view.complete(null) else view.completeExceptionally(error) }
        return view
    }
    private fun terminal(error: SQLException): Boolean = error.sqlState?.let { it.startsWith("28") || it.startsWith("3D") || it.startsWith("42") || it.startsWith("0A") } == true
    private sealed class Event {
        class Listening(val generation: Long) : Event()
        class Notification(val value: PostgreSqlNotification) : Event()
        class Failure(val error: Throwable) : Event()
    }
    companion object { private val IDS = AtomicLong() }
}
