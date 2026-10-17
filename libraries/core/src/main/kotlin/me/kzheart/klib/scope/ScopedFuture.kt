package me.kzheart.klib.scope

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import me.kzheart.klib.scheduler.KScheduler
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scheduler.TaskHandle

/** External sources retain a clearable cell, never the scope or user callback. */
internal class ScopedFuture<S, T> : CompletableFuture<T>(), Disposable {
    private val pending = AtomicReference<Submission<S, T>>()
    private inline fun <R> releasing(action: () -> R): R {
        val released = pending.getAndSet(null)
        return try { action() } finally { released?.release() }
    }
    override fun complete(value: T): Boolean = releasing { super.complete(value) }
    override fun completeExceptionally(failure: Throwable): Boolean = releasing { super.completeExceptionally(failure) }
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = releasing { super.cancel(false) }
    override fun obtrudeValue(value: T) = releasing { super.obtrudeValue(value) }
    override fun obtrudeException(failure: Throwable) = releasing { super.obtrudeException(failure) }
    override fun dispose() { cancel(false) }

    private class Submission<S, T>(val generation: ScopeGeneration, val result: ScopedFuture<S, T>, val action: Function<in S, out T>) {
        private val queued = AtomicReference<TaskHandle>()
        lateinit var scheduler: KScheduler
        private var value: S? = null
        fun accept(value: S, failure: Throwable?) {
            if (result.isDone) return
            if (failure != null) { result.completeExceptionally(failure); return }
            try {
                generation.call {
                    if (!result.isDone) {
                        this.value = value
                        val reference = result.pending
                        val handle = scheduler.sync { reference.get()?.run() }
                        queued.set(handle)
                        if (handle.isCancelled) result.cancel(false)
                        if (result.isDone) release()
                    }
                }
            } catch (rejection: Throwable) { reject(rejection) }
        }
        @Suppress("UNCHECKED_CAST")
        private fun run() {
            val accepted = generation.run {
                if (!result.isDone) {
                    try { result.complete(action.apply(value as S)) }
                    catch (failure: Throwable) { result.completeExceptionally(failure) }
                }
            }
            if (!accepted) result.cancel(false)
        }
        fun reject(failure: Throwable) {
            if (generation.isActive()) result.completeExceptionally(failure) else result.cancel(false)
        }
        fun release() {
            generation.view().remove(result)
            queued.getAndSet(null)?.cancel()
        }
    }
    companion object {
        @JvmStatic fun <S, T> submit(scope: Scope, source: CompletionStage<S>, action: Function<in S, out T>): CompletableFuture<T> {
            val generation = try { ScopeGeneration.of(scope) } catch (_: IllegalStateException) {
                return CompletableFuture<T>().also { it.cancel(false) }
            }
            val result = ScopedFuture<S, T>()
            val submission = Submission(generation, result, action)
            result.pending.set(submission)
            try {
                submission.scheduler = generation.call {
                    val owner = generation.view()
                    owner.install(result)
                    owner.requireCapability(SchedulerFactory::class.java).forScope(owner)
                }
                val reference = result.pending
                source.whenComplete { value, failure -> reference.get()?.accept(value, failure) }
            } catch (rejection: Throwable) { submission.reject(rejection) }
            return result
        }
    }
}
