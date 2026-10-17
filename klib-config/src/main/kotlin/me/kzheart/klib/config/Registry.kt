package me.kzheart.klib.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Function
import me.kzheart.klib.diagnostic.DiagnosticSource
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

/** Directory-backed registry whose complete candidate set is validated before publication. */
class Registry<T> private constructor(
    private val owner: Scope,
    private val directory: Path,
    private val type: Class<T>,
    private val idExtractor: Function<in T, String>,
    private val mapper: YamlConfigMapper,
    listenerExecutor: Consumer<Runnable>,
    initial: Map<String, T>,
) : Disposable, ReloadFailureSource, DiagnosticSource {
    private val lifecycleLock = Any()
    private val reloadMutex = Any()
    @Volatile private var snapshot = initial
    @Volatile private var lastFailure: Throwable? = null
    private var watcher: Disposable? = null
    private var disposed = false
    private val listeners = ReloadListeners(owner, listenerExecutor, { directory.toString() }, { synchronized(lifecycleLock) { !disposed } }, true)
    fun snapshot(): Map<String, T> = snapshot
    @Suppress("UNCHECKED_CAST") fun find(id: String?): Optional<T> = Optional.ofNullable(snapshot[id]) as Optional<T>
    fun values(): Collection<T> = snapshot.values
    fun reload() = synchronized(reloadMutex) {
        synchronized(lifecycleLock) { ensureOpen() }
        try {
            val candidate = loadSnapshot(directory, type, idExtractor, mapper)
            synchronized(lifecycleLock) { ensureOpen(); snapshot = candidate; lastFailure = null }
        } catch (failure: RuntimeException) {
            lastFailure = failure
            listeners.report(failure)
            throw failure
        }
        listeners.dispatch()
        Unit
    }
    fun onChange(listener: Runnable): Disposable {
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
    override fun diagnosticName(): String = "config-registry"
    override fun diagnosticSnapshot(): Map<String, *> = linkedMapOf(
        "directory" to directory.toString(), "value_type" to type.name, "entries" to snapshot.size,
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
    private fun ensureOpen() { check(!disposed && !owner.isClosed) { "Registry is closed: $directory" } }
    private fun reloadFromWatcher() { try { reload() } catch (failure: RuntimeException) { /* Failure is already reported. */ } }

    companion object {
        @JvmStatic fun <T> open(owner: Scope, directory: Path, type: Class<T>, idExtractor: Function<in T, String>, mapper: YamlConfigMapper): Registry<T> =
            open(owner, directory, type, idExtractor, mapper, true)
        @JvmStatic fun <T> open(owner: Scope, directory: Path, type: Class<T>, idExtractor: Function<in T, String>, mapper: YamlConfigMapper,
            watching: Boolean): Registry<T> = open(owner, directory, type, idExtractor, mapper, watching, listenerExecutor(owner))
        @JvmStatic fun <T> open(owner: Scope, directory: Path, type: Class<T>, idExtractor: Function<in T, String>, mapper: YamlConfigMapper,
            watching: Boolean, listenerExecutor: Consumer<Runnable>): Registry<T> {
            check(!owner.isClosed) { "Cannot open registry in closed scope: ${owner.name()}" }
            val normalized = directory.toAbsolutePath().normalize()
            val registry = Registry(owner, normalized, type, idExtractor, mapper, listenerExecutor, loadSnapshot(normalized, type, idExtractor, mapper))
            try {
                owner.install(registry)
                if (watching) {
                    val watcher = FileWatchHandle.start(normalized, { isYaml(it) }, { registry.reloadFromWatcher() })
                    registry.watcher = watcher
                    try { owner.install(watcher) } catch (failure: RuntimeException) { watcher.dispose(); throw failure }
                }
                return registry
            } catch (failure: RuntimeException) { registry.dispose(); throw failure }
        }
        private fun listenerExecutor(owner: Scope): Consumer<Runnable> {
            val factory = owner.findCapability(SchedulerFactory::class.java)
            return if (!factory.isPresent) Consumer { it.run() } else Consumer { factory.get().forScope(owner).after(Ticks.of(0L), it) }
        }
        private fun <T> loadSnapshot(directory: Path, type: Class<T>, idExtractor: Function<in T, String>, mapper: YamlConfigMapper): Map<String, T> = try {
            Files.createDirectories(directory)
            val files = Files.newDirectoryStream(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && isYaml(it) }.sortedBy { it.fileName.toString() }
            }
            val loaded = linkedMapOf<String, T>()
            for (file in files) {
                val value = mapper.read(YamlDocument.parse(file.toString(), FileConfigSource.readStrictUtf8(file)).root(), type)
                val extracted = try { idExtractor.apply(value) }
                    catch (failure: RuntimeException) { throw ConfigException("$file:<root>: registry ID extraction failed", failure) }
                if (extracted.isNullOrBlank()) throw ConfigException("$file:<root>: registry ID must not be blank")
                val id = extracted.trim()
                if (loaded.put(id, value) != null) throw ConfigException("$directory:<root>: duplicate registry ID '$id'")
            }
            Collections.unmodifiableMap(loaded)
        } catch (failure: IOException) { throw ConfigException("$directory: cannot load registry", failure) }
        private fun isYaml(file: Path): Boolean = file.fileName.toString().lowercase(Locale.ROOT).let { it.endsWith(".yml") || it.endsWith(".yaml") }
    }
}
