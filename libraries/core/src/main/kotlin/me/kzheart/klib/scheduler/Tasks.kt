package me.kzheart.klib.scheduler

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import me.kzheart.klib.reflect.Declarations
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

class Tasks(private val owner: Scope) {
    fun every(ticks: Ticks, task: Runnable): TaskHandle = owner.every(ticks, task)
    fun after(ticks: Ticks, task: Runnable): TaskHandle = owner.after(ticks, task)
    fun sync(task: Runnable): TaskHandle = owner.sync(task)
    fun <T> submitSync(action: Supplier<T>): CompletableFuture<T> = owner.submitSync(action)
    fun <S, T> submitSync(stage: CompletionStage<S>, action: Function<in S, out T>): CompletableFuture<T> = owner.submitSync(stage, action)
    fun <T> async(task: Supplier<T>): AsyncTask<T> = owner.async(task)
    fun <T> thenSync(stage: CompletionStage<T>, success: Consumer<in T>, failure: Consumer<in Throwable>?): CompletionStage<T> =
        AsyncTasks.thenSync(stage, owner, success, failure)
    fun register(target: Any): Disposable {
        val methods = Declarations.methods(target.javaClass).mapNotNull { method ->
            val every = method.getAnnotation(Every::class.java) ?: return@mapNotNull null
            Declarations.voidMethod(method, 0)
            if (every.ticks <= 0) throw Declarations.invalid(method, "ticks must be positive")
            method to every
        }
        return owner.scope("tasks-${ids.incrementAndGet()}") { child ->
            for ((method, every) in methods) {
                val running = AtomicBoolean()
                val invoke = Runnable { try { Declarations.invoke(target, method) } finally { running.set(false) } }
                child.every(Ticks.of(every.ticks)) {
                    if (running.compareAndSet(false, true)) {
                        if (every.thread == TaskThread.SYNC) invoke.run() else {
                            try { child.async { invoke.run(); null } }
                            catch (failure: RuntimeException) { running.set(false); throw failure }
                            catch (failure: Error) { running.set(false); throw failure }
                        }
                    }
                }
            }
        }
    }
    companion object { private val ids = AtomicLong() }
}
