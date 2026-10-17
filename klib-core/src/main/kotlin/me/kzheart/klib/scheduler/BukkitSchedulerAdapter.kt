package me.kzheart.klib.scheduler

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import me.kzheart.klib.KLogger
import me.kzheart.klib.diagnostic.DiagnosticSource
import me.kzheart.klib.scope.Scope
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask

class BukkitSchedulerAdapter(
    private val plugin: Plugin,
    private val scope: Scope,
    private val scheduler: BukkitScheduler,
    private val logger: KLogger
) : KScheduler, DiagnosticSource {
    private val asyncExecutor = BukkitAsyncExecutor()
    private val syncExecutor = Executor { scheduler.runTask(plugin, it) }
    private val scopedSyncExecutor = Executor { command ->
        if (scope.isClosed) throw RejectedExecutionException("Scope is closed: ${scope.name()}")
        sync(command)
    }

    private fun handle() = scope.install(ScopedTaskHandle<BukkitTask>(scope, { it.cancel() }))

    override fun every(period: Ticks, task: Runnable): TaskHandle {
        require(period.value() >= 1) { "period must be at least one tick" }
        val handle = handle()
        return handle.submit {
            handle.attach(scheduler.runTaskTimer(plugin, handle.recurring(observed(task)),
                period.value(), period.value()))
        }
    }

    override fun after(delay: Ticks, task: Runnable): TaskHandle {
        val handle = handle()
        return handle.submit {
            handle.attach(scheduler.runTaskLater(plugin, handle.once(observed(task)), delay.value()))
        }
    }

    override fun sync(task: Runnable): TaskHandle = after(Ticks.of(0), task)
    override fun syncExecutor(): Executor = scopedSyncExecutor

    override fun <T> async(supplier: Supplier<T>): AsyncTask<T> {
        val observed = Supplier { observe("异步任务执行失败") { supplier.get() } }
        val task = scope.install(AsyncTaskImpl(asyncExecutor, syncExecutor, observed, scope))
        return try { task.start(); task } catch (failure: RuntimeException) {
            task.cancel()
            throw failure
        }
    }

    private fun observed(task: Runnable) = Runnable { observe("任务执行失败") { task.run() } }

    private inline fun <T> observe(message: String, action: () -> T): T = try { action() }
    catch (failure: RuntimeException) { logger.error("$message: ${scope.name()}", failure); throw failure }
    catch (failure: Error) { logger.error("$message: ${scope.name()}", failure); throw failure }

    override fun diagnosticName(): String = "scheduler"
    override fun diagnosticSnapshot(): Map<String, *> = linkedMapOf(
        "backend" to "bukkit", "scope" to scope.name(), "scope_closed" to scope.isClosed,
        "async_shutdown" to asyncExecutor.isShutdown)

    private inner class BukkitAsyncExecutor : AbstractExecutorService() {
        @Volatile private var shutdown = false
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("Bukkit async executor is shut down")
            scheduler.runTaskAsynchronously(plugin, command)
        }
    }
}
