package me.kzheart.klib.config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import me.kzheart.klib.scope.Disposable
internal class ListenerList {
    private val listeners = CopyOnWriteArrayList<Runnable>()
    fun add(listener: Runnable) { listeners.add(listener) }
    fun registration(listener: Runnable): Disposable {
        val disposed = AtomicBoolean()
        return Disposable { if (disposed.compareAndSet(false, true)) listeners.remove(listener) }
    }
    fun clear() = listeners.clear()
    fun snapshot(): Iterable<Runnable> = listeners
}
