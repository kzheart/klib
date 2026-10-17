package me.kzheart.klib.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import me.kzheart.klib.diagnostic.DiagnosticSource
import me.kzheart.klib.scope.Scope

class ExecutorScheduler(
    private val scope: Scope,
    private val timerExecutor: ScheduledExecutorService,
    private val asyncExecutor: ExecutorService,
    private val syncExecutor: Executor
) : KScheduler, DiagnosticSource {
    private val scopedSyncExecutor = Executor { command ->
        if (scope.isClosed) throw RejectedExecutionException("Scope is closed: ${scope.name()}")
        sync(command)
    }

    private fun handle() = scope.install(ScopedTaskHandle<Future<*>>(
        scope, { it.cancel(false) }, { it.isDone }, cancelAfterDone = true))

    override fun every(period: Ticks, task: Runnable): TaskHandle {
        require(period.value() != 0L) { "period must be at least one tick" }
        val handle = handle()
        return handle.submit {
            handle.attach(timerExecutor.scheduleAtFixedRate(handle.recurring(task),
                period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS))
        }
    }

    override fun after(delay: Ticks, task: Runnable): TaskHandle {
        val handle = handle()
        return handle.submit {
            handle.attach(timerExecutor.schedule(handle.once(task), delay.toMillis(), TimeUnit.MILLISECONDS))
        }
    }

    override fun sync(task: Runnable): TaskHandle {
        val handle = handle()
        return handle.submit { syncExecutor.execute(handle.once(task)) }
    }

    override fun syncExecutor(): Executor = scopedSyncExecutor

    override fun <T> async(supplier: Supplier<T>): AsyncTask<T> {
        val task = scope.install(AsyncTaskImpl(asyncExecutor, syncExecutor, supplier, scope))
        return try { task.start(); task } catch (failure: RuntimeException) {
            task.cancel()
            throw failure
        }
    }

    override fun diagnosticName(): String = "scheduler"
    override fun diagnosticSnapshot(): Map<String, *> = linkedMapOf(
        "backend" to "executor", "scope" to scope.name(), "scope_closed" to scope.isClosed,
        "timer_shutdown" to timerExecutor.isShutdown, "async_shutdown" to asyncExecutor.isShutdown)
}
