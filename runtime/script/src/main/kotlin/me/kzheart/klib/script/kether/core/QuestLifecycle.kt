/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** 一个上下文的终止、原生动作跟踪和空闲完成状态在同一把锁下改变。 */
internal class QuestLifecycle(private val context: AbstractQuestContext<*>) {
    private val steps = AtomicLong()
    @Volatile private var stepLimit = AbstractQuestContext.DEFAULT_STEP_LIMIT
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val idle = CompletableFuture<Void>()
    private var retainDetached = false
    private var rootFinished = false
    @Volatile var isTerminated = false
        private set

    fun runActions(): CompletableFuture<Any?> = synchronized(context) {
        check(context.future == null && !isTerminated) { "already running or terminated" }
        val result = context.rootFrame.run<Any?>()
        context.future = result
        result.whenComplete { _, failure -> synchronized(context) {
            rootFinished = true
            if (!retainDetached && failure == null && context.exitStatus == null) context.exitStatus = ExitStatus.success()
            finishIfIdle()
        } }
        result
    }

    fun retainDetachedActions(): CompletableFuture<Void> = synchronized(context) {
        check(context.future == null && !isTerminated) { "already running or terminated" }
        retainDetached = true
        idle
    }

    fun terminate() = synchronized(context) {
        if (isTerminated) return@synchronized
        isTerminated = true
        context.exitStatus = ExitStatus.paused()
        discardContinuation()
        // 包括脱离根结果的 async，取消不得依赖可能已经关闭的宿主调度器。
        pending.toList().forEach { it.cancel(false) }
        context.rootFrame.close()
        rootFinished = true
        finishIfIdle()
    }

    fun <R> track(action: CompletableFuture<R>): CompletableFuture<R> = synchronized(context) {
        if (isTerminated) action.cancel(false)
        else if (!action.isDone) {
            pending.add(action)
            action.whenComplete { _, _ -> synchronized(context) {
                pending.remove(action)
                finishIfIdle()
            } }
        }
        action
    }

    private fun finishIfIdle() {
        if (retainDetached && rootFinished && !isTerminated && context.exitStatus?.isRunning == true) {
            terminate()
            return
        }
        if (rootFinished && pending.isEmpty()) {
            if (retainDetached && context.exitStatus == null) context.exitStatus = ExitStatus.success()
            idle.complete(null)
        }
    }

    fun setStepLimit(limit: Long) {
        require(limit > 0) { "stepLimit must be positive" }
        stepLimit = limit
    }
    fun tryStep(): Boolean = steps.incrementAndGet() <= stepLimit
    fun stepLimitError(): IllegalStateException = IllegalStateException(
        "Quest '${context.quest.id}' exceeded the execution step limit of $stepLimit actions; the script likely loops forever")
    fun discardContinuation() {
        context.future?.takeUnless { it.isDone }?.completeExceptionally(QuestCloseException())
    }
}

/** 执行器只负责续接的双重退出检查；生命周期和资源释放由上下文管理。 */
internal class QuestContinuationExecutor(private val context: AbstractQuestContext<*>) : Executor {
    @Volatile private var actual: Executor? = null
    override fun execute(command: Runnable) {
        if (stopped()) return
        val executor = actual ?: context.createExecutor().also { actual = it }
        executor.execute { synchronized(context) { if (!stopped()) command.run() } }
    }
    private fun stopped(): Boolean {
        if (!context.isTerminated && !context.getExitStatus().isPresent) return false
        context.discardContinuation()
        return true
    }
}
