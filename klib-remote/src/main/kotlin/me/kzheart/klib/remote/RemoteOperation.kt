package me.kzheart.klib.remote

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** 显式 operation 作用域；包装异步任务时传播捕获的上下文并恢复线程原状态。 */
class RemoteOperation private constructor(private var context: Context, private val owner: Boolean) : AutoCloseable {
    private val previous = CURRENT.get()
    private var closed = false
    init { CURRENT.set(context) }

    fun phase(value: String): RemoteOperation = update { it.copy(phase = Texts.requireText(value, "phase")) }
    fun outcome(value: String): RemoteOperation = update { it.copy(outcome = Texts.requireText(value, "outcome")) }
    fun attribute(key: String, value: String): RemoteOperation = update { it.withAttribute(key, value) }
    fun context(): Context = context

    private inline fun update(change: (Context) -> Context): RemoteOperation {
        check(!closed) { "operation is closed" }
        context = change(context)
        CURRENT.set(context)
        return this
    }

    override fun close() {
        if (closed) return
        closed = true
        if (owner) context.timing.finish()
        if (previous == null) CURRENT.remove() else CURRENT.set(previous)
    }

    class Context internal constructor(private val id: String, private val parentId: String?,
        private val name: String, private val phase: String?, private val outcome: String,
        private val startedAt: String, attributes: Map<String, String>, ancestry: List<Context>,
        internal val timing: Timing) {
        private val attributes = Collections.unmodifiableMap(LinkedHashMap(attributes))
        internal val ancestry: List<Context> = Collections.unmodifiableList(ArrayList(ancestry))
        fun id(): String = id
        fun parentId(): String? = parentId
        fun name(): String = name
        fun phase(): String? = phase
        fun outcome(): String = outcome

        internal fun copy(phase: String? = this.phase, outcome: String = this.outcome,
            attributes: Map<String, String> = this.attributes): Context =
            Context(id, parentId, name, phase, outcome, startedAt, attributes, ancestry, timing)

        internal fun withAttribute(key: String, value: String): Context {
            val normalized = Texts.requireText(key, "attribute key")
            require(normalized.toByteArray(UTF_8).size <= 64 && value.toByteArray(UTF_8).size <= 1024) {
                "operation attribute exceeds byte limit"
            }
            check(attributes.containsKey(normalized) || attributes.size < 16) { "operation attributes exceed limit" }
            return copy(attributes = LinkedHashMap(attributes).apply { put(normalized, value) })
        }
        fun toMap(): Map<String, Any?> = nodeMap().apply { put("ancestors", ancestry.map { it.nodeMap() }) }
        private fun nodeMap(): MutableMap<String, Any?> = linkedMapOf(
            "id" to id, "parent_id" to parentId, "name" to name, "phase" to phase, "outcome" to outcome,
            "started_at" to startedAt, "duration_ms" to timing.durationMillis(), "attributes" to attributes)
    }

    internal class Timing(private val startedAtNanos: Long) {
        @Volatile private var endedAtNanos: Long? = null
        fun finish() { if (endedAtNanos == null) endedAtNanos = System.nanoTime() }
        fun durationMillis(): Long? = endedAtNanos?.let { TimeUnit.NANOSECONDS.toMillis(maxOf(0L, it - startedAtNanos)) }
    }

    companion object {
        private val CURRENT = ThreadLocal<Context>()
        @JvmStatic fun start(name: String): RemoteOperation {
            val parent = CURRENT.get()
            val context = Context("op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                parent?.id(), Texts.requireText(name, "name"), null, "running", Instant.now().toString(), emptyMap(),
                if (parent == null) emptyList() else parent.ancestry + parent, Timing(System.nanoTime()))
            return RemoteOperation(context, true)
        }
        @JvmStatic fun current(): Context? = CURRENT.get()
        @JvmStatic fun wrapCurrent(task: Runnable): Runnable {
            val captured = CURRENT.get()
            return Runnable { activate(captured) { task.run() } }
        }
        @JvmStatic fun <T> wrapCurrent(task: Callable<T>): Callable<T> {
            val captured = CURRENT.get()
            return Callable { activate(captured) { task.call() } }
        }
        @JvmStatic fun <T> wrapCurrent(task: Supplier<T>): Supplier<T> {
            val captured = CURRENT.get()
            return Supplier { activate(captured) { task.get() } }
        }
        private inline fun <T> activate(captured: Context?, task: () -> T): T {
            if (captured == null) return task()
            val activation = RemoteOperation(captured, false)
            try { return task() } finally { activation.close() }
        }
    }
}
