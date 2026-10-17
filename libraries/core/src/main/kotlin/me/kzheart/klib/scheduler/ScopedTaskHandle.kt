package me.kzheart.klib.scheduler

import me.kzheart.klib.scope.Scope

/** Owns the attach/cancel race shared by both scheduler backends. */
internal class ScopedTaskHandle<T : Any>(
    private val owner: Scope,
    private val cancelScheduled: (T) -> Unit,
    private val scheduledDone: (T) -> Boolean = { false },
    private val cancelAfterDone: Boolean = false
) : TaskHandle {
    private val lock = Any()
    private var scheduled: T? = null
    private var cancelled = false
    private var done = false

    fun attach(task: T) = synchronized(lock) {
        scheduled = task
        if (cancelled) cancelScheduled(task)
    }

    fun markDone() {
        synchronized(lock) { done = true }
        owner.remove(this)
    }

    override fun cancel(): Boolean {
        val task = synchronized(lock) {
            if (cancelled || done && !cancelAfterDone) return false
            cancelled = true
            scheduled
        }
        task?.let(cancelScheduled)
        owner.remove(this)
        return true
    }

    override fun isCancelled(): Boolean = synchronized(lock) { cancelled }
    override fun isDone(): Boolean = synchronized(lock) {
        cancelled || done || scheduled?.let(scheduledDone) == true
    }

    fun once(task: Runnable): Runnable = Runnable {
        try { if (!isCancelled) task.run() } finally { markDone() }
    }

    fun recurring(task: Runnable): Runnable = Runnable {
        try { if (!isCancelled) task.run() }
        catch (failure: RuntimeException) { cancel(); throw failure }
        catch (failure: Error) { cancel(); throw failure }
    }

    inline fun submit(action: () -> Unit): TaskHandle = try {
        action()
        this
    } catch (failure: RuntimeException) {
        cancel()
        throw failure
    }
}
