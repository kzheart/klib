package me.kzheart.klib.config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import me.kzheart.klib.scope.Disposable
internal class FailureListenerList {
    private val listeners = CopyOnWriteArrayList<Consumer<in Throwable>>()
    fun add(listener: Consumer<in Throwable>): Disposable {
        listeners.add(listener)
        val disposed = AtomicBoolean()
        return Disposable { if (disposed.compareAndSet(false, true)) listeners.remove(listener) }
    }
    fun snapshot(): Iterable<Consumer<in Throwable>> = listeners
    fun clear() = listeners.clear()
}
