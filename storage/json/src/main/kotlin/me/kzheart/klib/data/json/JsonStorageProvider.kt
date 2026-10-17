package me.kzheart.klib.data.json

import me.kzheart.klib.data.StorageException
import me.kzheart.klib.data.StorageProvider
import me.kzheart.klib.data.StorageSession
import me.kzheart.klib.data.StorageTransaction
import me.kzheart.klib.data.TransactionContext
import me.kzheart.klib.diagnostic.DiagnosticSource
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 每个文件共享一份状态和一个串行执行器；会话仅作为该状态的视图。 */
class JsonStorageProvider(file: Path) : StorageProvider, DiagnosticSource {
    private val lock = Any()
    private val file = file.toAbsolutePath()
    private val storage = JsonStorageFile(this.file)
    private val sessions = Collections.newSetFromMap(ConcurrentHashMap<JsonStorageSession, Boolean>())
    private var disposed = false
    private var executor: ExecutorService? = null
    @Volatile private var executorThread: Thread? = null
    @Volatile private var state: JsonState? = null

    override fun open(): CompletionStage<StorageSession> {
        val active = synchronized(lock) {
            if (disposed) return failed(IllegalStateException("provider is disposed"))
            executor()
        }
        return CompletableFuture.supplyAsync({
            try {
                if (state == null) state = storage.read()
                val session = JsonStorageSession()
                sessions.add(session)
                synchronized(lock) {
                    if (disposed) {
                        sessions.remove(session)
                        throw IllegalStateException("provider is disposed")
                    }
                }
                session as StorageSession
            } catch (error: RuntimeException) { shutdownIfUnused(); throw error }
        }, active)
    }

    override fun dispose() {
        synchronized(lock) { if (disposed) return; disposed = true }
        sessions.forEach { it.dispose() }
        sessions.clear()
        val closing = synchronized(lock) { executor.also { executor = null } } ?: return
        closing.shutdown()
        if (Thread.currentThread() === executorThread) return
        try {
            if (!closing.awaitTermination(30L, TimeUnit.SECONDS)) {
                closing.shutdownNow()
                throw StorageException("Timed out closing JSON storage")
            }
        } catch (error: InterruptedException) {
            closing.shutdownNow()
            Thread.currentThread().interrupt()
            throw StorageException("Interrupted while closing JSON storage", error)
        }
    }

    override fun diagnosticName(): String = "storage"
    override fun diagnosticSnapshot(): Map<String, *> {
        val result = linkedMapOf<String, Any>("backend" to "json", "file" to file.fileName.toString(),
            "sessions" to sessions.size, "loaded" to (state != null))
        synchronized(lock) {
            result["closed"] = disposed
            result["worker_active"] = (executor != null)
        }
        return result
    }

    private fun executor(): ExecutorService = synchronized(lock) {
        executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "klib-json-storage").apply { isDaemon = true; executorThread = this }
        }.also { executor = it }
    }

    private fun shutdownIfUnused() = synchronized(lock) {
        if (sessions.isEmpty()) { executor?.shutdown(); executor = null }
    }

    private fun <T> execute(operation: StorageTransaction<T>, writable: Boolean): CompletionStage<T> {
        val active = synchronized(lock) {
            if (disposed) return failed(IllegalStateException("provider is disposed"))
            executor()
        }
        return CompletableFuture.supplyAsync({
            val current = state ?: JsonState()
            val candidate = if (writable) current.copy() else current
            try {
                val result = operation.execute(JsonTransactionContext(candidate))
                if (writable) { storage.write(candidate); state = candidate }
                result
            } catch (error: Exception) {
                throw if (error is StorageException) error else StorageException(
                    if (writable) "JSON storage transaction failed" else "JSON storage read failed", error)
            }
        }, active)
    }

    private inner class JsonStorageSession : StorageSession {
        private val sessionDisposed = AtomicBoolean()
        override fun get(namespace: String, key: String): CompletionStage<Optional<ByteArray>> =
            read { it.get(namespace, key) }
        override fun entries(namespace: String): CompletionStage<Map<String, ByteArray>> = read { it.entries(namespace) }
        override fun put(namespace: String, key: String, value: ByteArray): CompletionStage<Void> {
            val copy = value.copyOf()
            return write { it.put(namespace, key, copy) }
        }
        override fun delete(namespace: String, key: String): CompletionStage<Void> {
            return write { it.delete(namespace, key) }
        }
        private fun write(operation: (TransactionContext) -> Unit): CompletionStage<Void> =
            transaction(object : StorageTransaction<Void> {
                override fun execute(context: TransactionContext): Void? { operation(context); return null }
            })

        override fun <T> transaction(transaction: StorageTransaction<T>): CompletionStage<T> =
            if (sessionDisposed.get()) failed(IllegalStateException("session is disposed")) else execute(transaction, true)
        private fun <T> read(operation: StorageTransaction<T>): CompletionStage<T> =
            if (sessionDisposed.get()) failed(IllegalStateException("session is disposed")) else execute(operation, false)
        override fun dispose() { if (sessionDisposed.compareAndSet(false, true)) sessions.remove(this) }
    }

    private class JsonTransactionContext(private val state: JsonState) : TransactionContext {
        override fun get(namespace: String, key: String): Optional<ByteArray> {
            val entries = state.values[JsonStorageFile.requireName(namespace, "namespace")]
            val value = entries?.get(JsonStorageFile.requireName(key, "key"))
            return if (value == null) Optional.empty() else Optional.of(Base64.getDecoder().decode(value))
        }
        override fun put(namespace: String, key: String, value: ByteArray) {
            JsonStorageFile.requireName(namespace, "namespace")
            JsonStorageFile.requireName(key, "key")
            require(value.size <= JsonStorageFile.MAX_VALUE_BYTES) { "value exceeds ${JsonStorageFile.MAX_VALUE_BYTES} byte limit" }
            state.values.getOrPut(namespace) { LinkedHashMap() }[key] = Base64.getEncoder().encodeToString(value.copyOf())
        }
        override fun delete(namespace: String, key: String) {
            val entries = state.values[JsonStorageFile.requireName(namespace, "namespace")] ?: return
            entries.remove(JsonStorageFile.requireName(key, "key"))
            if (entries.isEmpty()) state.values.remove(namespace)
        }
        override fun entries(namespace: String): Map<String, ByteArray> {
            val stored = state.values[JsonStorageFile.requireName(namespace, "namespace")] ?: return emptyMap()
            return stored.mapValuesTo(LinkedHashMap()) { Base64.getDecoder().decode(it.value) }
        }
        override fun schemaVersion(schemaName: String): Int =
            state.schemas[JsonStorageFile.requireName(schemaName, "schemaName")] ?: 0
        override fun schemaVersion(schemaName: String, version: Int) {
            state.schemas[JsonStorageFile.requireName(schemaName, "schemaName")] = version
        }
    }

    private companion object {
        fun <T> failed(error: Throwable): CompletionStage<T> = CompletableFuture<T>().also { it.completeExceptionally(error) }
    }
}
