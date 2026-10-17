package me.kzheart.klib.remote

import me.kzheart.klib.scope.Disposable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock

/** 将事件非阻塞地写入有界磁盘队列；后台优先投递 incident，并保留失败重试状态。 */
class RemoteDelivery private constructor(builder: Builder) : Consumer<RemoteEvent?>, Disposable {
    private val client = builder.client
    private val maxPendingEvents = builder.pendingLimit
    private val batchSize = builder.batchLimit
    private val maxBatchBytes = builder.batchByteLimit
    private val maxEventBytes = builder.eventByteLimit
    private val initialBackoffMillis = builder.initialBackoffMillis
    private val maxBackoffMillis = builder.maxBackoffMillis
    private val settingsRefreshMillis = builder.settingsRefreshMillis
    private val pendingLock = ReentrantLock()
    private val pendingIncidents = ArrayDeque<Pending>()
    private val pendingLogs = ArrayDeque<Pending>()
    private val wake = Semaphore(0)
    private val droppedEvents = AtomicLong()
    private val disk = try {
        RemoteDiskQueue(builder.directory, builder.diskByteLimit, builder.diskEventLimit,
            maxEventBytes, builder.ttlMillis, client.queueIdentity())
    } catch (_: Throwable) { null }
    @Volatile private var closed = disk == null
    private val worker = if (disk == null) null else Thread({ run() }, "klib-remote-delivery").apply {
        isDaemon = true
        start()
    }

    override fun accept(event: RemoteEvent?) { submit(event) }

    /** 完成阶段仅表示原子落盘；容量淘汰、关闭或持久化失败均返回 false。 */
    fun submit(event: RemoteEvent?): CompletionStage<Boolean> {
        val persisted = CompletableFuture<Boolean>()
        try {
            if (closed || event == null) {
                reject(persisted)
                return persisted
            }
            val values = event.toMap()
            val eventId = Texts.requireText(values["event_id"]?.toString(), "event_id")
            val encoded = DiagnosticJson.write(values, maxEventBytes).toByteArray(StandardCharsets.UTF_8)
            val pending = Pending(values["type"]?.toString() == "incident", eventId,
                System.currentTimeMillis(), encoded, persisted)
            if (offer(pending)) wake.release() else reject(persisted)
        } catch (_: Throwable) { reject(persisted) }
        return persisted
    }

    fun queuedEvents(): Long {
        val pending = pendingSize()
        return try { pending + (disk?.size()?.toLong() ?: 0L) } catch (_: Throwable) { pending }
    }

    fun droppedEvents(): Long = droppedEvents.get()
    fun isActive(): Boolean = !closed
    @get:JvmSynthetic
    @get:JvmName("activeProperty")
    val isActive: Boolean get() = isActive()

    override fun dispose() {
        closed = true
        wake.release()
        worker?.interrupt()
        failPending()
    }

    private fun reject(persisted: CompletableFuture<Boolean>) {
        persisted.complete(false)
        droppedEvents.incrementAndGet()
    }

    private fun offer(value: Pending): Boolean {
        if (!pendingLock.tryLock()) return false
        try {
            if (closed) return false
            if (pendingIncidents.size + pendingLogs.size >= maxPendingEvents) {
                val evicted = pendingLogs.pollFirst()
                    ?: (if (value.incident) pendingIncidents.pollFirst() else null)
                    ?: return false
                reject(evicted.persisted)
            }
            (if (value.incident) pendingIncidents else pendingLogs).addLast(value)
            return true
        } finally { pendingLock.unlock() }
    }

    /** 仅工作线程拥有重试时钟，邮箱锁不覆盖磁盘或网络操作。 */
    private fun run() {
        val queue = disk ?: return
        val retry = RetryState()
        var currentEnvelope: RemoteBatchEnvelope? = null
        try {
            while (!closed) {
                try {
                    val envelope = currentEnvelope ?: client.snapshotEnvelope().also { currentEnvelope = it }
                    persistPending(queue, envelope)
                    droppedEvents.addAndGet(queue.purgeExpired(System.currentTimeMillis()).toLong())
                    val now = System.currentTimeMillis()
                    if (now >= retry.nextSettingsAt && !refreshSettings(retry, now)) continue
                    val settings = client.settings()
                    if (settings == null || !settings.acceptingEvents() || settings.policy().paused()) {
                        waitFor(minOf(1000L, maxOf(1L, retry.nextSettingsAt - now)))
                        continue
                    }
                    if (now < retry.nextAttemptAt) {
                        waitFor(minOf(1000L, retry.nextAttemptAt - now))
                        continue
                    }
                    val limits = settings.limits()
                    droppedEvents.addAndGet(queue.discardOversized(minOf(maxEventBytes, limits.maxEventBytes())).toLong())
                    val candidate = queue.batch(minOf(batchSize, limits.maxBatchEvents()),
                        minOf(maxBatchBytes, safeInt(limits.maxDecompressedBytes())))
                    if (candidate.isEmpty()) { waitFor(1000L); continue }
                    val batch = fitBatch(queue, candidate, limits)
                    if (batch.isEmpty()) continue
                    if (deliver(queue, batch)) {
                        retry.failures = 0
                        retry.nextAttemptAt = 0L
                    } else {
                        retry.nextAttemptAt = safeAdd(now, retryDelay(++retry.failures, -1L))
                    }
                } catch (failure: RemoteHttpException) {
                    when (failure.status()) {
                        401 -> closed = true
                        403 -> {
                            retry.failures++
                            retry.nextAttemptAt = safeAdd(System.currentTimeMillis(),
                                maxOf(PAUSED_RETRY_MILLIS, failure.retryAfterMillis()))
                        }
                        else -> retry.nextAttemptAt = safeAdd(System.currentTimeMillis(),
                            retryDelay(++retry.failures, failure.retryAfterMillis()))
                    }
                } catch (_: Throwable) {
                    retry.nextAttemptAt = safeAdd(System.currentTimeMillis(), retryDelay(++retry.failures, -1L))
                }
            }
        } finally {
            failPending()
            queue.close()
        }
    }

    private fun refreshSettings(retry: RetryState, now: Long): Boolean {
        try {
            val previous = client.settings()
            val wasAccepting = previous != null && previous.acceptingEvents() && !previous.policy().paused()
            client.refreshPolicy()
            retry.failures = 0
            retry.nextSettingsAt = safeAdd(now, settingsRefreshMillis)
            val refreshed = client.settings()
            if (!wasAccepting && refreshed != null && refreshed.acceptingEvents() && !refreshed.policy().paused()) {
                retry.nextAttemptAt = 0L
            }
            return true
        } catch (failure: RemoteHttpException) {
            if (failure.status() == 401) { closed = true; return false }
            retry.nextSettingsAt = safeAdd(now, retryDelay(++retry.failures, failure.retryAfterMillis()))
        } catch (_: IOException) {
            retry.nextSettingsAt = safeAdd(now, retryDelay(++retry.failures, -1L))
        }
        waitFor(minOf(1000L, maxOf(1L, retry.nextSettingsAt - now)))
        return false
    }

    private fun persistPending(queue: RemoteDiskQueue, envelope: RemoteBatchEnvelope) {
        while (true) {
            val value = pollPending() ?: return
            val settings = client.settings()
            val limit = if (settings == null) maxEventBytes else minOf(maxEventBytes, settings.limits().maxEventBytes())
            if (value.data.size > limit) { reject(value.persisted); continue }
            val result = queue.store(value.incident, value.createdAtMillis, value.eventId, envelope, value.data)
            if (result.evicted() > 0) droppedEvents.addAndGet(result.evicted().toLong())
            if (!result.stored()) droppedEvents.incrementAndGet()
            value.persisted.complete(result.stored())
        }
    }

    private fun fitBatch(queue: RemoteDiskQueue, candidate: List<RemoteDiskQueue.Entry>,
        limits: RemoteSettings.Limits): List<RemoteDiskQueue.Entry> {
        val fitted = candidate.toMutableList()
        while (fitted.isNotEmpty()) {
            if (client.batchFits(fitted[0].envelope(), fitted.map { it.data() }, limits)) return fitted
            if (fitted.size > 1) { fitted.removeAt(fitted.lastIndex); continue }
            if (queue.remove(fitted[0])) droppedEvents.incrementAndGet()
            fitted.clear()
        }
        return fitted
    }

    private fun deliver(queue: RemoteDiskQueue, batch: List<RemoteDiskQueue.Entry>): Boolean {
        val receipt = client.sendEncodedBatch(batch[0].envelope(), batch.map { it.data() }, batch.map { it.eventId() })
        var removedAll = true
        for (result in receipt.results()) if (!queue.remove(batch[result.index()])) removedAll = false
        return removedAll
    }

    private fun pollPending(): Pending? = pendingLock.withLock { pendingIncidents.pollFirst() ?: pendingLogs.pollFirst() }

    private fun pendingSize(): Long {
        if (!pendingLock.tryLock()) return 0L
        try { return (pendingIncidents.size + pendingLogs.size).toLong() } finally { pendingLock.unlock() }
    }

    private fun failPending() = pendingLock.withLock {
        for (queue in listOf(pendingIncidents, pendingLogs)) {
            while (true) reject((queue.pollFirst() ?: break).persisted)
        }
    }

    private fun waitFor(millis: Long) {
        try { wake.tryAcquire(maxOf(1L, millis), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun retryDelay(failures: Int, retryAfterMillis: Long): Long {
        val shift = minOf(30, maxOf(0, failures - 1))
        val base = if (initialBackoffMillis > (maxBackoffMillis shr shift)) maxBackoffMillis
            else minOf(maxBackoffMillis, initialBackoffMillis shl shift)
        val lower = base / 2L
        val jitter = if (base <= 1L) base else lower + ThreadLocalRandom.current().nextLong(base - lower + 1L)
        return if (retryAfterMillis < 0L) jitter else maxOf(jitter, retryAfterMillis)
    }

    private class RetryState(var nextAttemptAt: Long = 0L, var nextSettingsAt: Long = 0L, var failures: Int = 0)
    private class Pending(val incident: Boolean, val eventId: String, val createdAtMillis: Long,
        val data: ByteArray, val persisted: CompletableFuture<Boolean>)

    /** 有界队列、批次预算与重试配置。 */
    class Builder internal constructor(internal val client: RemoteClient, internal val directory: Path) {
        internal var diskByteLimit = DEFAULT_MAX_DISK_BYTES
        internal var ttlMillis = DEFAULT_TTL.toMillis()
        internal var pendingLimit = 256
        internal var diskEventLimit = 4096
        internal var batchLimit = 100
        internal var eventByteLimit = 64 * 1024
        internal var batchByteLimit = 1024 * 1024
        internal var initialBackoffMillis = 1000L
        internal var maxBackoffMillis = 60_000L
        internal var settingsRefreshMillis = 60_000L

        fun maxDiskBytes(value: Long): Builder = apply {
            require(value >= 1024L) { "maxDiskBytes is too small" }; diskByteLimit = value
        }
        fun ttl(value: Duration): Builder = apply { ttlMillis = positiveMillis(value, "ttl") }
        fun maxPendingEvents(value: Int): Builder = apply { pendingLimit = positive(value, "maxPendingEvents") }
        fun maxDiskEvents(value: Int): Builder = apply { diskEventLimit = positive(value, "maxDiskEvents") }
        fun batchSize(value: Int): Builder = apply { batchLimit = positive(value, "batchSize") }
        fun maxEventBytes(value: Int): Builder = apply { eventByteLimit = positive(value, "maxEventBytes") }
        fun maxBatchBytes(value: Int): Builder = apply { batchByteLimit = positive(value, "maxBatchBytes") }
        fun initialBackoff(value: Duration): Builder = apply { initialBackoffMillis = positiveMillis(value, "initialBackoff") }
        fun maxBackoff(value: Duration): Builder = apply { maxBackoffMillis = positiveMillis(value, "maxBackoff") }
        fun settingsRefreshInterval(value: Duration): Builder = apply {
            settingsRefreshMillis = positiveMillis(value, "settingsRefreshInterval")
        }
        fun build(): RemoteDelivery {
            check(eventByteLimit <= batchByteLimit) { "maxEventBytes must not exceed maxBatchBytes" }
            check(initialBackoffMillis <= maxBackoffMillis) { "initialBackoff must not exceed maxBackoff" }
            return RemoteDelivery(this)
        }

        private fun positive(value: Int, name: String): Int {
            require(value > 0) { "$name must be positive" }; return value
        }
        private fun positiveMillis(value: Duration, name: String): Long {
            val millis = try { value.toMillis() } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("$name is too large", failure)
            }
            require(millis > 0L) { "$name must be positive" }
            return millis
        }
    }

    companion object {
        const val DEFAULT_MAX_DISK_BYTES: Long = 16L * 1024L * 1024L
        @JvmField val DEFAULT_TTL: Duration = Duration.ofHours(24L)
        private val PAUSED_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5L)
        @JvmStatic fun builder(client: RemoteClient, queueDirectory: Path): Builder = Builder(client, queueDirectory)
        private fun safeAdd(value: Long, addition: Long): Long =
            if (addition > Long.MAX_VALUE - value) Long.MAX_VALUE else value + addition
        private fun safeInt(value: Long): Int = if (value > Int.MAX_VALUE) Int.MAX_VALUE else maxOf(1L, value).toInt()
    }
}
