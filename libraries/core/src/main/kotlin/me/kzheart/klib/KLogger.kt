package me.kzheart.klib

import java.time.Instant
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiConsumer
import java.util.logging.Level
import java.util.logging.Logger
import me.kzheart.klib.scope.Disposable

/** Thread-safe diagnostic buffer and module switches behind the standard logger. */
class KLogger @JvmOverloads constructor(delegate: Logger?, private val bufferSize: Int = 256) {
    private val delegate = requireNotNull(delegate) { "delegate cannot be null" }
    private val recent = ArrayDeque<String>()
    private val debugModules = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val errorObservers = CopyOnWriteArrayList<BiConsumer<String?, Throwable?>>()
    private val notifying = ThreadLocal<Boolean>()
    init { require(bufferSize >= 1) { "bufferSize must be positive" } }

    fun info(message: String?) = info(null, message)
    fun info(module: String?, message: String?) = write(Level.INFO, INFO, module, message, null)
    fun success(message: String?) = success(null, message)
    fun success(module: String?, message: String?) = write(Level.INFO, SUCCESS, module, message, null)
    fun warn(message: String?) = warn(null, message)
    fun warn(module: String?, message: String?) = write(Level.WARNING, WARN, module, message, null)
    fun error(message: String?, error: Throwable?) = error(null, message, error)
    fun error(module: String?, message: String?, error: Throwable?) {
        write(Level.SEVERE, ERROR, module, message, error)
        if (notifying.get() == true) return
        notifying.set(true)
        try {
            errorObservers.forEach { observer ->
                try { observer.accept(message, error) }
                catch (failure: RuntimeException) { delegate.log(Level.WARNING, "Error observer failed", failure) }
            }
        } finally { notifying.remove() }
    }
    fun onError(observer: BiConsumer<String?, Throwable?>): Disposable {
        errorObservers.add(observer)
        return Disposable { errorObservers.remove(observer) }
    }
    fun debug(module: String?, message: String?) {
        val normalized = normalizeModule(module)
        if (!debugModules.contains("*") && !debugModules.contains(normalized)) return
        record("DEBUG", normalized, message)
        delegate.log(Level.INFO, "$INFO [debug:$normalized] $message")
    }
    fun setDebug(module: String?, enabled: Boolean) {
        val normalized = normalizeModule(module)
        if (enabled) debugModules.add(normalized) else debugModules.remove(normalized)
    }
    fun isDebugEnabled(module: String?): Boolean = debugModules.contains("*") || debugModules.contains(normalizeModule(module))
    fun recentLines(): List<String> = synchronized(recent) { Collections.unmodifiableList(ArrayList(recent)) }
    private fun write(level: Level, symbol: String, module: String?, message: String?, error: Throwable?) {
        record(level.name, normalizeModule(module), message)
        val rendered = "$symbol $message"
        if (error == null) delegate.log(level, rendered) else delegate.log(level, rendered, error)
    }
    private fun record(level: String, module: String, message: String?) {
        val line = "${Instant.now()} $level [$module] $message"
        synchronized(recent) {
            while (recent.size >= bufferSize) recent.removeFirst()
            recent.addLast(line)
        }
    }
    companion object {
        private val ascii = java.lang.Boolean.parseBoolean(System.getProperty("klib.logger.ascii", "false"))
        private val INFO = if (ascii) ">" else "❯"
        private val SUCCESS = if (ascii) "+" else "✔"
        private val WARN = if (ascii) "!" else "⚠"
        private val ERROR = if (ascii) "x" else "✖"
        private fun normalizeModule(module: String?): String = module?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT) ?: "core"
    }
}
