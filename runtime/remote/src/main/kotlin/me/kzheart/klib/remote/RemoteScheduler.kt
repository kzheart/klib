package me.kzheart.klib.remote

import me.kzheart.klib.scheduler.AsyncTask
import me.kzheart.klib.scheduler.KScheduler
import me.kzheart.klib.scheduler.TaskHandle
import me.kzheart.klib.scheduler.Ticks
import java.util.concurrent.Executor
import java.util.function.Supplier

/** 薄调度适配器，只传播 operation，不改变底层调度语义。 */
class RemoteScheduler(private val delegate: KScheduler) : KScheduler {
    override fun every(period: Ticks, task: Runnable): TaskHandle = delegate.every(period, RemoteOperation.wrapCurrent(task))
    override fun after(delay: Ticks, task: Runnable): TaskHandle = delegate.after(delay, RemoteOperation.wrapCurrent(task))
    override fun sync(task: Runnable): TaskHandle = delegate.sync(RemoteOperation.wrapCurrent(task))
    override fun syncExecutor(): Executor {
        val executor = delegate.syncExecutor()
        return Executor { command -> executor.execute(RemoteOperation.wrapCurrent(command)) }
    }
    override fun <T> async(supplier: Supplier<T>): AsyncTask<T> = delegate.async(RemoteOperation.wrapCurrent(supplier))
}
