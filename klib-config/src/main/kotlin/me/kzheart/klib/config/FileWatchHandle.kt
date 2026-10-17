package me.kzheart.klib.config

import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate
import java.util.logging.Logger
import me.kzheart.klib.scope.Disposable

/** One daemon watcher per directory, with reference-counted subscriptions and debounced events. */
internal class FileWatchHandle private constructor() {
    private class DirectoryWatcher(val directory: Path, private val watchService: WatchService) {
        val subscriptions = CopyOnWriteArrayList<Subscription>()
        private val closed = AtomicBoolean()
        val thread = Thread({ watchLoop() }, "klib-config-watch-${directory.fileName}").apply { isDaemon = true }
        fun subscribe(filter: Predicate<Path>, listener: Runnable): Disposable = Subscription(this, filter, listener).also { subscriptions.add(it) }
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { watchService.close() } catch (ignored: IOException) { /* Thread interruption is the fallback. */ }
            thread.interrupt()
        }
        private fun watchLoop() {
            while (!closed.get()) {
                val key = try { watchService.take() }
                catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return }
                catch (ignored: ClosedWatchServiceException) { return }
                val changed = linkedSetOf<Path>()
                var overflow = drain(key, changed)
                if (!resetOrRecover(key)) return
                if (!debounce(changed)) overflow = true
                notifySubscribers(changed, overflow)
            }
        }
        private fun debounce(changed: MutableSet<Path>): Boolean {
            var healthy = true
            while (true) {
                val extra = try { watchService.poll(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS) }
                catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return healthy }
                catch (ignored: ClosedWatchServiceException) { return healthy }
                if (extra == null) return healthy
                if (drain(extra, changed)) healthy = false
                if (!resetOrRecover(extra)) return false
            }
        }
        private fun drain(key: WatchKey, changed: MutableSet<Path>): Boolean {
            var overflow = false
            for (event in key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) overflow = true
                else (event.context() as? Path)?.let { changed.add(directory.resolve(it)) }
            }
            return overflow
        }
        private fun notifySubscribers(changed: Set<Path>, overflow: Boolean) {
            if (closed.get() || changed.isEmpty() && !overflow) return
            for (subscription in subscriptions) {
                if (overflow || subscription.matchesAny(changed)) {
                    try { subscription.listener.run() }
                    catch (ignored: RuntimeException) { /* Reload holders retain the valid snapshot and report the failure. */ }
                }
            }
        }
        private fun resetOrRecover(key: WatchKey): Boolean {
            if (key.reset()) return true
            if (closed.get()) return false
            logger.warning("$directory: config watch key became invalid; re-registering directory")
            return try {
                Files.createDirectories(directory)
                register()
                true
            } catch (failure: IOException) { stopped(failure) }
            catch (failure: ClosedWatchServiceException) { stopped(failure) }
        }
        private fun stopped(failure: Exception): Boolean {
            logger.severe("$directory: config watcher stopped, automatic reloads are disabled: $failure")
            return false
        }
        fun register() { directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE) }
    }
    private class Subscription(val watcher: DirectoryWatcher, val filter: Predicate<Path>, val listener: Runnable) : Disposable {
        private val disposed = AtomicBoolean()
        fun matchesAny(changed: Set<Path>): Boolean = !disposed.get() && changed.any { filter.test(it) }
        override fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            watcher.subscriptions.remove(this)
            release(watcher)
        }
    }
    companion object {
        private val logger = Logger.getLogger(FileWatchHandle::class.java.name)
        private const val DEBOUNCE_MILLIS = 75L
        private val watchersLock = Any()
        private val watchers = hashMapOf<Path, DirectoryWatcher>()
        @JvmStatic fun start(directory: Path, filter: Predicate<Path>, listener: Runnable): Disposable {
            val normalized = directory.toAbsolutePath().normalize()
            return synchronized(watchersLock) {
                val watcher = watchers.getOrPut(normalized) { open(normalized) }
                watcher.subscribe(filter, listener)
            }
        }
        private fun open(directory: Path): DirectoryWatcher = try {
            Files.createDirectories(directory)
            DirectoryWatcher(directory, FileSystems.getDefault().newWatchService()).also { it.register(); it.thread.start() }
        } catch (failure: IOException) { throw ConfigException("$directory: cannot start config watcher", failure) }
        private fun release(watcher: DirectoryWatcher) = synchronized(watchersLock) {
            if (watchers[watcher.directory] === watcher && watcher.subscriptions.isEmpty()) {
                watchers.remove(watcher.directory)
                watcher.close()
            }
        }
    }
}
