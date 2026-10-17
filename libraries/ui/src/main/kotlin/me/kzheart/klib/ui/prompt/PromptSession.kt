package me.kzheart.klib.ui.prompt

import me.kzheart.klib.scheduler.TaskHandle
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** 取消、超时和作答共享唯一终止路径；主线程完成阶段显式表达派发失败。 */
class PromptSession<T> internal constructor(private val owner: Scope, private val parser: PromptParser<T>) : Disposable {
    private val completion = CompletableFuture<PromptOutcome<T>>()
    private val syncCompletion = CompletableFuture<PromptOutcome<T>>()
    private var status = PromptStatus.WAITING
    private var timeoutTask: TaskHandle? = null
    private var syncExecutor: Executor? = null
    @Synchronized @JvmName("attachTimeout") internal fun attachTimeout(task: TaskHandle) { timeoutTask = task; if (status != PromptStatus.WAITING) task.cancel() }
    @Synchronized @JvmName("attachSync") internal fun attachSync(executor: Executor) { syncExecutor = executor }
    fun submit(message: String): Boolean {
        synchronized(this) { if (status != PromptStatus.WAITING) return false }
        val parsed = try { Objects.requireNonNull(parser.parse(message), "parser result") }
            catch (_: RuntimeException) { return false }
        return parsed.isPresent && finish(PromptStatus.ANSWERED, parsed.get())
    }
    fun cancel(): Boolean = finish(PromptStatus.CANCELLED, null)
    @JvmName("timeout") internal fun timeout(): Boolean = finish(PromptStatus.TIMED_OUT, null)
    @Synchronized fun status(): PromptStatus = status
    fun completion(): CompletionStage<PromptOutcome<T>> = completion
    fun completionSync(): CompletionStage<PromptOutcome<T>> = syncCompletion
    override fun dispose() { cancel() }
    private fun finish(next: PromptStatus, value: T?): Boolean {
        val task = synchronized(this) {
            if (status != PromptStatus.WAITING) return false
            status = next
            timeoutTask
        }
        if (next != PromptStatus.TIMED_OUT) task?.cancel()
        val outcome = PromptOutcome(next, value)
        completion.complete(outcome)
        marshalSync(outcome)
        owner.remove(this)
        return true
    }
    private fun marshalSync(outcome: PromptOutcome<T>) {
        val executor = synchronized(this) { syncExecutor }
        if (executor == null) {
            syncCompletion.completeExceptionally(IllegalStateException("prompt has no main-thread completion executor"))
            return
        }
        try { executor.execute { syncCompletion.complete(outcome) } }
        catch (failure: RuntimeException) { syncCompletion.completeExceptionally(failure) }
    }
}
