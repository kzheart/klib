package me.kzheart.klib.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.function.Consumer
import java.util.function.Supplier
import me.kzheart.klib.scope.Scope

class AsyncTaskImpl<T> @JvmOverloads constructor(
    private val asyncExecutor: ExecutorService,
    private val syncExecutor: Executor,
    private val supplier: Supplier<T>,
    private val owner: Scope? = null
) : AsyncTask<T> {
    private sealed interface Outcome<out T> {
        data object Pending : Outcome<Nothing>
        class Success<T>(val value: T) : Outcome<T>
        class Failure(val error: Throwable) : Outcome<Nothing>
    }
    private val lock = Any()
    private val callbacks = mutableListOf<Consumer<in T>>()
    private val errorCallbacks = mutableListOf<Consumer<in Throwable>>()
    private var future: Future<*>? = null
    private var outcome: Outcome<T> = Outcome.Pending
    private var started = false
    private var cancelled = false
    private var pendingDispatches = 0

    fun start() {
        val rejection = synchronized(lock) {
            check(!started) { "async task has already been started" }
            started = true
            if (cancelled) return
            try { future = asyncExecutor.submit(::runSupplier); return }
            catch (failure: RuntimeException) {
                outcome = Outcome.Failure(failure)
                callbacks.clear()
                errorCallbacks.clear()
                failure
            }
        }
        detachFromOwner()
        throw rejection
    }

    override fun thenSync(callback: Consumer<in T>): AsyncTask<T> {
        val current = synchronized(lock) {
            if (cancelled || outcome is Outcome.Failure) return this
            if (outcome === Outcome.Pending) callbacks.add(callback)
            outcome
        }
        if (current is Outcome.Success) dispatch { callback.accept(current.value) }
        return this
    }

    override fun onError(callback: Consumer<in Throwable>): AsyncTask<T> {
        val current = synchronized(lock) {
            if (cancelled || outcome is Outcome.Success) return this
            if (outcome === Outcome.Pending) errorCallbacks.add(callback)
            outcome
        }
        if (current is Outcome.Failure) dispatch { callback.accept(current.error) }
        return this
    }

    override fun cancel(): Boolean {
        val submitted = synchronized(lock) {
            if (cancelled) return false
            cancelled = true
            callbacks.clear()
            errorCallbacks.clear()
            future
        }
        submitted?.cancel(false)
        detachFromOwner()
        return true
    }
    override fun isCancelled(): Boolean = synchronized(lock) { cancelled }
    override fun isDone(): Boolean = synchronized(lock) { outcome !== Outcome.Pending || cancelled }
    override fun isFailed(): Boolean = synchronized(lock) { outcome is Outcome.Failure }

    private fun runSupplier() {
        val supplied = try { supplier.get() }
        catch (failure: RuntimeException) { completeExceptionally(failure); throw failure }
        catch (failure: Error) { completeExceptionally(failure); throw failure }
        val pending = synchronized(lock) {
            outcome = Outcome.Success(supplied)
            if (cancelled) { callbacks.clear(); errorCallbacks.clear(); return }
            callbacks.toList().also { callbacks.clear(); errorCallbacks.clear() }
        }
        pending.forEach { callback -> dispatch { callback.accept(supplied) } }
        detachWhenIdle()
    }

    private fun completeExceptionally(failure: Throwable) {
        val pending = synchronized(lock) {
            outcome = Outcome.Failure(failure)
            callbacks.clear()
            if (cancelled) { errorCallbacks.clear(); return }
            errorCallbacks.toList().also { errorCallbacks.clear() }
        }
        pending.forEach { callback -> dispatch { callback.accept(failure) } }
        detachWhenIdle()
    }

    /** Both success and failure callbacks use exactly the same cancellation boundary. */
    private fun dispatch(callback: () -> Unit) {
        synchronized(lock) { pendingDispatches++ }
        try {
            syncExecutor.execute {
                try {
                    synchronized(lock) { if (cancelled) return@execute }
                    if (owner?.isClosed == true) { cancel(); return@execute }
                    callback()
                } finally { dispatchFinished() }
            }
        } catch (_: RuntimeException) { dispatchFinished(); cancel() }
    }
    private fun dispatchFinished() {
        synchronized(lock) { pendingDispatches-- }
        detachWhenIdle()
    }
    private fun detachWhenIdle() {
        synchronized(lock) { if (pendingDispatches > 0) return }
        detachFromOwner()
    }
    private fun detachFromOwner() { owner?.remove(this) }
}
