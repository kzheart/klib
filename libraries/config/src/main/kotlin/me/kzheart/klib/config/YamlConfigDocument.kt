package me.kzheart.klib.config

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import me.kzheart.klib.config.api.ConfigDocument
import me.kzheart.klib.diagnostic.DiagnosticSource
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

/** Atomic typed publication of a reloadable YAML source. */
class YamlConfigDocument<T> private constructor(
    private val owner: Scope,
    private val source: ConfigSource,
    private val mapper: YamlConfigMapper,
    private val type: Class<T>,
    listenerExecutor: Consumer<Runnable>,
    initial: MappedConfig<T>,
) : ConfigDocument<T>, ReloadFailureSource, DiagnosticSource {
    private val lifecycleLock = Any()
    private val reloadMutex = Any()
    @Volatile private var value = initial.value
    @Volatile private var revision = initial.revision
    @Volatile private var lastFailure: Throwable? = null
    private var watcher: Disposable? = null
    private var disposed = false
    private val listeners = ReloadListeners(owner, listenerExecutor, { sourceName() }, { synchronized(lifecycleLock) { !disposed } }, false)

    override fun sourceName(): String = source.sourceName()
    override fun value(): T = value
    override fun reload() { reload(true) }
    override fun reloadAsync(): CompletionStage<Void> = try { reload(true) }
        catch (failure: RuntimeException) { CompletableFuture<Void>().also { it.completeExceptionally(failure) } }
    private fun reload(notifyWhenUnchanged: Boolean): CompletionStage<Void> = synchronized(reloadMutex) {
        synchronized(lifecycleLock) { ensureOpen() }
        val changed = try {
            val candidate = map(source, mapper, type)
            synchronized(lifecycleLock) {
                ensureOpen()
                val different = candidate.revision != revision
                value = candidate.value
                revision = candidate.revision
                lastFailure = null
                different
            }
        } catch (failure: RuntimeException) {
            lastFailure = failure
            listeners.report(failure)
            throw failure
        }
        if (changed || notifyWhenUnchanged) listeners.dispatch() else CompletableFuture.completedFuture(null)
    }
    override fun onChange(listener: Runnable): Disposable {
        val registration = synchronized(lifecycleLock) { ensureOpen(); listeners.addChange(listener) }
        return listeners.install(registration)
    }
    fun lastFailure(): Optional<Throwable> = Optional.ofNullable(lastFailure)
    override fun lastReloadFailure(): Optional<Throwable> = lastFailure()
    override fun onReloadFailure(listener: Consumer<in Throwable>): Disposable {
        synchronized(lifecycleLock) { ensureOpen() }
        return listeners.install(listeners.addFailure(listener))
    }
    fun lastListenerFailure(): Optional<Throwable> = Optional.ofNullable(listeners.lastFailure)
    override fun diagnosticName(): String = "config"
    override fun diagnosticSnapshot(): Map<String, *> = linkedMapOf(
        "source" to sourceName(), "value_type" to type.name, "revision" to revision,
        "reload_failure" to (lastFailure?.javaClass?.name ?: ""),
        "listener_failure" to (listeners.lastFailure?.javaClass?.name ?: ""),
        "closed" to synchronized(lifecycleLock) { disposed })
    override fun dispose() {
        val currentWatcher = synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            listeners.clear()
            watcher
        }
        currentWatcher?.dispose()
    }
    private fun reloadFromWatcher() { try { reload(false) } catch (failure: RuntimeException) { /* Failure is already reported. */ } }
    private fun ensureOpen() { check(!disposed && !owner.isClosed) { "Config document is closed: ${sourceName()}" } }
    private class MappedConfig<T>(val value: T, val revision: String)

    companion object {
        @JvmStatic fun <T> open(owner: Scope, source: ConfigSource, mapper: YamlConfigMapper, type: Class<T>): YamlConfigDocument<T> =
            open(owner, source, mapper, type, Consumer { it.run() })
        @JvmStatic fun <T> open(owner: Scope, source: ConfigSource, mapper: YamlConfigMapper, type: Class<T>,
            listenerExecutor: Consumer<Runnable>): YamlConfigDocument<T> {
            check(!owner.isClosed) { "Cannot load config into closed scope: ${owner.name()}" }
            val document = YamlConfigDocument(owner, source, mapper, type, listenerExecutor, map(source, mapper, type))
            try {
                owner.install(document)
                val watcher = source.watch { document.reloadFromWatcher() }
                document.watcher = watcher
                try { owner.install(watcher) } catch (failure: RuntimeException) { watcher.dispose(); throw failure }
                return document
            } catch (failure: RuntimeException) { document.dispose(); throw failure }
        }
        private fun <T> map(source: ConfigSource, mapper: YamlConfigMapper, type: Class<T>): MappedConfig<T> {
            val prepared = source.prepare() ?: throw ConfigException("${source.sourceName()}:<root>: source returned null prepare")
            val document = prepared.document() ?: throw ConfigException("${source.sourceName()}:<root>: source returned null document")
            val mapped = mapper.read(document.root(), type)
            prepared.commit()
            val revision = prepared.revision() ?: throw ConfigException("${source.sourceName()}:<root>: source returned null revision")
            return MappedConfig(mapped, revision)
        }
    }
}
