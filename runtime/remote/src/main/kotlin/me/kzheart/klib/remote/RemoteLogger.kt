package me.kzheart.klib.remote

import me.kzheart.klib.scope.Disposable
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** 插件专属日志与 Incident 入口；有界事实窗口和 contributor 执行隔离。 */
class RemoteLogger private constructor(builder: Builder) : Disposable {
    private val name = builder.name
    private val sink = builder.sink
    private val policy = builder.policySource
    private val budget = builder.incidentBudget
    private val contributors = builder.contributors.toList()
    private val logWindow = BoundedFacts<RemoteEvent>(budget.logEntries(), budget.logBytes())
    private val breadcrumbs = BoundedFacts<Breadcrumb>(budget.breadcrumbs(), budget.breadcrumbBytes())
    private val droppedEvents = AtomicLong()
    @Volatile private var closed = false
    private val contributorExecutor: ThreadPoolExecutor = run {
        val workers = maxOf(1, minOf(4, budget.maxContributors()))
        ThreadPoolExecutor(workers, workers, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue(maxOf(1, budget.maxContributors())),
            { task -> Thread(task, "klib-remote-contributor").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }

    fun info(message: String) = log(Level.INFO, message, emptyMap<String, Any>())
    fun warn(message: String) = log(Level.WARNING, message, emptyMap<String, Any>())
    fun error(message: String) = log(Level.SEVERE, message, emptyMap<String, Any>())
    fun log(level: Level, message: String, context: Map<String, *>) =
        log(level, message, RemoteLogContext.builder().context(context).build())

    fun log(level: Level, message: String, logContext: RemoteLogContext) {
        val fields = eventFields("log", message)
        fields["level"] = wireLevel(level)
        fields["logger"] = name
        fields["attributes"] = logContext.context()
        fields["tags"] = logContext.tags().associateWith { "true" }
        val payload = linkedMapOf<String, Any>("context" to logContext.context(), "mdc" to logContext.mdc(), "tags" to logContext.tags())
        RemoteOperation.current()?.let {
            fields["operation_id"] = it.id()
            payload["operation"] = it.toMap()
        }
        fields["payload"] = payload
        val event = RemoteEvent(fields)
        logWindow.add(event, DiagnosticJson.write(event.toMap()))
        val current = safePolicy()
        if (!closed && current.accepts(level) && RemoteSampling.accepts(fields["event_id"].toString(), current.sampleRate())) emit(event)
    }

    /** 仅桥接所指定 Logger 自身，不监听根或子 Logger。 */
    fun bridge(source: Logger): Disposable {
        val sourceName = source.name
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                try {
                    if (closed || !isLoggable(record) || sourceName != record.loggerName) return
                    val context = linkedMapOf<String, Any>("thread_id" to record.threadID)
                    record.thrown?.let { context["throwable_type"] = it.javaClass.name }
                    log(record.level, record.message.toString(), context)
                    record.thrown?.let { if (safePolicy().exceptions()) captureIncident("logger:$sourceName", it) }
                } catch (_: Throwable) { }
            }
            override fun flush() { }
            override fun close() { }
        }
        source.addHandler(handler)
        return Disposable { source.removeHandler(handler) }
    }

    fun breadcrumb(category: String, message: String) = breadcrumb(category, message, emptyMap<String, Any>())
    fun breadcrumb(category: String, message: String, context: Map<String, *>) {
        val breadcrumb = Breadcrumb(category, message, context)
        breadcrumbs.add(breadcrumb, DiagnosticJson.write(breadcrumb.toMap()))
    }

    fun captureIncident(name: String, error: Throwable): Incident {
        val fields = eventFields("incident", Texts.requireText(name, "name"))
        fields["fingerprint"] = IssueFingerprint.of(error)
        fields["source"] = "automatic"
        RemoteOperation.current()?.let { fields["operation_id"] = it.id() }
        fields["payload"] = incidentPayload(error)
        val incident = Incident(fields)
        if (!closed && safePolicy().exceptions()) emit(incident)
        return incident
    }

    fun captureManualIncident(name: String, attributes: Map<String, *>?): Incident {
        val fields = eventFields("incident", Texts.requireText(name, "name"))
        fields["fingerprint"] = "manual:$name"
        fields["source"] = "manual"
        RemoteOperation.current()?.let { fields["operation_id"] = it.id() }
        fields["attributes"] = if (attributes == null) emptyMap<String, String>() else RemoteLogContext.builder().context(attributes).build().context()
        fields["payload"] = incidentPayload(null).apply { put("manual", true) }
        val incident = Incident(fields)
        if (!closed && safePolicy().manualIncidents()) emit(incident)
        return incident
    }

    fun droppedEvents(): Long = droppedEvents.get()
    override fun dispose() { closed = true; contributorExecutor.shutdownNow() }
    private fun safePolicy(): RemotePolicy = try {
        val current: RemotePolicy? = policy.get()
        current ?: RemotePolicy.builder().build()
    }
        catch (_: Throwable) { RemotePolicy.builder().build() }
    private fun emit(event: RemoteEvent) {
        try { sink.accept(event) } catch (_: Throwable) { droppedEvents.incrementAndGet() }
    }
    private fun eventFields(type: String, message: String): MutableMap<String, Any> = linkedMapOf(
        "event_id" to EventIds.next(), "type" to type, "occurred_at" to Instant.now().toString(), "message" to message)
    private fun incidentPayload(error: Throwable?): MutableMap<String, Any> {
        val payload = linkedMapOf<String, Any>()
        error?.let { payload["throwable"] = ThrowableSnapshot.capture(it, budget).toMap() }
        val operation = RemoteOperation.current()
        operation?.let { payload["operation"] = it.toMap() }
        payload["log_window"] = logWindow.snapshot().map { it.toMap() }
        payload["breadcrumbs"] = breadcrumbs.snapshot().map { it.toMap() }
        payload["contributors"] = collectContributors(error, operation)
        return payload
    }

    private fun collectContributors(error: Throwable?, operation: RemoteOperation.Context?): List<Map<String, Any>> {
        val context = DiagnosticContributor.Context(error, operation)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget.contributorTimeoutMillis())
        val tasks = contributors.take(budget.maxContributors()).mapIndexed { index, contributor ->
            val future: Future<Map<String, Any>> = try {
                contributorExecutor.submit(Callable { collectContributor(contributor, context, index) })
            } catch (_: RuntimeException) {
                CompletableFuture.completedFuture(contributorFailure(index, "rejected", null))
            }
            ContributorTask(index, future)
        }
        return tasks.map { task ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                task.future.cancel(true)
                contributorFailure(task.index, "timeout", null)
            } else try {
                task.future.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                task.future.cancel(true)
                contributorFailure(task.index, "timeout", null)
            } catch (failure: Exception) {
                contributorFailure(task.index, "error", failure.cause ?: failure)
            }
        }
    }

    private fun collectContributor(contributor: DiagnosticContributor, context: DiagnosticContributor.Context, index: Int): Map<String, Any> {
        val name = try { Texts.requireText(contributor.name(), "contributor name") }
            catch (_: RuntimeException) { "invalid-contributor-$index" }
        return linkedMapOf<String, Any>("name" to name).apply {
            try {
                put("data", boundedMap(contributor.contribute(context)))
                put("status", "ok")
            } catch (failure: Throwable) {
                put("status", "error"); put("error_type", failure.javaClass.name)
            }
        }
    }

    private fun contributorFailure(index: Int, status: String, failure: Throwable?): Map<String, Any> =
        linkedMapOf<String, Any>("name" to "contributor-$index", "status" to status).apply {
            failure?.let { put("error_type", it.javaClass.name) }
        }

    private fun boundedMap(values: Map<String, *>?): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        var bytes = 0
        for ((rawKey, rawValue) in values ?: return emptyMap()) {
            if (result.size >= budget.contributorEntries()) break
            val nullableKey: String? = rawKey
            val key = nullableKey.toString()
            val value = rawValue.toString()
            val entryBytes = key.toByteArray(UTF_8).size + value.toByteArray(UTF_8).size
            if (entryBytes > budget.contributorBytes()) continue
            if (bytes + entryBytes > budget.contributorBytes()) break
            result[key] = value
            bytes += entryBytes
        }
        return result
    }

    private class ContributorTask(val index: Int, val future: Future<Map<String, Any>>)
    private class BoundedFacts<T>(private val maxEntries: Int, private val maxBytes: Int) {
        private val entries = ArrayDeque<Entry<T>>()
        private var bytes = 0
        @Synchronized fun add(value: T, encoded: String) {
            val size = encoded.toByteArray(UTF_8).size
            if (size > maxBytes) return
            while (entries.isNotEmpty() && (entries.size >= maxEntries || bytes + size > maxBytes)) bytes -= entries.removeFirst().bytes
            entries.addLast(Entry(value, size))
            bytes += size
        }
        @Synchronized fun snapshot(): List<T> = entries.map { it.value }
    }
    private class Entry<T>(val value: T, val bytes: Int)

    class Builder internal constructor(loggerName: String, internal val sink: Consumer<in RemoteEvent>) {
        internal val name = Texts.requireText(loggerName, "loggerName")
        internal var policySource: Supplier<RemotePolicy> = Supplier { RemotePolicy.builder().build() }
        internal var incidentBudget = IncidentBudget.defaults()
        internal val contributors = ArrayList<DiagnosticContributor>()
        fun policy(value: Supplier<RemotePolicy>): Builder = apply { policySource = value }
        fun budget(value: IncidentBudget): Builder = apply { incidentBudget = value }
        fun contributor(value: DiagnosticContributor): Builder = apply { contributors.add(value) }
        fun build(): RemoteLogger = RemoteLogger(this)
    }
    companion object {
        @JvmStatic fun builder(loggerName: String, sink: Consumer<in RemoteEvent>): Builder = Builder(loggerName, sink)
        private fun wireLevel(level: Level): String = when {
            level.intValue() >= Level.SEVERE.intValue() -> "error"
            level.intValue() >= Level.WARNING.intValue() -> "warn"
            level.intValue() >= Level.INFO.intValue() -> "info"
            level.intValue() >= Level.FINE.intValue() -> "debug"
            else -> "trace"
        }
    }
}
