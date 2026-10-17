package me.kzheart.klib.remote

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Kotlin owns operation lifetime, immutable snapshots and asynchronous context restoration. */
internal class RemoteOperationState private constructor(
    private var context: RemoteOperation.Context, private val owner: Boolean
) : AutoCloseable {
    private val previous = CURRENT.get()
    private var closed = false
    init { CURRENT.set(context) }

    fun phase(value: String) = update { it.state.copy(phase = Texts.requireText(value, "phase")) }
    fun outcome(value: String) = update { it.state.copy(outcome = Texts.requireText(value, "outcome")) }
    fun attribute(key: String, value: String) = update { it.state.withAttribute(key, value) }
    fun context(): RemoteOperation.Context = context

    private inline fun update(change: (RemoteOperation.Context) -> RemoteOperation.Context) {
        check(!closed) { "operation is closed" }
        context = change(context)
        CURRENT.set(context)
    }
    override fun close() {
        if (closed) return
        closed = true
        if (owner) context.state.timing.finish()
        if (previous == null) CURRENT.remove() else CURRENT.set(previous)
    }

    class ContextState(private val id: String, private val parentId: String?,
        private val name: String, private val phase: String?, private val outcome: String,
        private val startedAt: String, attributes: Map<String, String>, ancestry: List<RemoteOperation.Context>,
        val timing: Timing) {
        private val attributes = Collections.unmodifiableMap(LinkedHashMap(attributes))
        val ancestry: List<RemoteOperation.Context> = Collections.unmodifiableList(ArrayList(ancestry))
        fun id(): String = id
        fun parentId(): String? = parentId
        fun name(): String = name
        fun phase(): String? = phase
        fun outcome(): String = outcome
        fun copy(phase: String? = this.phase, outcome: String = this.outcome,
            attributes: Map<String, String> = this.attributes): RemoteOperation.Context =
            RemoteOperation.Context(ContextState(id, parentId, name, phase, outcome, startedAt, attributes, ancestry, timing))

        fun withAttribute(key: String, value: String): RemoteOperation.Context {
            val normalized = Texts.requireText(key, "attribute key")
            require(normalized.toByteArray(UTF_8).size <= 64 && value.toByteArray(UTF_8).size <= 1024) {
                "operation attribute exceeds byte limit"
            }
            check(attributes.containsKey(normalized) || attributes.size < 16) { "operation attributes exceed limit" }
            return copy(attributes = LinkedHashMap(attributes).apply { put(normalized, value) })
        }
        fun toMap(): Map<String, Any?> = nodeMap().apply { put("ancestors", ancestry.map { it.state.nodeMap() }) }
        private fun nodeMap(): MutableMap<String, Any?> = linkedMapOf(
            "id" to id, "parent_id" to parentId, "name" to name, "phase" to phase, "outcome" to outcome,
            "started_at" to startedAt, "duration_ms" to timing.durationMillis(), "attributes" to attributes)
    }

    class Timing(private val startedAtNanos: Long) {
        @Volatile private var endedAtNanos: Long? = null
        fun finish() { if (endedAtNanos == null) endedAtNanos = System.nanoTime() }
        fun durationMillis(): Long? = endedAtNanos?.let { TimeUnit.NANOSECONDS.toMillis(maxOf(0L, it - startedAtNanos)) }
    }

    companion object {
        private val CURRENT = ThreadLocal<RemoteOperation.Context>()
        @JvmStatic fun start(name: String): RemoteOperationState {
            val parent = CURRENT.get()
            val context = RemoteOperation.Context(ContextState("op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                parent?.id(), Texts.requireText(name, "name"), null, "running", Instant.now().toString(), emptyMap(),
                if (parent == null) emptyList() else parent.state.ancestry + parent, Timing(System.nanoTime())))
            return RemoteOperationState(context, true)
        }
        @JvmStatic fun current(): RemoteOperation.Context? = CURRENT.get()
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
        private inline fun <T> activate(captured: RemoteOperation.Context?, task: () -> T): T {
            if (captured == null) return task()
            val activation = RemoteOperationState(captured, false)
            try { return task() } finally { activation.close() }
        }
    }
}
